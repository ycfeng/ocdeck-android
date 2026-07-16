package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerFrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcSessionConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorState
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcVisitorReadyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.min

class FrpcStcpVisitorManager(
    private val client: FrpcStcpVisitorClient,
    private val healthProbeFactory: OpenCodeHealthProbeFactory,
    private val readinessPolicy: FrpcStcpReadinessPolicy = FrpcStcpReadinessPolicy(),
    private val retryClassifier: FrpcStcpReadinessRetryClassifier = DefaultFrpcStcpReadinessRetryClassifier,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val stateMutex = Mutex()
    private val slots = mutableMapOf<String, ServerSlot>()
    private var nextGenerationOrdinal = 0L

    suspend fun captureConfigLease(serverId: String): FrpcStcpConfigLease = stateMutex.withLock {
        val slot = slots.getOrPut(serverId, ::ServerSlot)
        FrpcStcpConfigLease(serverId = serverId, epoch = slot.configEpoch)
    }

    suspend fun isConfigLeaseCurrent(configLease: FrpcStcpConfigLease): Boolean = stateMutex.withLock {
        slots[configLease.serverId]?.configEpoch == configLease.epoch
    }

    internal suspend fun isReadyTransportCurrent(
        generation: FrpcStcpVisitorGeneration,
        controlEpoch: Long,
    ): Boolean {
        val candidate = stateMutex.withLock {
            val state = slots[generation.serverId]?.current ?: return@withLock null
            val ready = state.ready ?: return@withLock null
            if (
                !isCurrentLocked(state) ||
                state.generation != generation ||
                state.epochRefresh != null ||
                ready.generation != generation ||
                ready.tunnel.controlEpoch != controlEpoch
            ) {
                null
            } else {
                state to ready.tunnel
            }
        } ?: return false
        val nativeState = try {
            client.getState(candidate.second.sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            exception.fatalCauseOrNull()?.let { throw it }
            return false
        }
        if (!nativeState.isReadyFor(candidate.second)) return false
        return stateMutex.withLock {
            val (state, tunnel) = candidate
            isCurrentLocked(state) &&
                state.epochRefresh == null &&
                state.ready?.generation == generation &&
                state.ready?.tunnel == tunnel
        }
    }

    suspend fun ensureReadyVisitor(
        server: ServerConfig,
        credentials: FrpcStcpVisitorCredentials,
        openCodePassword: String?,
        configLease: FrpcStcpConfigLease,
    ): FrpcStcpVisitorReadyResult {
        val config = requireNotNull(server.frpcStcpVisitor) { "frpc STCP visitor is not configured" }
        validateConfig(config, credentials)
        require(configLease.serverId == server.id) { "STCP config lease belongs to another server" }
        val identity = VisitorIdentity(
            baseUrl = server.baseUrl,
            username = server.username,
            password = openCodePassword,
            config = config,
            credentials = credentials,
        )

        val selection = stateMutex.withLock {
            val slot = slots.getOrPut(server.id, ::ServerSlot)
            if (slot.configEpoch != configLease.epoch) {
                return@withLock FlightSelection.Stale
            }

            val current = slot.current
            if (current != null && !current.invalidated && current.identity == identity) {
                current.ready?.let { ready ->
                    current.epochRefresh?.let { refresh ->
                        return@withLock FlightSelection.Refresh(current, refresh, launchWorker = false)
                    }
                    val refresh = EpochRefreshFlight(ready)
                    current.epochRefresh = refresh
                    return@withLock FlightSelection.Refresh(current, refresh, launchWorker = true)
                }
                return@withLock FlightSelection.Await(current, launchWorker = false)
            }
            if (current != null) {
                invalidateLocked(slot, current, FrpcStcpConfigLeaseExpiredException(server.id))
                slot.configEpoch += 1
                return@withLock FlightSelection.StaleWithCleanup(current)
            }

            val state = GenerationState(
                generation = FrpcStcpVisitorGeneration(
                    serverId = server.id,
                    configEpoch = configLease.epoch,
                    ordinal = ++nextGenerationOrdinal,
                ),
                identity = identity,
                predecessorClosed = slot.predecessorClosed,
            )
            slot.current = state
            FlightSelection.Await(state, launchWorker = true)
        }

        when (selection) {
            FlightSelection.Stale -> throw FrpcStcpConfigLeaseExpiredException(server.id)
            is FlightSelection.StaleWithCleanup -> {
                scheduleCleanup(selection.state)
                throw FrpcStcpConfigLeaseExpiredException(server.id)
            }
            is FlightSelection.Await -> {
                if (selection.launchWorker) {
                    launchGeneration(
                        state = selection.state,
                        server = server,
                        config = config,
                        credentials = credentials,
                        openCodePassword = openCodePassword,
                    )
                }
                val payload = selection.state.completion.await()
                return payload.toResult(readinessHealth = payload.health)
            }
            is FlightSelection.Refresh -> {
                if (selection.launchWorker) {
                    launchEpochRefresh(
                        state = selection.state,
                        flight = selection.flight,
                        server = server,
                        openCodePassword = openCodePassword,
                    )
                }
                val refresh = selection.flight.completion.await()
                return refresh.payload.toResult(readinessHealth = refresh.readinessHealth)
            }
        }
    }

    suspend fun invalidateConfiguration(serverId: String) {
        val stateToClean = stateMutex.withLock {
            val slot = slots.getOrPut(serverId, ::ServerSlot)
            slot.configEpoch += 1
            slot.current?.also { current ->
                invalidateLocked(slot, current, FrpcStcpConfigLeaseExpiredException(serverId))
            }
        }
        stateToClean?.let(::scheduleCleanup)
    }

    suspend fun closeVisitor(serverId: String) {
        invalidateConfiguration(serverId)
    }

    private fun launchGeneration(
        state: GenerationState,
        server: ServerConfig,
        config: ServerFrpcStcpVisitorConfig,
        credentials: FrpcStcpVisitorCredentials,
        openCodePassword: String?,
    ) {
        scope.launch(CoroutineName("frpc-stcp-${state.generation.serverId}-${state.generation.ordinal}")) {
            try {
                state.predecessorClosed?.await()
                ensureCurrent(state)

                val sessionId = client.startSession(
                    FrpcSessionConfig(
                        serverAddr = config.serverAddr.trim(),
                        serverPort = config.serverPort,
                        authToken = credentials.authToken.orEmpty(),
                        user = config.user?.takeIf { it.isNotBlank() },
                        transportProtocol = config.transportProtocol.value,
                        wireProtocol = config.wireProtocol.value,
                    ),
                )
                val remainsCurrent = stateMutex.withLock {
                    state.sessionId = sessionId
                    state.sessionAssigned.complete(sessionId)
                    isCurrentLocked(state)
                }
                if (!remainsCurrent) {
                    scheduleCleanup(state)
                    return@launch
                }

                val visitorName = visitorName(server.id)
                val nativeReady = ensureNativeVisitor(
                    state = state,
                    sessionId = sessionId,
                    visitorName = visitorName,
                    config = config,
                    credentials = credentials,
                )
                val tunnel = FrpcStcpVisitorTunnel(
                    sessionId = sessionId,
                    visitorName = visitorName,
                    localPort = nativeReady.bindPort,
                    desiredRevision = nativeReady.desiredRevision,
                    controlEpoch = nativeReady.controlEpoch,
                    effectiveBaseUrl = FrpcStcpVisitorUrlResolver.effectiveBaseUrl(server.baseUrl, nativeReady.bindPort),
                )
                val health = awaitApplicationReadiness(
                    state = state,
                    server = server,
                    password = openCodePassword,
                    effectiveBaseUrl = tunnel.effectiveBaseUrl,
                )
                val payload = ReadyPayload(
                    generation = state.generation,
                    tunnel = tunnel,
                    health = health,
                )
                val accepted = stateMutex.withLock {
                    if (!isCurrentLocked(state)) {
                        false
                    } else {
                        state.ready = payload
                        state.completion.complete(payload)
                        true
                    }
                }
                if (!accepted) {
                    scheduleCleanup(state)
                }
            } catch (cancellation: CancellationException) {
                state.sessionAssigned.complete(null)
                failGeneration(state, cancellation)
                throw cancellation
            } catch (error: Error) {
                state.sessionAssigned.complete(null)
                failGeneration(state, error)
                throw error
            } catch (exception: Exception) {
                exception.fatalCauseOrNull()?.let { fatal ->
                    state.sessionAssigned.complete(null)
                    failGeneration(state, fatal)
                    throw fatal
                }
                state.sessionAssigned.complete(null)
                failGeneration(state, exception.toLocalPortConflict(config.bindPort) ?: exception)
            }
        }
    }

    private fun launchEpochRefresh(
        state: GenerationState,
        flight: EpochRefreshFlight,
        server: ServerConfig,
        openCodePassword: String?,
    ) {
        scope.launch(CoroutineName("frpc-stcp-refresh-${state.generation.serverId}-${state.generation.ordinal}")) {
            try {
                ensureCurrent(state)
                val baseline = flight.baseline
                val tunnel = baseline.tunnel
                val nativeState = client.getState(tunnel.sessionId)
                check(nativeState.sessionId == tunnel.sessionId) {
                    "Native STCP state belongs to another session"
                }
                if (nativeState.isReadyFor(tunnel)) {
                    completeEpochRefresh(
                        state = state,
                        flight = flight,
                        refresh = EpochRefreshPayload(baseline, readinessHealth = null),
                    )
                    return@launch
                }
                check(nativeState.phase !in TERMINAL_SESSION_PHASES) {
                    "Native STCP session is ${nativeState.phase}"
                }

                val ready = client.waitVisitorReady(
                    sessionId = tunnel.sessionId,
                    visitorName = tunnel.visitorName,
                    desiredRevision = tunnel.desiredRevision,
                    timeoutMillis = readinessPolicy.nativeReadyTimeoutMillis,
                )
                validateNativeReady(
                    ready = ready,
                    visitorName = tunnel.visitorName,
                    desiredRevision = tunnel.desiredRevision,
                    minimumControlEpoch = tunnel.controlEpoch,
                )
                ensureCurrent(state)
                val refreshedTunnel = tunnel.copy(controlEpoch = ready.boundControlEpoch)
                val health = awaitApplicationReadiness(
                    state = state,
                    server = server,
                    password = openCodePassword,
                    effectiveBaseUrl = refreshedTunnel.effectiveBaseUrl,
                )
                completeEpochRefresh(
                    state = state,
                    flight = flight,
                    refresh = EpochRefreshPayload(
                        payload = ReadyPayload(
                            generation = state.generation,
                            tunnel = refreshedTunnel,
                            health = health,
                        ),
                        readinessHealth = health,
                    ),
                )
            } catch (cancellation: CancellationException) {
                failGeneration(state, cancellation)
                throw cancellation
            } catch (error: Error) {
                failGeneration(state, error)
                throw error
            } catch (exception: Exception) {
                exception.fatalCauseOrNull()?.let { fatal ->
                    failGeneration(state, fatal)
                    throw fatal
                }
                failGeneration(state, exception)
            }
        }
    }

    private suspend fun ensureNativeVisitor(
        state: GenerationState,
        sessionId: String,
        visitorName: String,
        config: ServerFrpcStcpVisitorConfig,
        credentials: FrpcStcpVisitorCredentials,
    ): NativeVisitorReady {
        var remainingBindRetryMillis = if (state.predecessorClosed != null) {
            readinessPolicy.predecessorBindRetryTimeoutMillis
        } else {
            0
        }
        var retryDelayMillis = readinessPolicy.initialRetryDelayMillis
        while (true) {
            ensureCurrent(state)
            try {
                val ensured = client.ensureVisitor(
                    sessionId = sessionId,
                    visitor = FrpcStcpVisitorConfig(
                        name = visitorName,
                        serverName = config.serverName.trim(),
                        serverUser = config.serverUser?.takeIf { it.isNotBlank() },
                        secretKey = requireNotNull(credentials.secretKey),
                        bindAddr = LOCAL_BIND_ADDR,
                        bindPort = config.bindPort,
                    ),
                )
                val ready = client.waitVisitorReady(
                    sessionId = sessionId,
                    visitorName = visitorName,
                    desiredRevision = ensured.desiredRevision,
                    timeoutMillis = readinessPolicy.nativeReadyTimeoutMillis,
                )
                validateNativeReady(ready, visitorName, ensured.desiredRevision, minimumControlEpoch = 1)
                return NativeVisitorReady(
                    bindPort = ensured.bindPort,
                    desiredRevision = ensured.desiredRevision,
                    controlEpoch = ready.boundControlEpoch,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                exception.fatalCauseOrNull()?.let { throw it }
                if (!exception.isLocalPortConflict() || remainingBindRetryMillis <= 0) {
                    throw exception
                }
                val delayMillis = min(retryDelayMillis, remainingBindRetryMillis)
                delay(delayMillis)
                remainingBindRetryMillis -= delayMillis
                retryDelayMillis = min(retryDelayMillis * 2, readinessPolicy.maxRetryDelayMillis)
            }
        }
    }

    private suspend fun awaitApplicationReadiness(
        state: GenerationState,
        server: ServerConfig,
        password: String?,
        effectiveBaseUrl: String,
    ): ServerHealthDto {
        val probe = healthProbeFactory.create(server, password, effectiveBaseUrl)
        return try {
            withTimeout(readinessPolicy.totalTimeoutMillis) {
                probeUntilReady(state, probe)
            }
        } catch (timeout: TimeoutCancellationException) {
            throw FrpcStcpReadinessTimeoutException(server.id, timeout)
        }
    }

    private suspend fun probeUntilReady(
        state: GenerationState,
        probe: OpenCodeHealthProbe,
    ): ServerHealthDto {
        var retryDelayMillis = readinessPolicy.initialRetryDelayMillis
        while (true) {
            ensureCurrent(state)
            try {
                val health = withTimeoutOrNull(readinessPolicy.attemptTimeoutMillis) {
                    probe.probe()
                } ?: throw OpenCodeHealthAttemptTimeoutException()
                if (health.healthy == false) {
                    throw OpenCodeUnhealthyException()
                }
                return health
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                exception.fatalCauseOrNull()?.let { throw it }
                if (!retryClassifier.isRetryable(exception)) {
                    throw exception
                }
                delay(retryDelayMillis)
                retryDelayMillis = min(retryDelayMillis * 2, readinessPolicy.maxRetryDelayMillis)
            }
        }
    }

    private fun validateNativeReady(
        ready: FrpcVisitorReadyResult,
        visitorName: String,
        desiredRevision: Long,
        minimumControlEpoch: Long,
    ) {
        check(ready.name == visitorName) { "Native STCP visitor readiness returned another visitor" }
        check(ready.desiredRevision == desiredRevision) {
            "Native STCP visitor readiness returned another configuration revision"
        }
        check(
            ready.phase == "ready" &&
                ready.listenerBound &&
                ready.boundControlEpoch >= minimumControlEpoch,
        ) {
            "Native STCP visitor did not own a ready listener in the current control epoch"
        }
    }

    private suspend fun completeEpochRefresh(
        state: GenerationState,
        flight: EpochRefreshFlight,
        refresh: EpochRefreshPayload,
    ) {
        val accepted = stateMutex.withLock {
            if (!isCurrentLocked(state) || state.epochRefresh !== flight) {
                false
            } else {
                state.ready = refresh.payload
                state.epochRefresh = null
                flight.completion.complete(refresh)
                true
            }
        }
        if (!accepted) {
            scheduleCleanup(state)
        }
    }

    private suspend fun ensureCurrent(state: GenerationState) {
        val current = stateMutex.withLock { isCurrentLocked(state) }
        if (!current) {
            throw FrpcStcpGenerationSupersededException(state.generation.serverId)
        }
    }

    private fun isCurrentLocked(state: GenerationState): Boolean {
        val slot = slots[state.generation.serverId]
        return slot?.current === state && !state.invalidated && slot.configEpoch == state.generation.configEpoch
    }

    private suspend fun failGeneration(state: GenerationState, throwable: Throwable) {
        val shouldCleanup = stateMutex.withLock {
            val slot = slots[state.generation.serverId]
            if (slot?.current === state) {
                slot.current = null
                slot.predecessorClosed = state.closed
            }
            state.invalidated = true
            state.completion.completeExceptionally(throwable)
            state.epochRefresh?.completion?.completeExceptionally(throwable)
            state.epochRefresh = null
            markCleanupLocked(state)
        }
        if (shouldCleanup) {
            launchCleanup(state)
        }
    }

    private fun invalidateLocked(
        slot: ServerSlot,
        state: GenerationState,
        throwable: Throwable,
    ) {
        if (slot.current === state) {
            slot.current = null
            slot.predecessorClosed = state.closed
        }
        state.invalidated = true
        state.completion.completeExceptionally(throwable)
        state.epochRefresh?.completion?.completeExceptionally(throwable)
        state.epochRefresh = null
    }

    private fun scheduleCleanup(state: GenerationState) {
        scope.launch {
            val shouldCleanup = stateMutex.withLock { markCleanupLocked(state) }
            if (shouldCleanup) {
                cleanup(state)
            }
        }
    }

    private fun launchCleanup(state: GenerationState) {
        scope.launch { cleanup(state) }
    }

    private suspend fun cleanup(state: GenerationState) {
        try {
            state.sessionAssigned.await()?.let { sessionId ->
                try {
                    client.stopSession(sessionId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (exception: Exception) {
                    exception.fatalCauseOrNull()?.let { throw it }
                    // A stale cleanup failure must not block the next generation.
                }
            }
        } finally {
            state.closed.complete(Unit)
        }
    }

    private fun markCleanupLocked(state: GenerationState): Boolean {
        if (state.cleanupStarted) return false
        state.cleanupStarted = true
        return true
    }

    private fun validateConfig(config: ServerFrpcStcpVisitorConfig, credentials: FrpcStcpVisitorCredentials) {
        require(config.serverAddr.isNotBlank()) { "frps server address is required" }
        require(config.serverPort in 1..65_535) { "frps server port is invalid" }
        require(config.serverName.isNotBlank()) { "STCP server name is required" }
        require(!credentials.secretKey.isNullOrBlank()) { "STCP secret key is required" }
        require(config.bindPort in 1..65_535) { "STCP local bind port is invalid" }
    }

    private fun visitorName(serverId: String): String = "ocdeck_$serverId"

    private companion object {
        const val LOCAL_BIND_ADDR = "127.0.0.1"
        val TERMINAL_SESSION_PHASES = setOf("failed", "stopping", "closed")
    }
}

data class FrpcStcpVisitorCredentials(
    val authToken: String?,
    val secretKey: String?,
) {
    override fun toString(): String =
        "FrpcStcpVisitorCredentials(authToken=<redacted>, secretKey=<redacted>)"
}

class FrpcStcpConfigLease internal constructor(
    val serverId: String,
    val epoch: Long,
)

data class FrpcStcpVisitorGeneration(
    val serverId: String,
    val configEpoch: Long,
    val ordinal: Long,
) {
    override fun toString(): String =
        "FrpcStcpVisitorGeneration(serverId=<redacted>, configEpoch=$configEpoch, ordinal=$ordinal)"
}

data class FrpcStcpVisitorTunnel(
    val sessionId: String,
    val visitorName: String,
    val localPort: Int,
    val desiredRevision: Long,
    val controlEpoch: Long,
    val effectiveBaseUrl: String,
) {
    override fun toString(): String =
        "FrpcStcpVisitorTunnel(sessionId=<redacted>, visitorName=<redacted>, localPort=$localPort, " +
            "desiredRevision=$desiredRevision, controlEpoch=$controlEpoch, effectiveBaseUrl=<redacted>)"
}

data class FrpcStcpVisitorReadyResult(
    val tunnel: FrpcStcpVisitorTunnel,
    val generation: FrpcStcpVisitorGeneration,
    val readinessHealth: ServerHealthDto?,
) {
    override fun toString(): String =
        "FrpcStcpVisitorReadyResult(tunnel=$tunnel, generation=$generation, " +
            "readinessHealthPresent=${readinessHealth != null})"
}

class FrpcStcpConfigLeaseExpiredException(@Suppress("UNUSED_PARAMETER") serverId: String) :
    OpenCodeRequestException(OpenCodeFailure.TransportChanged)

private class FrpcStcpGenerationSupersededException(@Suppress("UNUSED_PARAMETER") serverId: String) :
    CancellationException()

internal object FrpcStcpVisitorUrlResolver {
    fun effectiveBaseUrl(baseUrl: String, localPort: Int): String {
        val url = baseUrl.trim().trimEnd('/').toHttpUrl()
        return url.newBuilder()
            .host("127.0.0.1")
            .port(localPort)
            .build()
            .toString()
            .trimEnd('/')
    }
}

private data class VisitorIdentity(
    val baseUrl: String,
    val username: String?,
    val password: String?,
    val config: ServerFrpcStcpVisitorConfig,
    val credentials: FrpcStcpVisitorCredentials,
) {
    override fun toString(): String =
        "VisitorIdentity(baseUrl=<redacted>, " +
            "username=${if (username == null) "null" else "<redacted>"}, " +
            "password=${if (password == null) "null" else "<redacted>"}, " +
            "configPresent=true, credentialsPresent=true)"
}

private data class ReadyPayload(
    val generation: FrpcStcpVisitorGeneration,
    val tunnel: FrpcStcpVisitorTunnel,
    val health: ServerHealthDto,
) {
    override fun toString(): String =
        "ReadyPayload(generation=$generation, tunnel=$tunnel, healthPresent=true)"
}

private data class NativeVisitorReady(
    val bindPort: Int,
    val desiredRevision: Long,
    val controlEpoch: Long,
)

private fun ReadyPayload.toResult(readinessHealth: ServerHealthDto?): FrpcStcpVisitorReadyResult =
    FrpcStcpVisitorReadyResult(
        tunnel = tunnel,
        generation = generation,
        readinessHealth = readinessHealth,
    )

private data class ServerSlot(
    var configEpoch: Long = 0,
    var current: GenerationState? = null,
    var predecessorClosed: CompletableDeferred<Unit>? = null,
)

private class GenerationState(
    val generation: FrpcStcpVisitorGeneration,
    val identity: VisitorIdentity,
    val predecessorClosed: CompletableDeferred<Unit>?,
) {
    val completion = CompletableDeferred<ReadyPayload>()
    val sessionAssigned = CompletableDeferred<String?>()
    val closed = CompletableDeferred<Unit>()
    var sessionId: String? = null
    var ready: ReadyPayload? = null
    var epochRefresh: EpochRefreshFlight? = null
    var invalidated = false
    var cleanupStarted = false
}

private sealed interface FlightSelection {
    data object Stale : FlightSelection
    data class StaleWithCleanup(val state: GenerationState) : FlightSelection
    data class Await(val state: GenerationState, val launchWorker: Boolean) : FlightSelection
    data class Refresh(
        val state: GenerationState,
        val flight: EpochRefreshFlight,
        val launchWorker: Boolean,
    ) : FlightSelection
}

private class EpochRefreshFlight(
    val baseline: ReadyPayload,
) {
    val completion = CompletableDeferred<EpochRefreshPayload>()
}

private data class EpochRefreshPayload(
    val payload: ReadyPayload,
    val readinessHealth: ServerHealthDto?,
) {
    override fun toString(): String =
        "EpochRefreshPayload(payload=$payload, readinessHealthPresent=${readinessHealth != null})"
}

private fun FrpcStcpVisitorState.isReadyFor(tunnel: FrpcStcpVisitorTunnel): Boolean {
    val visitor = visitors[tunnel.visitorName] ?: return false
    return phase == "open" &&
        controlEpoch == tunnel.controlEpoch &&
        visitor.desiredRevision == tunnel.desiredRevision &&
        visitor.phase == "ready" &&
        visitor.listenerBound &&
        visitor.boundControlEpoch == controlEpoch
}

private fun Throwable.toLocalPortConflict(localPort: Int): LocalPortInUseException? {
    if (!isLocalPortConflict()) return null
    return LocalPortInUseException(localPort, this)
}

private fun Throwable.isLocalPortConflict(): Boolean = generateSequence(this) { it.cause }
    .any { throwable ->
        throwable is java.net.BindException || throwable.message.orEmpty().lowercase().let { message ->
            "address already in use" in message || "eaddrinuse" in message
        }
    }
