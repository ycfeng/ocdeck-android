package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerFrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcSessionConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorNetworkChangeAware
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorState
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcVisitorReadyResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.BindException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.io.EOFException
import java.io.IOException
import retrofit2.HttpException
import kotlin.math.min

class FrpcStcpVisitorManager(
    private val client: FrpcStcpVisitorClient,
    private val healthProbeFactory: OpenCodeHealthProbeFactory,
    private val readinessPolicy: FrpcStcpReadinessPolicy = FrpcStcpReadinessPolicy(),
    private val retryClassifier: FrpcStcpReadinessRetryClassifier = DefaultFrpcStcpReadinessRetryClassifier,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val monotonicTimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
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
            throw cancelled.fatalCauseOrNull() ?: cancelled
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

    suspend fun reconnectAfterDefaultNetworkChange() {
        (client as? FrpcStcpVisitorNetworkChangeAware)?.reconnectAfterDefaultNetworkChange()
    }

    internal suspend fun captureRecoveryStage(
        generation: FrpcStcpVisitorGeneration,
        baselineControlEpoch: Long,
    ): FrpcStcpRecoveryStage {
        val candidate = stateMutex.withLock {
            val state = slots[generation.serverId]?.current
            if (state == null || !isCurrentLocked(state) || state.generation != generation) {
                null
            } else {
                val ready = state.ready
                RecoveryStageCandidate(
                    sessionId = state.sessionId ?: ready?.tunnel?.sessionId,
                    visitorName = ready?.tunnel?.visitorName,
                    managerControlEpoch = ready?.tunnel?.controlEpoch,
                    refreshInProgress = state.epochRefresh != null,
                    refreshDiagnostics = state.refreshDiagnostics.snapshot(),
                )
            }
        } ?: return FrpcStcpRecoveryStage.GENERATION_NOT_CURRENT
        val sessionId = candidate.sessionId ?: return FrpcStcpRecoveryStage.SAMPLE_UNAVAILABLE
        val nativeState = try {
            client.getState(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled.fatalCauseOrNull() ?: cancelled
        } catch (exception: Exception) {
            exception.fatalCauseOrNull()?.let { throw it }
            return FrpcStcpRecoveryStage.SAMPLE_UNAVAILABLE
        }
        when (nativeState.phase) {
            "connecting" -> return FrpcStcpRecoveryStage.CONTROL_CONNECTING
            "reconnecting" -> return when (nativeState.lastError) {
                "transport failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT
                "transport connect failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CONNECT
                "transport connect timed out" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CONNECT_TIMEOUT
                "transport tls setup failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_TLS_SETUP
                "transport tls handshake failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_TLS_HANDSHAKE
                "transport tls handshake timed out" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TLS_HANDSHAKE_TIMEOUT
                "transport read failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT_READ
                "transport write failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT_WRITE
                "transport closed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT_CLOSED
                "transport configuration invalid" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_TRANSPORT_INVALID_CONFIGURATION
                "yamux transport failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX_TRANSPORT
                "yamux protocol failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX_PROTOCOL
                "yamux stream open timed out" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX_STREAM_OPEN_TIMEOUT
                "yamux timed out" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX_TIMEOUT
                "yamux failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_YAMUX
                "control reconnect connecting transport" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CONNECTING_TRANSPORT
                "control reconnect opening control stream" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_OPENING_CONTROL_STREAM
                "control reconnect waiting login" -> if (nativeState.controlEpoch > baselineControlEpoch) {
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_WAITING_LOGIN_AFTER_NEW_EPOCH
                } else {
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_WAITING_LOGIN
                }
                "control reconnect login timer scheduled" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_TIMER_SCHEDULED
                "control reconnect login timer fired" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_TIMER_FIRED
                "control reconnect aborting login timeout" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_ABORTING_LOGIN_TIMEOUT
                "control reconnect waiting login after timeout" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_WAITING_LOGIN_AFTER_TIMEOUT
                "control reconnect closing transport" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_TRANSPORT
                "control reconnect joining jobs" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_JOINING_JOBS
                "control reconnect closing work" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_WORK
                "control reconnect closing client streams" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_CLIENT_STREAMS
                "control reconnect closing yamux" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_YAMUX
                "control reconnect closing failed establishment" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CLOSING_FAILED_ESTABLISHMENT
                "login timed out" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_TIMEOUT
                "login rejected" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_REJECTED
                "login protocol failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_LOGIN_PROTOCOL
                "control crypto failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO
                "control crypto invalid key" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_INVALID_KEY
                "control crypto invalid nonce" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_INVALID_NONCE
                "control crypto truncated nonce" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_TRUNCATED_NONCE
                "control crypto truncated header" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_TRUNCATED_HEADER
                "control crypto truncated record" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_TRUNCATED_RECORD
                "control crypto invalid length" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_INVALID_LENGTH
                "control crypto integrity failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_INTEGRITY
                "control crypto nonce exhausted" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_NONCE_EXHAUSTED
                "control crypto record limit" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_RECORD_LIMIT
                "control crypto unsupported algorithm" ->
                    FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_UNSUPPORTED_ALGORITHM
                "control crypto io failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_IO
                "control crypto write failed" -> FrpcStcpRecoveryStage.CONTROL_RETRYING_CRYPTO_WRITE
                else -> FrpcStcpRecoveryStage.CONTROL_RETRYING
            }
            "failed", "stopping", "closed" -> return FrpcStcpRecoveryStage.CONTROL_FAILED
            "open" -> Unit
            else -> return FrpcStcpRecoveryStage.SAMPLE_UNAVAILABLE
        }
        if (nativeState.controlEpoch <= baselineControlEpoch) {
            return FrpcStcpRecoveryStage.CONTROL_OLD_OPEN
        }
        val visitor = candidate.visitorName?.let(nativeState.visitors::get)
        if (
            visitor == null ||
            visitor.phase != "ready" ||
            !visitor.listenerBound ||
            visitor.boundControlEpoch != nativeState.controlEpoch
        ) {
            return FrpcStcpRecoveryStage.VISITOR_NOT_READY
        }
        if (candidate.managerControlEpoch == nativeState.controlEpoch && !candidate.refreshInProgress) {
            return FrpcStcpRecoveryStage.MANAGER_READY
        }
        return candidate.refreshDiagnostics.toRecoveryStage(candidate.refreshInProgress)
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
                        tlsServerCertificateSha256 = credentials.tlsServerCertificateSha256,
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
                val fatal = cancellation.fatalCauseOrNull() ?: cancellation
                state.sessionAssigned.complete(null)
                failGeneration(state, fatal)
                throw fatal
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
                markRefreshStarted(state, flight)
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
                    timeoutMillis = readinessPolicy.epochRefreshNativeReadyTimeoutMillis,
                )
                validateNativeReady(
                    ready = ready,
                    visitorName = tunnel.visitorName,
                    desiredRevision = tunnel.desiredRevision,
                    minimumControlEpoch = maxOf(tunnel.controlEpoch, nativeState.controlEpoch),
                )
                ensureCurrent(state)
                val refreshedTunnel = tunnel.copy(controlEpoch = ready.boundControlEpoch)
                markHealthProbing(state, flight, refreshedTunnel.controlEpoch)
                val health = awaitApplicationReadiness(
                    state = state,
                    server = server,
                    password = openCodePassword,
                    effectiveBaseUrl = refreshedTunnel.effectiveBaseUrl,
                    refresh = RefreshHealthContext(flight, refreshedTunnel.controlEpoch),
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
                val fatal = cancellation.fatalCauseOrNull() ?: cancellation
                failGeneration(state, fatal)
                throw fatal
            } catch (error: Error) {
                failGeneration(state, error)
                throw error
            } catch (exception: Exception) {
                exception.fatalCauseOrNull()?.let { fatal ->
                    failGeneration(state, fatal)
                    throw fatal
                }
                if (exception.isRetryableEpochRefreshTimeout()) {
                    failEpochRefresh(state, flight, exception)
                } else {
                    failGeneration(state, exception)
                }
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
        val firstBindConflict = try {
            return checkNotNull(
                ensureNativeVisitorAttempt(state, sessionId, visitorName, config, credentials),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation.fatalCauseOrNull() ?: cancellation
        } catch (exception: Exception) {
            exception.fatalCauseOrNull()?.let { throw it }
            if (!exception.isLocalPortConflict()) throw exception
            exception
        }
        val retryTimeoutMillis = readinessPolicy.predecessorBindRetryTimeoutMillis
        if (state.predecessorClosed == null || retryTimeoutMillis <= 0) throw firstBindConflict

        val retryDeadlineMillis = saturatingDeadlineMillis(monotonicTimeMillis(), retryTimeoutMillis)
        var lastBindConflict = firstBindConflict
        var retryDelayMillis = readinessPolicy.initialRetryDelayMillis
        val retried: NativeVisitorReady? = withTimeoutOrNull(retryTimeoutMillis) {
            var ready: NativeVisitorReady? = null
            var budgetExpired = false
            while (ready == null && !budgetExpired) {
                delay(retryDelayMillis)
                retryDelayMillis = min(retryDelayMillis * 2, readinessPolicy.maxRetryDelayMillis)
                try {
                    val attempted = ensureNativeVisitorAttempt(
                        state,
                        sessionId,
                        visitorName,
                        config,
                        credentials,
                        retryDeadlineMillis,
                    )
                    if (attempted == null) budgetExpired = true else ready = attempted
                } catch (cancellation: CancellationException) {
                    throw cancellation.fatalCauseOrNull() ?: cancellation
                } catch (exception: Exception) {
                    exception.fatalCauseOrNull()?.let { throw it }
                    if (!exception.isLocalPortConflict()) throw exception
                    lastBindConflict = exception
                }
            }
            ready
        }
        return retried ?: throw lastBindConflict
    }

    private suspend fun ensureNativeVisitorAttempt(
        state: GenerationState,
        sessionId: String,
        visitorName: String,
        config: ServerFrpcStcpVisitorConfig,
        credentials: FrpcStcpVisitorCredentials,
        retryDeadlineMillis: Long? = null,
    ): NativeVisitorReady? {
        ensureCurrent(state)
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
        val readyTimeoutMillis = if (retryDeadlineMillis == null) {
            readinessPolicy.nativeReadyTimeoutMillis
        } else {
            val remainingBudgetMillis = remainingUntilDeadlineMillis(
                retryDeadlineMillis,
                monotonicTimeMillis(),
            )
            if (remainingBudgetMillis <= 0) return null
            min(readinessPolicy.nativeReadyTimeoutMillis, remainingBudgetMillis).coerceAtLeast(1L)
        }
        val ready = client.waitVisitorReady(
            sessionId = sessionId,
            visitorName = visitorName,
            desiredRevision = ensured.desiredRevision,
            timeoutMillis = readyTimeoutMillis,
        )
        validateNativeReady(ready, visitorName, ensured.desiredRevision, minimumControlEpoch = 1)
        return NativeVisitorReady(
            bindPort = ensured.bindPort,
            desiredRevision = ensured.desiredRevision,
            controlEpoch = ready.boundControlEpoch,
        )
    }

    private suspend fun awaitApplicationReadiness(
        state: GenerationState,
        server: ServerConfig,
        password: String?,
        effectiveBaseUrl: String,
        refresh: RefreshHealthContext? = null,
    ): ServerHealthDto {
        val probe = healthProbeFactory.create(server, password, effectiveBaseUrl)
        return try {
            withTimeout(readinessPolicy.totalTimeoutMillis) {
                probeUntilReady(state, probe, refresh)
            }
        } catch (timeout: TimeoutCancellationException) {
            refresh?.let {
                withContext(NonCancellable) {
                    markHealthFailed(state, it.flight, it.controlEpoch, HealthFailure.TOTAL_TIMEOUT)
                }
            }
            throw FrpcStcpReadinessTimeoutException(server.id, timeout)
        }
    }

    private suspend fun probeUntilReady(
        state: GenerationState,
        probe: OpenCodeHealthProbe,
        refresh: RefreshHealthContext?,
    ): ServerHealthDto {
        var retryDelayMillis = readinessPolicy.initialRetryDelayMillis
        while (true) {
            ensureCurrent(state)
            refresh?.let { markHealthAttempt(state, it.flight, it.controlEpoch) }
            try {
                val health = withTimeoutOrNull(readinessPolicy.attemptTimeoutMillis) {
                    probe.probe()
                } ?: throw OpenCodeHealthAttemptTimeoutException()
                if (health.healthy == false) {
                    throw OpenCodeUnhealthyException()
                }
                return health
            } catch (cancellation: CancellationException) {
                throw cancellation.fatalCauseOrNull() ?: cancellation
            } catch (exception: Exception) {
                exception.fatalCauseOrNull()?.let { throw it }
                val retryable = retryClassifier.isRetryable(exception)
                refresh?.let {
                    markHealthFailure(
                        state = state,
                        flight = it.flight,
                        controlEpoch = it.controlEpoch,
                        failure = exception.toHealthFailure(retryable),
                        terminal = !retryable,
                    )
                }
                if (!retryable) {
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
                state.refreshDiagnostics.markReady(refresh.payload.tunnel.controlEpoch)
                flight.completion.complete(refresh)
                true
            }
        }
        if (!accepted) {
            scheduleCleanup(state)
        }
    }

    private suspend fun failEpochRefresh(
        state: GenerationState,
        flight: EpochRefreshFlight,
        throwable: Throwable,
    ) {
        stateMutex.withLock {
            if (isCurrentLocked(state) && state.epochRefresh === flight) {
                state.epochRefresh = null
                state.refreshDiagnostics.markFailed(
                    when {
                        throwable is KotlinFrpcStcpVisitorException &&
                            throwable.failure == KotlinFrpcStcpVisitorFailure.WAIT_TIMEOUT -> HealthFailure.NATIVE_TIMEOUT
                        throwable is FrpcStcpReadinessTimeoutException -> HealthFailure.TOTAL_TIMEOUT
                        else -> state.refreshDiagnostics.failure.takeUnless { it == HealthFailure.NONE }
                            ?: HealthFailure.NON_RETRYABLE
                    },
                )
                flight.completion.completeExceptionally(throwable)
            }
        }
    }

    private suspend fun markRefreshStarted(state: GenerationState, flight: EpochRefreshFlight) {
        stateMutex.withLock {
            if (isCurrentLocked(state) && state.epochRefresh === flight) {
                state.refreshDiagnostics.markNativeWaiting()
            }
        }
    }

    private suspend fun markHealthProbing(
        state: GenerationState,
        flight: EpochRefreshFlight,
        controlEpoch: Long,
    ) {
        stateMutex.withLock {
            if (isCurrentLocked(state) && state.epochRefresh === flight) {
                state.refreshDiagnostics.markHealthProbing(controlEpoch)
            }
        }
    }

    private suspend fun markHealthAttempt(
        state: GenerationState,
        flight: EpochRefreshFlight,
        controlEpoch: Long,
    ) {
        stateMutex.withLock {
            if (isCurrentLocked(state) && state.epochRefresh === flight) {
                state.refreshDiagnostics.markHealthAttempt(controlEpoch)
            }
        }
    }

    private suspend fun markHealthFailure(
        state: GenerationState,
        flight: EpochRefreshFlight,
        controlEpoch: Long,
        failure: HealthFailure,
        terminal: Boolean,
    ) {
        stateMutex.withLock {
            if (isCurrentLocked(state) && state.epochRefresh === flight) {
                state.refreshDiagnostics.markHealthFailure(controlEpoch, failure, terminal)
            }
        }
    }

    private suspend fun markHealthFailed(
        state: GenerationState,
        flight: EpochRefreshFlight,
        controlEpoch: Long,
        failure: HealthFailure,
    ) {
        markHealthFailure(state, flight, controlEpoch, failure, terminal = true)
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
                    throw cancelled.fatalCauseOrNull() ?: cancelled
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
        require(credentials.tlsServerCertificateSha256 == null ||
            TLS_SERVER_CERTIFICATE_SHA256.matches(credentials.tlsServerCertificateSha256)
        ) { "frps TLS certificate pin is invalid" }
    }

    private fun visitorName(serverId: String): String = "ocdeck_$serverId"

    private companion object {
        const val LOCAL_BIND_ADDR = "127.0.0.1"
        val TERMINAL_SESSION_PHASES = setOf("failed", "stopping", "closed")
        val TLS_SERVER_CERTIFICATE_SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class FrpcStcpVisitorCredentials(
    val authToken: String?,
    val secretKey: String?,
    val tlsServerCertificateSha256: String? = null,
) {
    override fun toString(): String =
        "FrpcStcpVisitorCredentials(authToken=<redacted>, secretKey=<redacted>, " +
            "tlsServerCertificateSha256=${if (tlsServerCertificateSha256 == null) "null" else "<redacted>"})"
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

internal enum class FrpcStcpRecoveryStage {
    GENERATION_NOT_CURRENT,
    SAMPLE_UNAVAILABLE,
    CONTROL_CONNECTING,
    CONTROL_OLD_OPEN,
    CONTROL_RETRYING,
    CONTROL_RETRYING_TRANSPORT,
    CONTROL_RETRYING_CONNECT,
    CONTROL_RETRYING_CONNECT_TIMEOUT,
    CONTROL_RETRYING_TLS_SETUP,
    CONTROL_RETRYING_TLS_HANDSHAKE,
    CONTROL_RETRYING_TLS_HANDSHAKE_TIMEOUT,
    CONTROL_RETRYING_TRANSPORT_READ,
    CONTROL_RETRYING_TRANSPORT_WRITE,
    CONTROL_RETRYING_TRANSPORT_CLOSED,
    CONTROL_RETRYING_TRANSPORT_INVALID_CONFIGURATION,
    CONTROL_RETRYING_YAMUX_TRANSPORT,
    CONTROL_RETRYING_YAMUX_PROTOCOL,
    CONTROL_RETRYING_YAMUX_STREAM_OPEN_TIMEOUT,
    CONTROL_RETRYING_YAMUX_TIMEOUT,
    CONTROL_RETRYING_YAMUX,
    CONTROL_RETRYING_CONNECTING_TRANSPORT,
    CONTROL_RETRYING_OPENING_CONTROL_STREAM,
    CONTROL_RETRYING_WAITING_LOGIN,
    CONTROL_RETRYING_WAITING_LOGIN_AFTER_NEW_EPOCH,
    CONTROL_RETRYING_LOGIN_TIMER_SCHEDULED,
    CONTROL_RETRYING_LOGIN_TIMER_FIRED,
    CONTROL_RETRYING_ABORTING_LOGIN_TIMEOUT,
    CONTROL_RETRYING_WAITING_LOGIN_AFTER_TIMEOUT,
    CONTROL_RETRYING_CLOSING_TRANSPORT,
    CONTROL_RETRYING_JOINING_JOBS,
    CONTROL_RETRYING_CLOSING_WORK,
    CONTROL_RETRYING_CLOSING_CLIENT_STREAMS,
    CONTROL_RETRYING_CLOSING_YAMUX,
    CONTROL_RETRYING_CLOSING_FAILED_ESTABLISHMENT,
    CONTROL_RETRYING_LOGIN_TIMEOUT,
    CONTROL_RETRYING_LOGIN_REJECTED,
    CONTROL_RETRYING_LOGIN_PROTOCOL,
    CONTROL_RETRYING_CRYPTO,
    CONTROL_RETRYING_CRYPTO_INVALID_KEY,
    CONTROL_RETRYING_CRYPTO_INVALID_NONCE,
    CONTROL_RETRYING_CRYPTO_TRUNCATED_NONCE,
    CONTROL_RETRYING_CRYPTO_TRUNCATED_HEADER,
    CONTROL_RETRYING_CRYPTO_TRUNCATED_RECORD,
    CONTROL_RETRYING_CRYPTO_INVALID_LENGTH,
    CONTROL_RETRYING_CRYPTO_INTEGRITY,
    CONTROL_RETRYING_CRYPTO_NONCE_EXHAUSTED,
    CONTROL_RETRYING_CRYPTO_RECORD_LIMIT,
    CONTROL_RETRYING_CRYPTO_UNSUPPORTED_ALGORITHM,
    CONTROL_RETRYING_CRYPTO_IO,
    CONTROL_RETRYING_CRYPTO_WRITE,
    CONTROL_FAILED,
    VISITOR_NOT_READY,
    MANAGER_REFRESHING,
    MANAGER_REFRESH_NOT_STARTED,
    MANAGER_NATIVE_TIMEOUT,
    MANAGER_HEALTH_PROBING,
    MANAGER_HEALTH_ATTEMPT_TIMEOUT,
    MANAGER_HEALTH_CONNECT,
    MANAGER_HEALTH_SOCKET_TIMEOUT,
    MANAGER_HEALTH_EOF,
    MANAGER_HEALTH_SOCKET,
    MANAGER_HEALTH_UNHEALTHY,
    MANAGER_HEALTH_HTTP_RETRYABLE,
    MANAGER_HEALTH_OTHER_RETRYABLE,
    MANAGER_HEALTH_NON_RETRYABLE,
    MANAGER_HEALTH_TOTAL_TIMEOUT,
    MANAGER_NOT_READY,
    MANAGER_READY,
}

class FrpcStcpConfigLeaseExpiredException(@Suppress("UNUSED_PARAMETER") serverId: String) :
    OpenCodeRequestException(OpenCodeFailure.TransportChanged)

class FrpcTlsServerCertificatePinUnavailableException :
    IllegalStateException("The stored frp TLS server certificate pin is unavailable")

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

private data class RecoveryStageCandidate(
    val sessionId: String?,
    val visitorName: String?,
    val managerControlEpoch: Long?,
    val refreshInProgress: Boolean,
    val refreshDiagnostics: RefreshDiagnosticsSnapshot,
) {
    override fun toString(): String =
        "RecoveryStageCandidate(sessionAssigned=${sessionId != null}, visitorAssigned=${visitorName != null}, " +
            "managerControlEpoch=$managerControlEpoch, refreshInProgress=$refreshInProgress, " +
            "refreshDiagnostics=$refreshDiagnostics)"
}

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
    val refreshDiagnostics = RefreshDiagnostics()
    var invalidated = false
    var cleanupStarted = false
}

private enum class RefreshPhase {
    NOT_STARTED,
    NATIVE_WAITING,
    HEALTH_PROBING,
    READY,
    FAILED,
}

private enum class HealthFailure {
    NONE,
    NATIVE_TIMEOUT,
    ATTEMPT_TIMEOUT,
    CONNECT,
    SOCKET_TIMEOUT,
    EOF,
    SOCKET,
    UNHEALTHY,
    HTTP_RETRYABLE,
    OTHER_RETRYABLE,
    NON_RETRYABLE,
    TOTAL_TIMEOUT,
}

private class RefreshDiagnostics {
    var phase = RefreshPhase.NOT_STARTED
        private set
    var failure = HealthFailure.NONE
        private set
    private var targetControlEpoch: Long? = null
    private var launchCount = 0L
    private var healthAttemptCount = 0L
    private var healthFailureCount = 0L

    fun markNativeWaiting() {
        phase = RefreshPhase.NATIVE_WAITING
        failure = HealthFailure.NONE
        targetControlEpoch = null
        launchCount = launchCount.saturatingIncrement()
    }

    fun markHealthProbing(controlEpoch: Long) {
        phase = RefreshPhase.HEALTH_PROBING
        failure = HealthFailure.NONE
        targetControlEpoch = controlEpoch
    }

    fun markHealthAttempt(controlEpoch: Long) {
        phase = RefreshPhase.HEALTH_PROBING
        targetControlEpoch = controlEpoch
        healthAttemptCount = healthAttemptCount.saturatingIncrement()
    }

    fun markHealthFailure(controlEpoch: Long, currentFailure: HealthFailure, terminal: Boolean) {
        phase = if (terminal) RefreshPhase.FAILED else RefreshPhase.HEALTH_PROBING
        failure = currentFailure
        targetControlEpoch = controlEpoch
        healthFailureCount = healthFailureCount.saturatingIncrement()
    }

    fun markFailed(currentFailure: HealthFailure) {
        phase = RefreshPhase.FAILED
        failure = currentFailure
    }

    fun markReady(controlEpoch: Long) {
        phase = RefreshPhase.READY
        failure = HealthFailure.NONE
        targetControlEpoch = controlEpoch
    }

    fun snapshot(): RefreshDiagnosticsSnapshot = RefreshDiagnosticsSnapshot(
        phase = phase,
        failure = failure,
        targetControlEpoch = targetControlEpoch,
        launchCount = launchCount,
        healthAttemptCount = healthAttemptCount,
        healthFailureCount = healthFailureCount,
    )
}

private data class RefreshDiagnosticsSnapshot(
    val phase: RefreshPhase,
    val failure: HealthFailure,
    val targetControlEpoch: Long?,
    val launchCount: Long,
    val healthAttemptCount: Long,
    val healthFailureCount: Long,
) {
    fun toRecoveryStage(refreshInProgress: Boolean): FrpcStcpRecoveryStage = when (phase) {
        RefreshPhase.NOT_STARTED -> FrpcStcpRecoveryStage.MANAGER_REFRESH_NOT_STARTED
        RefreshPhase.NATIVE_WAITING -> FrpcStcpRecoveryStage.MANAGER_REFRESHING
        RefreshPhase.HEALTH_PROBING,
        RefreshPhase.FAILED,
        -> failure.toRecoveryStage(
            fallback = if (refreshInProgress) {
                FrpcStcpRecoveryStage.MANAGER_HEALTH_PROBING
            } else {
                FrpcStcpRecoveryStage.MANAGER_NOT_READY
            },
        )
        RefreshPhase.READY -> FrpcStcpRecoveryStage.MANAGER_NOT_READY
    }

    override fun toString(): String =
        "RefreshDiagnosticsSnapshot(phase=$phase, failure=$failure, " +
            "targetControlEpoch=$targetControlEpoch, launchCount=$launchCount, " +
            "healthAttemptCount=$healthAttemptCount, healthFailureCount=$healthFailureCount)"
}

private data class RefreshHealthContext(
    val flight: EpochRefreshFlight,
    val controlEpoch: Long,
)

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
    return sessionId == tunnel.sessionId &&
        phase == "open" &&
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

private fun Throwable.isLocalPortConflict(): Boolean = causeChain().any { throwable ->
    throwable is BindException ||
        throwable is KotlinFrpcStcpVisitorException &&
        throwable.failure == KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT
}

private fun Throwable.isRetryableEpochRefreshTimeout(): Boolean =
    this is FrpcStcpReadinessTimeoutException ||
        this is KotlinFrpcStcpVisitorException && failure == KotlinFrpcStcpVisitorFailure.WAIT_TIMEOUT

private fun Throwable.toHealthFailure(retryable: Boolean): HealthFailure {
    val causes = causeChain()
    return when {
        causes.any { it is OpenCodeHealthAttemptTimeoutException } -> HealthFailure.ATTEMPT_TIMEOUT
        causes.any { it is ConnectException } -> HealthFailure.CONNECT
        causes.any { it is SocketTimeoutException } -> HealthFailure.SOCKET_TIMEOUT
        causes.any { it is EOFException } -> HealthFailure.EOF
        causes.any { it is SocketException } -> HealthFailure.SOCKET
        causes.any { it is OpenCodeUnhealthyException } -> HealthFailure.UNHEALTHY
        retryable && causes.any { it is HttpException } -> HealthFailure.HTTP_RETRYABLE
        !retryable -> HealthFailure.NON_RETRYABLE
        causes.any { it is IOException } -> HealthFailure.OTHER_RETRYABLE
        else -> HealthFailure.OTHER_RETRYABLE
    }
}

private fun HealthFailure.toRecoveryStage(fallback: FrpcStcpRecoveryStage): FrpcStcpRecoveryStage = when (this) {
    HealthFailure.NONE -> fallback
    HealthFailure.NATIVE_TIMEOUT -> FrpcStcpRecoveryStage.MANAGER_NATIVE_TIMEOUT
    HealthFailure.ATTEMPT_TIMEOUT -> FrpcStcpRecoveryStage.MANAGER_HEALTH_ATTEMPT_TIMEOUT
    HealthFailure.CONNECT -> FrpcStcpRecoveryStage.MANAGER_HEALTH_CONNECT
    HealthFailure.SOCKET_TIMEOUT -> FrpcStcpRecoveryStage.MANAGER_HEALTH_SOCKET_TIMEOUT
    HealthFailure.EOF -> FrpcStcpRecoveryStage.MANAGER_HEALTH_EOF
    HealthFailure.SOCKET -> FrpcStcpRecoveryStage.MANAGER_HEALTH_SOCKET
    HealthFailure.UNHEALTHY -> FrpcStcpRecoveryStage.MANAGER_HEALTH_UNHEALTHY
    HealthFailure.HTTP_RETRYABLE -> FrpcStcpRecoveryStage.MANAGER_HEALTH_HTTP_RETRYABLE
    HealthFailure.OTHER_RETRYABLE -> FrpcStcpRecoveryStage.MANAGER_HEALTH_OTHER_RETRYABLE
    HealthFailure.NON_RETRYABLE -> FrpcStcpRecoveryStage.MANAGER_HEALTH_NON_RETRYABLE
    HealthFailure.TOTAL_TIMEOUT -> FrpcStcpRecoveryStage.MANAGER_HEALTH_TOTAL_TIMEOUT
}

private fun Long.saturatingIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1L

private fun saturatingDeadlineMillis(startMillis: Long, timeoutMillis: Long): Long =
    if (startMillis > Long.MAX_VALUE - timeoutMillis) Long.MAX_VALUE else startMillis + timeoutMillis

private fun remainingUntilDeadlineMillis(deadlineMillis: Long, nowMillis: Long): Long {
    if (nowMillis >= deadlineMillis) return 0
    val remainingMillis = deadlineMillis - nowMillis
    return if (remainingMillis < 0) Long.MAX_VALUE else remainingMillis
}
