package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerFrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcEnsureVisitorResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcSessionConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorState
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStopSessionResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcVisitorReadyResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcVisitorRuntimeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrpcStcpVisitorManagerContractTest {
    @Test
    fun malformedNativeReadyNeverPublishesTunnelOrStartsHealth() = runTest {
        val cases: List<Pair<String, (String, Long) -> FrpcVisitorReadyResult>> = listOf(
            "wrong visitor" to { _, desiredRevision ->
                readyResult(WRONG_VISITOR_ID, desiredRevision)
            },
            "wrong revision" to { visitorName, desiredRevision ->
                readyResult(visitorName, desiredRevision + 1)
            },
            "non-ready phase" to { visitorName, desiredRevision ->
                readyResult(visitorName, desiredRevision, phase = "starting")
            },
            "listener not bound" to { visitorName, desiredRevision ->
                readyResult(visitorName, desiredRevision, listenerBound = false)
            },
            "zero control epoch" to { visitorName, desiredRevision ->
                readyResult(visitorName, desiredRevision, controlEpoch = 0)
            },
        )

        cases.forEach { (label, malformedResult) ->
            val client = ContractFrpcStcpVisitorClient()
            val probes = ContractHealthProbeFactory()
            val manager = manager(client, probes)
            val server = contractServer()
            val lease = manager.captureConfigLease(server.id)
            client.onWaitVisitorReady = { _, visitorName, desiredRevision, _, call ->
                if (call == 1) malformedResult(visitorName, desiredRevision) else readyResult(visitorName, desiredRevision)
            }

            val failure = failureOf {
                manager.ensureReadyVisitor(server, contractCredentials(), SYNTHETIC_PASSWORD, lease)
            }
            runCurrent()

            assertTrue("$label must fail readiness", failure is IllegalStateException)
            assertEquals("$label must not create a health probe", 0, probes.createCount)
            assertEquals("$label must not invoke health", 0, probes.probeCount)
            assertEquals(
                "$label must clean its exact session",
                listOf(contractSessionId(1)),
                client.stopAttempts,
            )
            assertSafeToString("$label failure", failure)

            val replacement = manager.ensureReadyVisitor(
                server,
                contractCredentials(),
                SYNTHETIC_PASSWORD,
                lease,
            )

            assertEquals("$label must build a replacement", 2, client.startCount)
            assertEquals("$label must not cache a tunnel", contractSessionId(2), replacement.tunnel.sessionId)
            assertEquals("$label replacement must run health", 1, probes.probeCount)
        }
    }

    @Test
    fun nonTerminalRuntimeAnomaliesRequireNativeAndApplicationRecovery() = runTest {
        val anomalies: List<Pair<String, (ContractFrpcStcpVisitorClient, String) -> FrpcStcpVisitorState>> = listOf(
            "session not open" to { client, sessionId ->
                client.runtimeState(sessionId, sessionPhase = "connecting")
            },
            "visitor missing" to { client, sessionId ->
                client.runtimeState(sessionId, reportedVisitorName = null)
            },
            "visitor name mismatch" to { client, sessionId ->
                client.runtimeState(sessionId, reportedVisitorName = WRONG_VISITOR_ID)
            },
            "visitor revision mismatch" to { client, sessionId ->
                client.runtimeState(
                    sessionId,
                    reportedDesiredRevision = client.desiredRevision(sessionId) + 1,
                )
            },
        )

        anomalies.forEach { (label, anomalousState) ->
            val client = ContractFrpcStcpVisitorClient()
            val probes = ContractHealthProbeFactory()
            val manager = manager(client, probes)
            val server = contractServer()
            val lease = manager.captureConfigLease(server.id)
            val initial = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)
            client.onGetState = { sessionId, _ -> anomalousState(client, sessionId) }

            assertFalse(
                "$label must invalidate the cached transport check",
                manager.isReadyTransportCurrent(initial.generation, initial.tunnel.controlEpoch),
            )
            assertEquals("$label transport check must not run health", 1, probes.probeCount)

            val recovered = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)

            assertEquals("$label may recover only after native readiness", 2, client.waitReadyCount)
            assertEquals("$label may recover only after application health", 2, probes.probeCount)
            assertEquals("$label recovery stays in the current generation", initial.generation, recovered.generation)
            assertEquals("$label recovery revalidates the same tunnel", initial.tunnel, recovered.tunnel)
            assertNotNull("$label recovery returns health evidence", recovered.readinessHealth)
            assertEquals("$label must not start another session", 1, client.startCount)
            assertEquals("$label must not re-ensure the visitor", 1, client.ensureCount)
        }
    }

    @Test
    fun runtimeStateFromAnotherSessionCannotPassTheFinalReadinessBarrier() = runTest {
        val client = ContractFrpcStcpVisitorClient()
        val probes = ContractHealthProbeFactory()
        val manager = manager(client, probes)
        val server = contractServer()
        val lease = manager.captureConfigLease(server.id)
        val initial = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)
        client.onGetState = { sessionId, _ ->
            client.runtimeState(
                sessionId = sessionId,
                reportedSessionId = contractSessionId(99),
            )
        }

        assertFalse(
            manager.isReadyTransportCurrent(initial.generation, initial.tunnel.controlEpoch),
        )
        val failure = failureOf {
            manager.ensureReadyVisitor(server, contractCredentials(), null, lease)
        }
        runCurrent()

        assertTrue(failure is IllegalStateException)
        assertEquals("foreign session state must not run health", 1, probes.probeCount)
        assertEquals(
            "foreign session state must clean the old session",
            listOf(initial.tunnel.sessionId),
            client.stopAttempts,
        )
        assertSafeToString("foreign session failure", failure)

        client.onGetState = null
        val replacement = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)

        assertNotEquals(initial.generation, replacement.generation)
        assertEquals(contractSessionId(2), replacement.tunnel.sessionId)
        assertEquals(2, probes.probeCount)
    }

    @Test
    fun terminalRuntimeStateFailsGenerationAndNextCallReplacesIt() = runTest {
        listOf("failed", "stopping", "closed").forEach { terminalPhase ->
            val client = ContractFrpcStcpVisitorClient()
            val probes = ContractHealthProbeFactory()
            val manager = manager(client, probes)
            val server = contractServer()
            val lease = manager.captureConfigLease(server.id)
            val initial = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)
            client.onGetState = { sessionId, _ ->
                client.runtimeState(sessionId, sessionPhase = terminalPhase)
            }

            val failure = failureOf {
                manager.ensureReadyVisitor(server, contractCredentials(), null, lease)
            }
            runCurrent()

            assertTrue("$terminalPhase must fail the generation", failure is IllegalStateException)
            assertEquals("$terminalPhase must not wait on a terminal session", 1, client.waitReadyCount)
            assertEquals("$terminalPhase must not run health", 1, probes.probeCount)
            assertEquals("$terminalPhase must clean the old session", 1, client.stopAttempts.size)
            assertSafeToString("$terminalPhase failure", failure)

            client.onGetState = null
            val replacement = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)

            assertNotEquals("$terminalPhase must use a new generation", initial.generation, replacement.generation)
            assertEquals("$terminalPhase must use a new session", contractSessionId(2), replacement.tunnel.sessionId)
            assertEquals("$terminalPhase replacement must become healthy", 2, probes.probeCount)
        }
    }

    @Test
    fun rolledBackControlEpochAndOldReadyCannotReuseTunnel() = runTest {
        val client = ContractFrpcStcpVisitorClient().apply {
            onWaitVisitorReady = { _, visitorName, desiredRevision, _, call ->
                val controlEpoch = when (call) {
                    1 -> 2L
                    2 -> 1L
                    else -> 3L
                }
                readyResult(visitorName, desiredRevision, controlEpoch = controlEpoch)
            }
        }
        val probes = ContractHealthProbeFactory()
        val manager = manager(client, probes)
        val server = contractServer()
        val lease = manager.captureConfigLease(server.id)
        val initial = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)
        client.onGetState = { sessionId, _ ->
            client.runtimeState(
                sessionId = sessionId,
                controlEpoch = 1,
                reportedBoundControlEpoch = 1,
            )
        }

        assertFalse(manager.isReadyTransportCurrent(initial.generation, initial.tunnel.controlEpoch))
        val failure = failureOf {
            manager.ensureReadyVisitor(server, contractCredentials(), null, lease)
        }
        runCurrent()

        assertTrue(failure is IllegalStateException)
        assertEquals("old-epoch native ready must not run health", 1, probes.probeCount)
        assertEquals("old-epoch native ready must fail after a new wait", 2, client.waitReadyCount)
        assertEquals("failed generation must clean the old session", 1, client.stopAttempts.size)
        assertSafeToString("old epoch failure", failure)

        client.onGetState = null
        val replacement = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)

        assertNotEquals(initial.generation, replacement.generation)
        assertEquals(contractSessionId(2), replacement.tunnel.sessionId)
        assertEquals(3L, replacement.tunnel.controlEpoch)
        assertEquals(2, probes.probeCount)
    }

    @Test
    fun readyOlderThanObservedControlEpochFailsRefresh() = runTest {
        val client = ContractFrpcStcpVisitorClient().apply {
            onWaitVisitorReady = { _, visitorName, desiredRevision, _, call ->
                val controlEpoch = when (call) {
                    1, 2 -> 1L
                    else -> 3L
                }
                readyResult(visitorName, desiredRevision, controlEpoch = controlEpoch)
            }
        }
        val probes = ContractHealthProbeFactory()
        val manager = manager(client, probes)
        val server = contractServer()
        val lease = manager.captureConfigLease(server.id)
        val initial = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)
        client.onGetState = { sessionId, _ ->
            client.runtimeState(
                sessionId = sessionId,
                controlEpoch = 2,
                visitorPhase = "starting",
                listenerBound = false,
                reportedBoundControlEpoch = 0,
            )
        }

        val failure = failureOf {
            manager.ensureReadyVisitor(server, contractCredentials(), null, lease)
        }
        runCurrent()

        assertTrue(failure is IllegalStateException)
        assertEquals("stale native ready must not run health", 1, probes.probeCount)
        assertEquals("stale native ready must fail after a new wait", 2, client.waitReadyCount)
        assertEquals(
            "stale native ready must clean the old session",
            listOf(initial.tunnel.sessionId),
            client.stopAttempts,
        )
        assertSafeToString("stale native ready failure", failure)

        client.onGetState = null
        val replacement = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)

        assertNotEquals(initial.generation, replacement.generation)
        assertEquals(contractSessionId(2), replacement.tunnel.sessionId)
        assertEquals(3L, replacement.tunnel.controlEpoch)
        assertEquals(2, probes.probeCount)
    }

    @Test
    fun ensureVisitorBindPortRemainsFinalAcrossEpochRecovery() = runTest {
        val ensuredBindPort = 50_961
        val client = ContractFrpcStcpVisitorClient(ensuredBindPort = ensuredBindPort)
        val probes = ContractHealthProbeFactory()
        val manager = manager(client, probes)
        val server = contractServer(bindPort = 4_096)
        val lease = manager.captureConfigLease(server.id)
        val initial = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)

        client.readyControlEpoch = 2
        client.onGetState = { sessionId, _ ->
            client.runtimeState(
                sessionId = sessionId,
                controlEpoch = 2,
                reportedBoundControlEpoch = 2,
            )
        }
        val recovered = manager.ensureReadyVisitor(server, contractCredentials(), null, lease)

        assertEquals(4_096, client.visitorConfigs.single().bindPort)
        assertEquals(ensuredBindPort, initial.tunnel.localPort)
        assertEquals(ensuredBindPort, recovered.tunnel.localPort)
        assertEquals("https://127.0.0.1:$ensuredBindPort/private", recovered.tunnel.effectiveBaseUrl)
        assertEquals(listOf(recovered.tunnel.effectiveBaseUrl, recovered.tunnel.effectiveBaseUrl), probes.effectiveBaseUrls)
        assertEquals(initial.generation, recovered.generation)
        assertEquals(1, client.ensureCount)
        assertEquals(2, client.waitReadyCount)
        assertEquals(2, probes.probeCount)
    }

    @Test
    fun ordinaryStopFailureDoesNotBlockStateCleanupOrReplacement() = runTest {
        val stopFailure = IllegalStateException("Synthetic cleanup failure")
        val client = ContractFrpcStcpVisitorClient().apply {
            onStopSession = { sessionId, call ->
                if (call == 1) throw stopFailure
                FrpcStopSessionResult(sessionId = sessionId, phase = "closed")
            }
        }
        val probes = ContractHealthProbeFactory()
        val manager = manager(client, probes)
        val server = contractServer()
        val initialLease = manager.captureConfigLease(server.id)
        val initial = manager.ensureReadyVisitor(server, contractCredentials(), null, initialLease)

        manager.invalidateConfiguration(server.id)
        runCurrent()
        val replacementLease = manager.captureConfigLease(server.id)
        val replacement = manager.ensureReadyVisitor(server, contractCredentials(), null, replacementLease)

        assertEquals(1, client.stopAttempts.size)
        assertNotEquals(initial.generation, replacement.generation)
        assertEquals(contractSessionId(2), replacement.tunnel.sessionId)
        assertTrue(manager.isReadyTransportCurrent(replacement.generation, replacement.tunnel.controlEpoch))
        assertSafeToString("ordinary stop failure", stopFailure)
    }

    @Test
    fun cleanupJvmErrorPropagatesToScopeAndStillUnblocksReplacement() = runTest {
        val fatal = ContractCleanupJvmError()
        val reported = CompletableDeferred<Throwable>()
        val workerJob = SupervisorJob()
        val workerScope = CoroutineScope(
            workerJob + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> reported.complete(throwable) },
        )
        val client = ContractFrpcStcpVisitorClient().apply {
            onStopSession = { sessionId, call ->
                if (call == 1) throw fatal
                FrpcStopSessionResult(sessionId = sessionId, phase = "closed")
            }
        }
        val manager = manager(client, ContractHealthProbeFactory(), workerScope)
        val server = contractServer()

        try {
            val initialLease = manager.captureConfigLease(server.id)
            val initial = manager.ensureReadyVisitor(server, contractCredentials(), null, initialLease)
            manager.invalidateConfiguration(server.id)
            runCurrent()

            assertSame(fatal, reported.await())
            assertSafeToString("cleanup JVM Error", fatal)

            val replacementLease = manager.captureConfigLease(server.id)
            val replacement = manager.ensureReadyVisitor(server, contractCredentials(), null, replacementLease)

            assertNotEquals(initial.generation, replacement.generation)
            assertEquals(contractSessionId(2), replacement.tunnel.sessionId)
        } finally {
            workerJob.cancel()
        }
    }

    @Test
    fun contractFailuresAndObjectsHaveSecretFreeToString() = runTest {
        val client = ContractFrpcStcpVisitorClient()
        val probes = ContractHealthProbeFactory()
        val manager = manager(client, probes)
        val server = contractServer()
        val credentials = contractCredentials()
        val lease = manager.captureConfigLease(server.id)
        val ready = manager.ensureReadyVisitor(server, credentials, SYNTHETIC_PASSWORD, lease)

        assertTrue(manager.isReadyTransportCurrent(ready.generation, ready.tunnel.controlEpoch))
        client.onGetState = { sessionId, _ -> client.runtimeState(sessionId, reportedVisitorName = null) }
        client.onWaitVisitorReady = { _, _, desiredRevision, _, _ ->
            readyResult(WRONG_VISITOR_ID, desiredRevision)
        }
        val failure = failureOf {
            manager.ensureReadyVisitor(server, credentials, SYNTHETIC_PASSWORD, lease)
        }
        runCurrent()

        val objects = listOf(
            server,
            requireNotNull(server.frpcStcpVisitor),
            credentials,
            client,
            probes,
            client.startedConfigs.single(),
            client.visitorConfigs.single(),
            client.ensureResults.single(),
            client.waitReadyResults.first(),
            client.waitReadyResults.last(),
            client.reportedStates.first(),
            client.stopResults.single(),
            ready.generation,
            ready.tunnel,
            ready,
            failure,
        )

        objects.forEachIndexed { index, value ->
            assertSafeToString("contract object #$index", value)
        }
        assertTrue(credentials.toString().contains("<redacted>"))
        assertTrue(ready.tunnel.toString().contains("<redacted>"))
    }

    private fun TestScope.manager(
        client: ContractFrpcStcpVisitorClient,
        probes: ContractHealthProbeFactory,
        scope: CoroutineScope = backgroundScope,
    ): FrpcStcpVisitorManager = FrpcStcpVisitorManager(
        client = client,
        healthProbeFactory = probes,
        readinessPolicy = FrpcStcpReadinessPolicy(
            nativeReadyTimeoutMillis = 1_000,
            totalTimeoutMillis = 10_000,
            attemptTimeoutMillis = 1_000,
            initialRetryDelayMillis = 10,
            maxRetryDelayMillis = 100,
            predecessorBindRetryTimeoutMillis = 100,
        ),
        scope = scope,
    )
}

private class ContractFrpcStcpVisitorClient(
    private val ensuredBindPort: Int = 50_960,
) : FrpcStcpVisitorClient {
    var startCount = 0
        private set
    var ensureCount = 0
        private set
    var waitReadyCount = 0
        private set
    var getStateCount = 0
        private set
    var readyControlEpoch = 1L
    val startedConfigs = mutableListOf<FrpcSessionConfig>()
    val visitorConfigs = mutableListOf<FrpcStcpVisitorConfig>()
    val ensureResults = mutableListOf<FrpcEnsureVisitorResult>()
    val waitReadyResults = mutableListOf<FrpcVisitorReadyResult>()
    val reportedStates = mutableListOf<FrpcStcpVisitorState>()
    val stopAttempts = mutableListOf<String>()
    val stopResults = mutableListOf<FrpcStopSessionResult>()
    var onWaitVisitorReady: (suspend (String, String, Long, Long, Int) -> FrpcVisitorReadyResult)? = null
    var onGetState: (suspend (String, Int) -> FrpcStcpVisitorState)? = null
    var onStopSession: (suspend (String, Int) -> FrpcStopSessionResult)? = null
    private val runtimes = mutableMapOf<String, ContractVisitorRuntime>()

    override suspend fun startSession(config: FrpcSessionConfig): String {
        startCount += 1
        startedConfigs += config
        return contractSessionId(startCount)
    }

    override suspend fun ensureVisitor(
        sessionId: String,
        visitor: FrpcStcpVisitorConfig,
    ): FrpcEnsureVisitorResult {
        ensureCount += 1
        visitorConfigs += visitor
        val result = FrpcEnsureVisitorResult(
            bindPort = ensuredBindPort,
            desiredRevision = ensureCount.toLong(),
        )
        ensureResults += result
        runtimes[sessionId] = ContractVisitorRuntime(
            visitorName = visitor.name,
            desiredRevision = result.desiredRevision,
        )
        return result
    }

    override suspend fun waitVisitorReady(
        sessionId: String,
        visitorName: String,
        desiredRevision: Long,
        timeoutMillis: Long,
    ): FrpcVisitorReadyResult {
        waitReadyCount += 1
        val result = onWaitVisitorReady?.invoke(
            sessionId,
            visitorName,
            desiredRevision,
            timeoutMillis,
            waitReadyCount,
        ) ?: readyResult(
            visitorName = visitorName,
            desiredRevision = desiredRevision,
            controlEpoch = readyControlEpoch,
        )
        waitReadyResults += result
        runtimes[sessionId]?.controlEpoch = result.boundControlEpoch
        return result
    }

    override suspend fun stopVisitor(sessionId: String, visitorName: String) = Unit

    override suspend fun stopSession(sessionId: String, timeoutMillis: Long): FrpcStopSessionResult {
        stopAttempts += sessionId
        val result = onStopSession?.invoke(sessionId, stopAttempts.size)
            ?: FrpcStopSessionResult(sessionId = sessionId, phase = "closed")
        stopResults += result
        return result
    }

    override suspend fun getState(sessionId: String): FrpcStcpVisitorState {
        getStateCount += 1
        val state = onGetState?.invoke(sessionId, getStateCount) ?: runtimeState(sessionId)
        reportedStates += state
        return state
    }

    fun desiredRevision(sessionId: String): Long = runtimes.getValue(sessionId).desiredRevision

    fun runtimeState(
        sessionId: String,
        reportedSessionId: String = sessionId,
        sessionPhase: String = "open",
        controlEpoch: Long = runtimes.getValue(sessionId).controlEpoch,
        reportedVisitorName: String? = runtimes.getValue(sessionId).visitorName,
        reportedDesiredRevision: Long = runtimes.getValue(sessionId).desiredRevision,
        visitorPhase: String = "ready",
        listenerBound: Boolean = true,
        reportedBoundControlEpoch: Long = controlEpoch,
    ): FrpcStcpVisitorState = FrpcStcpVisitorState(
        sessionId = reportedSessionId,
        phase = sessionPhase,
        controlEpoch = controlEpoch,
        visitors = if (reportedVisitorName == null) {
            emptyMap()
        } else {
            mapOf(
                reportedVisitorName to FrpcVisitorRuntimeState(
                    desiredRevision = reportedDesiredRevision,
                    phase = visitorPhase,
                    listenerBound = listenerBound,
                    boundControlEpoch = reportedBoundControlEpoch,
                ),
            )
        },
    )

    override fun toString(): String =
        "ContractFrpcStcpVisitorClient(startCount=$startCount, ensureCount=$ensureCount, " +
            "waitReadyCount=$waitReadyCount, getStateCount=$getStateCount, " +
            "stopAttemptCount=${stopAttempts.size})"
}

private class ContractVisitorRuntime(
    val visitorName: String,
    val desiredRevision: Long,
    var controlEpoch: Long = 0,
) {
    override fun toString(): String =
        "ContractVisitorRuntime(desiredRevision=$desiredRevision, controlEpoch=$controlEpoch)"
}

private class ContractHealthProbeFactory : OpenCodeHealthProbeFactory {
    var createCount = 0
        private set
    var probeCount = 0
        private set
    val effectiveBaseUrls = mutableListOf<String>()

    override fun create(
        server: ServerConfig,
        password: String?,
        effectiveBaseUrl: String,
    ): OpenCodeHealthProbe {
        createCount += 1
        effectiveBaseUrls += effectiveBaseUrl
        return OpenCodeHealthProbe {
            probeCount += 1
            ServerHealthDto(healthy = true, version = "contract-health-$probeCount")
        }
    }

    override fun toString(): String =
        "ContractHealthProbeFactory(createCount=$createCount, probeCount=$probeCount)"
}

private class ContractCleanupJvmError : Error()

private fun readyResult(
    visitorName: String,
    desiredRevision: Long,
    phase: String = "ready",
    listenerBound: Boolean = true,
    controlEpoch: Long = 1,
): FrpcVisitorReadyResult = FrpcVisitorReadyResult(
    name = visitorName,
    desiredRevision = desiredRevision,
    phase = phase,
    listenerBound = listenerBound,
    boundControlEpoch = controlEpoch,
)

private fun contractServer(
    bindPort: Int = 4_096,
): ServerConfig = ServerConfig(
    id = SYNTHETIC_SERVER_ID,
    name = "K0 contract server",
    baseUrl = SYNTHETIC_BASE_URL,
    username = SYNTHETIC_USERNAME,
    passwordKey = SYNTHETIC_PASSWORD_ALIAS,
    frpcStcpVisitor = ServerFrpcStcpVisitorConfig(
        serverAddr = SYNTHETIC_FRPS_ENDPOINT,
        serverPort = 7_000,
        authTokenKey = SYNTHETIC_AUTH_TOKEN_ALIAS,
        user = SYNTHETIC_FRP_USER,
        serverUser = SYNTHETIC_SERVER_USER,
        serverName = SYNTHETIC_SERVER_NAME,
        secretKeyKey = SYNTHETIC_SECRET_ALIAS,
        bindPort = bindPort,
    ),
)

private fun contractCredentials(): FrpcStcpVisitorCredentials = FrpcStcpVisitorCredentials(
    authToken = SYNTHETIC_AUTH_TOKEN,
    secretKey = SYNTHETIC_SECRET_KEY,
)

private suspend fun failureOf(block: suspend () -> Any?): Throwable =
    runCatching { block() }.exceptionOrNull() ?: throw AssertionError("Expected operation to fail")

private fun assertSafeToString(label: String, value: Any) {
    val renderedValues = if (value is Throwable) {
        generateSequence(value) { throwable -> throwable.cause }.map(Any::toString).toList()
    } else {
        listOf(value.toString())
    }
    renderedValues.forEachIndexed { causeIndex, rendered ->
        SYNTHETIC_SENSITIVE_VALUES.forEachIndexed { valueIndex, sensitiveValue ->
            assertFalse(
                "$label cause #$causeIndex leaked synthetic value #$valueIndex",
                rendered.contains(sensitiveValue),
            )
        }
    }
}

private fun contractSessionId(ordinal: Int): String = "$SYNTHETIC_SESSION_PREFIX$ordinal"

private const val SYNTHETIC_SERVER_ID = "k0-server-private-id"
private const val SYNTHETIC_SESSION_PREFIX = "k0-session-private-"
private const val SYNTHETIC_FRPS_ENDPOINT = "frps.k0-private.invalid"
private const val SYNTHETIC_BASE_URL = "https://opencode.k0-private.invalid:8443/private/"
private const val SYNTHETIC_USERNAME = "k0-private-user"
private const val SYNTHETIC_PASSWORD = "k0-private-password"
private const val SYNTHETIC_PASSWORD_ALIAS = "k0-private-password-alias"
private const val SYNTHETIC_AUTH_TOKEN = "k0-private-auth-token"
private const val SYNTHETIC_AUTH_TOKEN_ALIAS = "k0-private-auth-token-alias"
private const val SYNTHETIC_SECRET_KEY = "k0-private-secret-key"
private const val SYNTHETIC_SECRET_ALIAS = "k0-private-secret-alias"
private const val SYNTHETIC_FRP_USER = "k0-private-frp-user"
private const val SYNTHETIC_SERVER_USER = "k0-private-server-user"
private const val SYNTHETIC_SERVER_NAME = "k0-private-stcp-server"
private const val WRONG_VISITOR_ID = "k0-wrong-private-visitor"
private const val SYNTHETIC_EFFECTIVE_BASE_URL = "https://127.0.0.1:50960/private"

private val SYNTHETIC_SENSITIVE_VALUES = listOf(
    SYNTHETIC_SERVER_ID,
    contractSessionId(1),
    "ocdeck_$SYNTHETIC_SERVER_ID",
    WRONG_VISITOR_ID,
    SYNTHETIC_FRPS_ENDPOINT,
    SYNTHETIC_BASE_URL,
    SYNTHETIC_USERNAME,
    SYNTHETIC_PASSWORD,
    SYNTHETIC_PASSWORD_ALIAS,
    SYNTHETIC_AUTH_TOKEN,
    SYNTHETIC_AUTH_TOKEN_ALIAS,
    SYNTHETIC_SECRET_KEY,
    SYNTHETIC_SECRET_ALIAS,
    SYNTHETIC_FRP_USER,
    SYNTHETIC_SERVER_USER,
    SYNTHETIC_SERVER_NAME,
    SYNTHETIC_EFFECTIVE_BASE_URL,
)
