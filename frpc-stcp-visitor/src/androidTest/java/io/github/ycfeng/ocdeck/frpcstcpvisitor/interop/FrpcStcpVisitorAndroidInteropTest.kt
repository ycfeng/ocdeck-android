package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcSessionConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorState
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeApiMismatchException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeUnavailableException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileFrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorFailure
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcSessionCleanupDiagnostics
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class FrpcStcpVisitorAndroidInteropTest {
    @Test
    fun runConfiguredBackend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_ENABLED) == "true")

        val runId = requireSafeRunId(arguments.getString(ARG_RUN_ID))
        val backend = requireBackend(arguments.getString(ARG_BACKEND))
        val configFileName = requireSafeFileName(arguments.getString(ARG_CONFIG_FILE), ".json")
        val resultFileName = requireSafeFileName(arguments.getString(ARG_RESULT_FILE), ".json")
        val context = instrumentation.context
        val configFile = privateFile(context, configFileName)
        val resultFile = privateFile(context, resultFileName)
        val progress = ScenarioProgress()
        var invocation: AndroidInteropInvocation? = null
        var stage = STAGE_CONFIGURATION

        val result = try {
            val configuredInvocation = readInvocation(configFile).also {
                validateInvocation(it, runId, backend)
            }
            invocation = configuredInvocation
            runBlocking {
                withTimeout(SCENARIO_TIMEOUT_MILLIS) {
                    runScenario(context, configuredInvocation, progress) { current -> stage = current }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Error) {
            throw failure
        } catch (failure: Throwable) {
            failedResult(
                invocation = invocation ?: invalidInvocation(runId, backend),
                progress = progress,
                stage = stage,
                failure = failure,
            )
        } finally {
            configFile.delete()
        }

        try {
            writeResult(resultFile, result)
        } catch (_: Exception) {
            fail("frpc Android interop result write failed")
        }
        if (result.status != STATUS_PASSED) {
            fail("frpc Android interop failed at ${result.failedStage} with ${result.failureCode}")
        }
    }

    private suspend fun runScenario(
        context: Context,
        invocation: AndroidInteropInvocation,
        progress: ScenarioProgress,
        updateStage: (String) -> Unit,
    ): AndroidInteropResult {
        when (invocation.scenario.kind) {
            SCENARIO_SUCCESS -> runSuccessScenario(invocation, progress, updateStage)
            SCENARIO_WRONG_TOKEN -> runWrongTokenScenario(invocation, progress, updateStage)
            SCENARIO_WRONG_SECRET -> runWrongSecretScenario(invocation, progress, updateStage)
            SCENARIO_BIND_CONFLICT -> runBindConflictScenario(invocation, progress, updateStage)
            SCENARIO_RESTART -> runRestartScenario(context, invocation, progress, updateStage)
            else -> throw AndroidInteropAssertionException()
        }
        if (progress.checks != expectedChecks(invocation.scenario.kind) ||
            progress.semanticOutcome != expectedSemanticOutcome(invocation.scenario.kind)
        ) {
            throw AndroidInteropAssertionException()
        }
        return AndroidInteropResult(
            schemaVersion = SCHEMA_VERSION,
            runId = invocation.runId,
            backend = invocation.backend,
            caseId = invocation.caseId,
            scenario = invocation.scenario,
            wireProtocol = invocation.wireProtocol,
            useEncryption = invocation.useEncryption,
            useCompression = invocation.useCompression,
            status = STATUS_PASSED,
            semanticOutcome = progress.semanticOutcome,
            bindPort = progress.bindPort,
            checks = progress.checks.toList(),
        )
    }

    private suspend fun runSuccessScenario(
        invocation: AndroidInteropInvocation,
        progress: ScenarioProgress,
        updateStage: (String) -> Unit,
    ) {
        val bindPort = resolveBindPort(invocation.bindPort).also { progress.bindPort = it }
        val client = createClient(invocation.backend)
        var sessionId: String? = null
        var globalSse: AndroidLiveSseSession? = null
        var projectSse: AndroidLiveSseSession? = null
        var primaryFailure: Throwable? = null
        try {
            updateStage(STAGE_SESSION_START)
            val activeSessionId = startSession(client, invocation)
            sessionId = activeSessionId
            updateStage(STAGE_VISITOR_SETUP)
            val desiredRevision = ensureVisitor(client, activeSessionId, invocation, bindPort)
            updateStage(STAGE_VISITOR_READY)
            waitReadyAndVerify(client, activeSessionId, desiredRevision)
            progress.add(CHECK_OPEN_STATE)

            updateStage(STAGE_TUNNEL_HEALTH)
            AndroidTunnelProbe.health(bindPort)
            progress.add(CHECK_HEALTH)

            updateStage(STAGE_GLOBAL_SSE)
            val activeGlobalSse = AndroidTunnelProbe.openSseSession(bindPort, "/global/event", GLOBAL_READY_DATA)
            globalSse = activeGlobalSse
            progress.add(CHECK_GLOBAL_SSE)

            updateStage(STAGE_PROJECT_SSE)
            val activeProjectSse = AndroidTunnelProbe.openSseSession(bindPort, "/event", PROJECT_READY_DATA)
            projectSse = activeProjectSse
            progress.add(CHECK_PROJECT_SSE)

            updateStage(STAGE_CONCURRENT_TRAFFIC)
            AndroidTunnelProbe.concurrentFlowControlTraffic(bindPort, activeGlobalSse, activeProjectSse)
            progress.add(CHECK_CONCURRENT_REST_SSE)
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val sseCleanupFailure = closeSseSessions(globalSse, projectSse)
        val cleanupFailure = cleanupClient(
            client = client,
            sessionId = sessionId,
            bindPort = bindPort,
            progress = progress,
            updateStage = { cleanupStage ->
                if (primaryFailure == null && sseCleanupFailure == null) updateStage(cleanupStage)
            },
        )
        throwSelected(primaryFailure, sseCleanupFailure, cleanupFailure)
        progress.complete(OUTCOME_SUCCESS)
    }

    private suspend fun runWrongTokenScenario(
        invocation: AndroidInteropInvocation,
        progress: ScenarioProgress,
        updateStage: (String) -> Unit,
    ) {
        val bindPort = resolveBindPort(invocation.bindPort).also { progress.bindPort = it }
        val client = createClient(invocation.backend)
        var sessionId: String? = null
        var primaryFailure: Throwable? = null
        try {
            updateStage(STAGE_SESSION_START)
            val activeSessionId = startSession(client, invocation)
            sessionId = activeSessionId
            updateStage(STAGE_CONTROL_REJECTION)
            var rejected = false
            try {
                val desiredRevision = ensureVisitor(client, activeSessionId, invocation, bindPort)
                client.waitVisitorReady(
                    activeSessionId,
                    VISITOR_NAME,
                    desiredRevision,
                    VISITOR_READY_TIMEOUT_MILLIS,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Error) {
                throw failure
            } catch (failure: AndroidInteropAssertionException) {
                throw failure
            } catch (failure: Exception) {
                if (!isExpectedControlRejection(invocation.backend, failure)) {
                    throw AndroidInteropUnexpectedFailureKindException(
                        "UNEXPECTED_CONTROL_${exceptionCategory(failure)}",
                    )
                }
                rejected = true
            }
            if (!rejected) throw AndroidInteropAssertionException()
            progress.add(CHECK_CONTROL_REJECTED)

            updateStage(STAGE_PUBLIC_STATE)
            val failed = awaitPublicState(client, activeSessionId, PUBLIC_STATE_TIMEOUT_MILLIS) { state ->
                state.phase == "failed"
            }
            progress.add(CHECK_SESSION_FAILED)
            val visitor = failed.visitors[VISITOR_NAME]
            if (visitor?.listenerBound == true || visitor?.phase == "ready") {
                throw AndroidInteropAssertionException()
            }
            progress.add(CHECK_LISTENER_NOT_BOUND)
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = cleanupClient(
            client = client,
            sessionId = sessionId,
            bindPort = bindPort,
            progress = progress,
            ignoreStopVisitorFailure = true,
            updateStage = { cleanupStage -> if (primaryFailure == null) updateStage(cleanupStage) },
        )
        throwSelected(primaryFailure, cleanupFailure)
        progress.complete(OUTCOME_CONTROL_FAILURE)
    }

    private suspend fun runWrongSecretScenario(
        invocation: AndroidInteropInvocation,
        progress: ScenarioProgress,
        updateStage: (String) -> Unit,
    ) {
        val bindPort = resolveBindPort(invocation.bindPort).also { progress.bindPort = it }
        val client = createClient(invocation.backend)
        var sessionId: String? = null
        var primaryFailure: Throwable? = null
        try {
            updateStage(STAGE_SESSION_START)
            val activeSessionId = startSession(client, invocation)
            sessionId = activeSessionId
            updateStage(STAGE_VISITOR_SETUP)
            val desiredRevision = ensureVisitor(client, activeSessionId, invocation, bindPort)
            updateStage(STAGE_VISITOR_READY)
            val readyEpoch = waitReadyAndVerify(client, activeSessionId, desiredRevision)
            progress.add(CHECK_OPEN_STATE)

            updateStage(STAGE_TUNNEL_HEALTH)
            val rejected = try {
                AndroidTunnelProbe.health(bindPort)
                false
            } catch (_: AndroidTunnelProbeException) {
                true
            }
            if (!rejected) throw AndroidInteropAssertionException()
            progress.add(CHECK_HEALTH_REJECTED)

            updateStage(STAGE_PUBLIC_STATE)
            val state = client.getState(activeSessionId)
            val visitor = state.visitors[VISITOR_NAME]
            if (state.phase != "open" || state.controlEpoch != readyEpoch || visitor == null ||
                visitor.phase != "ready" || !visitor.listenerBound || visitor.desiredRevision != desiredRevision ||
                visitor.boundControlEpoch != readyEpoch
            ) {
                throw AndroidInteropAssertionException()
            }
            progress.add(CHECK_LISTENER_CONSISTENT)
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = cleanupClient(
            client = client,
            sessionId = sessionId,
            bindPort = bindPort,
            progress = progress,
            updateStage = { cleanupStage -> if (primaryFailure == null) updateStage(cleanupStage) },
        )
        throwSelected(primaryFailure, cleanupFailure)
        progress.complete(OUTCOME_STCP_SECRET_REJECTED)
    }

    private suspend fun runBindConflictScenario(
        invocation: AndroidInteropInvocation,
        progress: ScenarioProgress,
        updateStage: (String) -> Unit,
    ) {
        val occupied = occupyLoopbackPort(invocation.bindPort)
        val bindPort = occupied.localPort.also { progress.bindPort = it }
        val client = createClient(invocation.backend)
        var sessionId: String? = null
        var primaryFailure: Throwable? = null
        try {
            updateStage(STAGE_SESSION_START)
            val activeSessionId = startSession(client, invocation)
            sessionId = activeSessionId
            updateStage(STAGE_BIND_CONFLICT)
            var rejected = false
            try {
                val desiredRevision = ensureVisitor(client, activeSessionId, invocation, bindPort)
                client.waitVisitorReady(
                    activeSessionId,
                    VISITOR_NAME,
                    desiredRevision,
                    VISITOR_READY_TIMEOUT_MILLIS,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Error) {
                throw failure
            } catch (failure: AndroidInteropAssertionException) {
                throw failure
            } catch (failure: Exception) {
                if (!isExpectedBindConflict(invocation.backend, failure)) {
                    throw AndroidInteropAssertionException()
                }
                rejected = true
            }
            if (!rejected) throw AndroidInteropAssertionException()
            progress.add(CHECK_BIND_REJECTED)

            updateStage(STAGE_PUBLIC_STATE)
            val failed = awaitPublicState(client, activeSessionId, PUBLIC_STATE_TIMEOUT_MILLIS) { state ->
                state.visitors[VISITOR_NAME]?.phase == "failed"
            }
            progress.add(CHECK_VISITOR_FAILED)
            if (failed.visitors[VISITOR_NAME]?.listenerBound != false) {
                throw AndroidInteropAssertionException()
            }
            progress.add(CHECK_LISTENER_NOT_BOUND)
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = cleanupClient(
            client = client,
            sessionId = sessionId,
            bindPort = bindPort,
            progress = progress,
            confirmPortRelease = false,
            updateStage = { cleanupStage -> if (primaryFailure == null) updateStage(cleanupStage) },
        )
        val occupiedCloseFailure = try {
            occupied.close()
            null
        } catch (failure: Throwable) {
            failure
        }
        val releaseFailure = try {
            updateStage(STAGE_PORT_RELEASE)
            awaitPortReleased(bindPort)
            progress.add(CHECK_PORT_RELEASED)
            null
        } catch (failure: Throwable) {
            failure
        }
        throwSelected(primaryFailure, cleanupFailure, occupiedCloseFailure, releaseFailure)
        progress.complete(OUTCOME_BIND_CONFLICT)
    }

    private suspend fun runRestartScenario(
        context: Context,
        invocation: AndroidInteropInvocation,
        progress: ScenarioProgress,
        updateStage: (String) -> Unit,
    ) {
        val markers = invocation.restartMarkers ?: throw AndroidInteropAssertionException()
        val bindPort = resolveBindPort(invocation.bindPort).also { progress.bindPort = it }
        val client = createClient(invocation.backend)
        var sessionId: String? = null
        var initialGlobal: AndroidLiveSseSession? = null
        var initialProject: AndroidLiveSseSession? = null
        var recoveredGlobal: AndroidLiveSseSession? = null
        var recoveredProject: AndroidLiveSseSession? = null
        var primaryFailure: Throwable? = null
        try {
            updateStage(STAGE_SESSION_START)
            val activeSessionId = startSession(client, invocation)
            sessionId = activeSessionId
            updateStage(STAGE_VISITOR_SETUP)
            val desiredRevision = ensureVisitor(client, activeSessionId, invocation, bindPort)
            updateStage(STAGE_VISITOR_READY)
            val initialEpoch = waitReadyAndVerify(client, activeSessionId, desiredRevision)
            progress.add(CHECK_INITIAL_OPEN_STATE)

            updateStage(STAGE_TUNNEL_HEALTH)
            AndroidTunnelProbe.health(bindPort)
            progress.add(CHECK_INITIAL_HEALTH)

            updateStage(STAGE_GLOBAL_SSE)
            val activeInitialGlobal = AndroidTunnelProbe.openSseSession(
                bindPort,
                "/global/event",
                GLOBAL_READY_DATA,
            )
            initialGlobal = activeInitialGlobal
            progress.add(CHECK_INITIAL_GLOBAL_SSE)

            updateStage(STAGE_PROJECT_SSE)
            val activeInitialProject = AndroidTunnelProbe.openSseSession(bindPort, "/event", PROJECT_READY_DATA)
            initialProject = activeInitialProject
            progress.add(CHECK_INITIAL_PROJECT_SSE)

            updateStage(STAGE_RESTART_DEVICE_READY_MARKER)
            writeRestartMarker(
                context,
                markers.deviceReadyFile,
                restartMarker(invocation, MARKER_DEVICE_READY),
            )
            updateStage(STAGE_RESTART_STOP_COMMAND)
            awaitRestartMarker(
                context,
                markers.frpsStoppedFile,
                restartMarker(invocation, MARKER_FRPS_STOPPED),
            )

            updateStage(STAGE_RESTART_DISCONNECT)
            coroutineScope {
                listOf(
                    async(Dispatchers.IO) {
                        activeInitialGlobal.awaitDisconnected(SSE_DISCONNECT_TIMEOUT_MILLIS)
                    },
                    async(Dispatchers.IO) {
                        activeInitialProject.awaitDisconnected(SSE_DISCONNECT_TIMEOUT_MILLIS)
                    },
                    async {
                        awaitControlUnavailable(client, activeSessionId, CONTROL_UNAVAILABLE_TIMEOUT_MILLIS)
                    },
                ).awaitAll()
            }
            progress.add(CHECK_SSE_DISCONNECTED)
            progress.add(CHECK_CONTROL_UNAVAILABLE)
            throwSelected(closeSseSessions(initialGlobal, initialProject))
            initialGlobal = null
            initialProject = null

            updateStage(STAGE_RESTART_DISCONNECTED_MARKER)
            writeRestartMarker(
                context,
                markers.deviceDisconnectedFile,
                restartMarker(invocation, MARKER_DEVICE_DISCONNECTED),
            )
            updateStage(STAGE_RESTART_COMMAND)
            awaitRestartMarker(
                context,
                markers.frpsRestartedFile,
                restartMarker(invocation, MARKER_FRPS_RESTARTED),
            )

            updateStage(STAGE_RESTART_RECOVERY)
            val recoveredEpoch = waitReadyAndVerify(
                client = client,
                sessionId = activeSessionId,
                desiredRevision = desiredRevision,
                timeoutMillis = RESTART_READY_TIMEOUT_MILLIS,
            )
            if (recoveredEpoch <= initialEpoch) throw AndroidInteropAssertionException()
            progress.add(CHECK_CONTROL_EPOCH_ADVANCED)
            progress.add(CHECK_RECOVERED_OPEN_STATE)

            updateStage(STAGE_TUNNEL_HEALTH)
            AndroidTunnelProbe.health(bindPort)
            progress.add(CHECK_HEALTH)

            updateStage(STAGE_GLOBAL_SSE)
            val activeRecoveredGlobal = AndroidTunnelProbe.openSseSession(
                bindPort,
                "/global/event",
                GLOBAL_READY_DATA,
            )
            recoveredGlobal = activeRecoveredGlobal
            progress.add(CHECK_GLOBAL_SSE)

            updateStage(STAGE_PROJECT_SSE)
            val activeRecoveredProject = AndroidTunnelProbe.openSseSession(bindPort, "/event", PROJECT_READY_DATA)
            recoveredProject = activeRecoveredProject
            progress.add(CHECK_PROJECT_SSE)

            updateStage(STAGE_CONCURRENT_TRAFFIC)
            AndroidTunnelProbe.concurrentFlowControlTraffic(
                bindPort,
                activeRecoveredGlobal,
                activeRecoveredProject,
            )
            progress.add(CHECK_CONCURRENT_REST_SSE)
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val sseCleanupFailure = closeSseSessions(
            initialGlobal,
            initialProject,
            recoveredGlobal,
            recoveredProject,
        )
        val cleanupFailure = cleanupClient(
            client = client,
            sessionId = sessionId,
            bindPort = bindPort,
            progress = progress,
            updateStage = { cleanupStage ->
                if (primaryFailure == null && sseCleanupFailure == null) updateStage(cleanupStage)
            },
        )
        throwSelected(primaryFailure, sseCleanupFailure, cleanupFailure)
        progress.complete(OUTCOME_RECOVERED)
    }

    private suspend fun startSession(
        client: FrpcStcpVisitorClient,
        invocation: AndroidInteropInvocation,
    ): String = client.startSession(
        FrpcSessionConfig(
            serverAddr = IPV4_LOOPBACK_ADDRESS,
            serverPort = invocation.serverPort,
            authToken = invocation.authToken,
            user = VISITOR_USER,
            transportProtocol = "tcp",
            wireProtocol = invocation.wireProtocol,
        ),
    ).takeIf(String::isNotBlank) ?: throw AndroidInteropAssertionException()

    private suspend fun ensureVisitor(
        client: FrpcStcpVisitorClient,
        sessionId: String,
        invocation: AndroidInteropInvocation,
        bindPort: Int,
    ): Long {
        val ensured = client.ensureVisitor(
            sessionId,
            FrpcStcpVisitorConfig(
                name = VISITOR_NAME,
                serverName = PROVIDER_PROXY_NAME,
                serverUser = PROVIDER_USER,
                secretKey = invocation.stcpSecret,
                bindAddr = IPV4_LOOPBACK_ADDRESS,
                bindPort = bindPort,
                useEncryption = invocation.useEncryption,
                useCompression = invocation.useCompression,
            ),
        )
        if (ensured.bindPort != bindPort || ensured.desiredRevision <= 0L) {
            throw AndroidInteropAssertionException()
        }
        return ensured.desiredRevision
    }

    private suspend fun waitReadyAndVerify(
        client: FrpcStcpVisitorClient,
        sessionId: String,
        desiredRevision: Long,
        timeoutMillis: Long = VISITOR_READY_TIMEOUT_MILLIS,
    ): Long {
        val ready = client.waitVisitorReady(
            sessionId,
            VISITOR_NAME,
            desiredRevision,
            timeoutMillis,
        )
        val state = client.getState(sessionId)
        val visitor = state.visitors[VISITOR_NAME]
        if (ready.name != VISITOR_NAME || ready.desiredRevision != desiredRevision || ready.phase != "ready" ||
            !ready.listenerBound || ready.boundControlEpoch <= 0L || state.sessionId != sessionId ||
            state.phase != "open" || state.configRevision < desiredRevision ||
            state.controlEpoch != ready.boundControlEpoch || visitor == null || visitor.phase != "ready" ||
            !visitor.listenerBound || visitor.desiredRevision != desiredRevision ||
            visitor.boundControlEpoch != ready.boundControlEpoch
        ) {
            throw AndroidInteropAssertionException()
        }
        return ready.boundControlEpoch
    }

    private suspend fun awaitPublicState(
        client: FrpcStcpVisitorClient,
        sessionId: String,
        timeoutMillis: Long,
        predicate: (FrpcStcpVisitorState) -> Boolean,
    ): FrpcStcpVisitorState {
        val deadline = AndroidInteropDeadline.afterMillis(timeoutMillis)
        while (!deadline.isExpired()) {
            val state = client.getState(sessionId)
            if (predicate(state)) return state
            delay(minOf(STATE_POLL_MILLIS, deadline.remainingMillis()).coerceAtLeast(1L))
        }
        throw AndroidInteropAssertionException()
    }

    private suspend fun awaitControlUnavailable(
        client: FrpcStcpVisitorClient,
        sessionId: String,
        timeoutMillis: Long,
    ) {
        awaitPublicState(client, sessionId, timeoutMillis) { state ->
            state.phase != "open" || state.visitors[VISITOR_NAME]?.listenerBound != true
        }
    }

    private suspend fun cleanupClient(
        client: FrpcStcpVisitorClient,
        sessionId: String?,
        bindPort: Int,
        progress: ScenarioProgress,
        ignoreStopVisitorFailure: Boolean = false,
        confirmPortRelease: Boolean = true,
        updateStage: (String) -> Unit,
    ): Throwable? = withContext(NonCancellable) {
        var selected: Throwable? = null
        if (sessionId != null) {
            updateStage(STAGE_STOP_VISITOR)
            try {
                withTimeout(CLEANUP_TIMEOUT_MILLIS) { client.stopVisitor(sessionId, VISITOR_NAME) }
            } catch (failure: Throwable) {
                val normalized = normalizeCleanupFailure(failure)
                if (!ignoreStopVisitorFailure || normalized is CancellationException || normalized is Error) {
                    selected = preferFailure(selected, normalized)
                }
            }
            if (selected == null) updateStage(STAGE_STOP_SESSION)
            try {
                val stopped = withTimeout(CLEANUP_TIMEOUT_MILLIS) {
                    client.stopSession(sessionId, STOP_SESSION_TIMEOUT_MILLIS)
                }
                if (stopped.phase != "closed" || client.getState(sessionId).phase != "closed") {
                    throw AndroidInteropAssertionException()
                }
                progress.add(CHECK_CLOSED)
            } catch (failure: Throwable) {
                val normalized = normalizeCleanupFailure(failure)
                selected = preferFailure(
                    selected,
                    diagnoseStopTimeout(client, sessionId, normalized),
                )
            }
        }
        if (client is AutoCloseable) {
            if (selected == null) updateStage(STAGE_CLIENT_CLOSE)
            try {
                client.close()
            } catch (failure: Throwable) {
                selected = preferFailure(selected, failure)
            }
        }
        if (confirmPortRelease) {
            if (selected == null) updateStage(STAGE_PORT_RELEASE)
            try {
                awaitPortReleased(bindPort)
                progress.add(CHECK_PORT_RELEASED)
            } catch (failure: Throwable) {
                selected = preferFailure(selected, failure)
            }
        }
        selected
    }

    private fun closeSseSessions(vararg sessions: AndroidLiveSseSession?): Throwable? {
        var selected: Throwable? = null
        sessions.filterNotNull().distinct().forEach { session ->
            try {
                session.close()
            } catch (failure: Throwable) {
                selected = preferFailure(selected, failure)
            }
        }
        return selected
    }

    private fun throwSelected(vararg failures: Throwable?) {
        var selected: Throwable? = null
        failures.filterNotNull().forEach { failure -> selected = preferFailure(selected, failure) }
        selected?.let { throw it }
    }

    private fun normalizeCleanupFailure(failure: Throwable): Throwable =
        if (failure is TimeoutCancellationException) AndroidInteropTimeoutException() else failure

    private suspend fun diagnoseStopTimeout(
        client: FrpcStcpVisitorClient,
        sessionId: String,
        failure: Throwable,
    ): Throwable {
        if (client !is KotlinFrpcStcpVisitorClient ||
            failure !is KotlinFrpcStcpVisitorException ||
            failure.failure != KotlinFrpcStcpVisitorFailure.STOP_TIMEOUT
        ) {
            return failure
        }
        val diagnostics = client.sessionCleanupDiagnostics(sessionId)
            ?: return AndroidInteropCleanupDiagnosticsException("CLIENT_STOP_TIMEOUT_SESSION_MISSING")
        return AndroidInteropCleanupDiagnosticsException(cleanupFailureCode(diagnostics))
    }

    private fun cleanupFailureCode(diagnostics: KotlinFrpcSessionCleanupDiagnostics): String =
        "CLIENT_STOP_TIMEOUT_${diagnostics.phase.name}" +
            "_J${diagnostics.activeOwnedJobs}" +
            "_R${diagnostics.activeRelays}" +
            "_N${diagnostics.activeListeners}" +
            "_O${diagnostics.openingRelays}" +
            "_P${diagnostics.relayingRelays}" +
            "_L${diagnostics.resettingLeaseRelays}" +
            "_D${diagnostics.resettingAdapterRelays}" +
            "_A${if (diagnostics.actorCompleted) 1 else 0}" +
            "_C${if (diagnostics.controlCollectorCompleted) 1 else 0}"

    private fun preferFailure(current: Throwable?, candidate: Throwable): Throwable = when {
        current == null -> candidate
        current is Error -> current
        candidate is Error -> candidate
        current is CancellationException -> current
        candidate is CancellationException -> candidate
        else -> current
    }

    private suspend fun awaitPortReleased(port: Int) {
        val deadline = AndroidInteropDeadline.afterMillis(PORT_RELEASE_TIMEOUT_MILLIS)
        while (!deadline.isExpired()) {
            val released = try {
                ServerSocket().use { socket ->
                    // Active listeners still prevent the bind, while reuse avoids TIME_WAIT false positives.
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(IPV4_LOOPBACK, port))
                }
                true
            } catch (_: BindException) {
                false
            } catch (_: IOException) {
                false
            }
            if (released) return
            delay(minOf(PORT_RELEASE_POLL_MILLIS, deadline.remainingMillis()).coerceAtLeast(1L))
        }
        throw AndroidInteropAssertionException()
    }

    private fun resolveBindPort(configured: Int): Int =
        if (configured == 0) allocateLoopbackPort() else configured

    private fun allocateLoopbackPort(): Int = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(IPV4_LOOPBACK, 0))
            socket.localPort.takeIf { it in 1..65_535 } ?: throw AndroidInteropAssertionException()
        }
    } catch (failure: AndroidInteropAssertionException) {
        throw failure
    } catch (_: Exception) {
        throw AndroidInteropAssertionException()
    }

    private fun occupyLoopbackPort(configured: Int): ServerSocket = try {
        ServerSocket().apply {
            reuseAddress = false
            bind(InetSocketAddress(IPV4_LOOPBACK, configured))
            if (localPort !in 1..65_535) {
                close()
                throw AndroidInteropAssertionException()
            }
        }
    } catch (failure: AndroidInteropAssertionException) {
        throw failure
    } catch (_: Exception) {
        throw AndroidInteropAssertionException()
    }

    private fun createClient(backend: String): FrpcStcpVisitorClient = when (backend) {
        BACKEND_GO_MOBILE -> GoMobileFrpcStcpVisitorClient(JSON)
        BACKEND_KOTLIN -> KotlinFrpcStcpVisitorClient()
        else -> throw AndroidInteropAssertionException()
    }

    private fun readInvocation(file: File): AndroidInteropInvocation {
        val bytes = readBoundedFile(file, MAXIMUM_CONFIG_BYTES)
        return try {
            JSON.decodeFromString(String(bytes, Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeResult(file: File, result: AndroidInteropResult) {
        val temporary = privateSibling(file, temporaryFileName(file.name))
        requireNewRegularFile(file)
        requireNewRegularFile(temporary)
        val bytes = JSON.encodeToString(result).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAXIMUM_RESULT_BYTES) throw IOException()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException()
        } finally {
            bytes.fill(0)
            temporary.delete()
        }
    }

    private fun writeRestartMarker(
        context: Context,
        fileName: String,
        marker: AndroidInteropRestartMarker,
    ) {
        val file = privateFile(context, fileName)
        val temporary = privateFile(context, temporaryFileName(fileName))
        requireNewRegularFile(file)
        requireNewRegularFile(temporary)
        val bytes = JSON.encodeToString(marker).toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..MAXIMUM_MARKER_BYTES) throw AndroidInteropAssertionException()
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) throw AndroidInteropAssertionException()
        } finally {
            bytes.fill(0)
            temporary.delete()
        }
    }

    private suspend fun awaitRestartMarker(
        context: Context,
        fileName: String,
        expected: AndroidInteropRestartMarker,
    ) {
        val file = privateFile(context, fileName)
        val deadline = AndroidInteropDeadline.afterMillis(MARKER_WAIT_TIMEOUT_MILLIS)
        while (!deadline.isExpired()) {
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val bytes = readBoundedFile(file, MAXIMUM_MARKER_BYTES)
                val actual = try {
                    JSON.decodeFromString<AndroidInteropRestartMarker>(String(bytes, Charsets.UTF_8))
                } catch (_: Exception) {
                    throw AndroidInteropAssertionException()
                } finally {
                    bytes.fill(0)
                }
                if (actual != expected) throw AndroidInteropAssertionException()
                return
            }
            delay(minOf(MARKER_POLL_MILLIS, deadline.remainingMillis()).coerceAtLeast(1L))
        }
        throw AndroidInteropAssertionException()
    }

    private fun readBoundedFile(file: File, maximumBytes: Int): ByteArray {
        val path = file.toPath()
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
            file.length() !in 1..maximumBytes.toLong()
        ) {
            throw IOException()
        }
        FileInputStream(file).use { input ->
            val output = ByteArrayOutputStream(minOf(maximumBytes, 1024))
            val buffer = ByteArray(1024)
            try {
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maximumBytes) throw IOException()
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            } finally {
                buffer.fill(0)
            }
        }
    }

    private fun requireNewRegularFile(file: File) {
        val path = file.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw IOException()
    }

    private fun validateInvocation(invocation: AndroidInteropInvocation, runId: String, backend: String) {
        val expected = EXPECTED_PROFILES[invocation.caseId]
        if (invocation.schemaVersion != SCHEMA_VERSION || invocation.runId != runId ||
            invocation.backend != backend || invocation.serverPort !in 1..65_535 ||
            !SAFE_CREDENTIAL.matches(invocation.authToken) || !SAFE_CREDENTIAL.matches(invocation.stcpSecret) ||
            invocation.bindPort !in 0..65_535 || expected == null ||
            invocation.scenario.kind != expected.scenarioKind || invocation.wireProtocol != expected.wireProtocol ||
            invocation.useEncryption != expected.useEncryption ||
            invocation.useCompression != expected.useCompression
        ) {
            throw AndroidInteropAssertionException()
        }
        val markers = invocation.restartMarkers
        if (invocation.scenario.kind == SCENARIO_RESTART) {
            val names = markers?.fileNames ?: throw AndroidInteropAssertionException()
            if (names.size != 4 || names.distinct().size != names.size ||
                names.any { !SAFE_MARKER_FILE.matches(it) || !SAFE_MARKER_FILE.matches(temporaryFileName(it)) }
            ) {
                throw AndroidInteropAssertionException()
            }
        } else if (markers != null) {
            throw AndroidInteropAssertionException()
        }
    }

    private fun failedResult(
        invocation: AndroidInteropInvocation,
        progress: ScenarioProgress,
        stage: String,
        failure: Throwable,
    ): AndroidInteropResult = AndroidInteropResult(
        schemaVersion = SCHEMA_VERSION,
        runId = invocation.runId,
        backend = invocation.backend,
        caseId = invocation.caseId,
        scenario = invocation.scenario,
        wireProtocol = invocation.wireProtocol,
        useEncryption = invocation.useEncryption,
        useCompression = invocation.useCompression,
        status = STATUS_FAILED,
        failedStage = stage.takeIf(STAGES::contains) ?: STAGE_UNKNOWN,
        failureCode = failureCode(failure),
        semanticOutcome = progress.semanticOutcome,
        bindPort = progress.bindPort,
        checks = progress.checks.toList(),
    )

    private fun failureCode(failure: Throwable): String = when (failure) {
        is GoMobileBridgeUnavailableException -> FAILURE_BRIDGE_UNAVAILABLE
        is GoMobileBridgeApiMismatchException -> FAILURE_BRIDGE_API_MISMATCH
        is KotlinFrpcStcpVisitorException -> "$FAILURE_CLIENT_PREFIX${failure.failure.name}"
        is BindException -> FAILURE_BIND_CONFLICT
        is AndroidTunnelProbeException -> FAILURE_TUNNEL_PROBE
        is AndroidInteropAssertionException -> FAILURE_CONTRACT_ASSERTION
        is AndroidInteropUnexpectedFailureKindException -> failure.code
        is AndroidInteropTimeoutException -> FAILURE_TIMEOUT
        is AndroidInteropCleanupDiagnosticsException -> failure.code
        is SecurityException -> FAILURE_SECURITY
        is IOException -> FAILURE_IO
        else -> FAILURE_UNEXPECTED
    }

    private fun restartMarker(
        invocation: AndroidInteropInvocation,
        step: String,
    ): AndroidInteropRestartMarker = AndroidInteropRestartMarker(
        schemaVersion = MARKER_SCHEMA_VERSION,
        runId = invocation.runId,
        caseId = invocation.caseId,
        backend = invocation.backend,
        step = step,
    )

    private fun invalidInvocation(runId: String, backend: String): AndroidInteropInvocation =
        AndroidInteropInvocation(
            schemaVersion = SCHEMA_VERSION,
            runId = runId,
            backend = backend,
            caseId = "invalid",
            scenario = AndroidInteropScenario("invalid"),
            serverPort = 1,
            authToken = "invalid-invalid-invalid",
            stcpSecret = "invalid-invalid-invalid",
            bindPort = 0,
            wireProtocol = "v1",
            useEncryption = false,
            useCompression = false,
        )

    private fun privateFile(context: Context, name: String): File = File(context.filesDir, name).also { file ->
        if (file.parentFile != context.filesDir) throw AndroidInteropAssertionException()
    }

    private fun privateSibling(file: File, name: String): File = File(file.parentFile, name).also { sibling ->
        if (sibling.parentFile != file.parentFile) throw AndroidInteropAssertionException()
    }

    private fun temporaryFileName(name: String): String {
        val separator = name.lastIndexOf('.')
        if (separator <= 0 || separator == name.lastIndex) throw AndroidInteropAssertionException()
        return name.substring(0, separator) + "-tmp" + name.substring(separator)
    }

    private fun requireSafeRunId(value: String?): String =
        value?.takeIf { SAFE_RUN_ID.matches(it) } ?: throw AndroidInteropAssertionException()

    private fun requireBackend(value: String?): String =
        value?.takeIf { it == BACKEND_GO_MOBILE || it == BACKEND_KOTLIN }
            ?: throw AndroidInteropAssertionException()

    private fun requireSafeFileName(value: String?, suffix: String): String =
        value?.takeIf { SAFE_JSON_FILE.matches(it) && it.endsWith(suffix) && !it.startsWith('.') }
            ?: throw AndroidInteropAssertionException()

    private fun expectedChecks(scenarioKind: String): List<String> = when (scenarioKind) {
        SCENARIO_SUCCESS -> SUCCESS_CHECKS
        SCENARIO_WRONG_TOKEN -> WRONG_TOKEN_CHECKS
        SCENARIO_WRONG_SECRET -> WRONG_SECRET_CHECKS
        SCENARIO_BIND_CONFLICT -> BIND_CONFLICT_CHECKS
        SCENARIO_RESTART -> RESTART_CHECKS
        else -> throw AndroidInteropAssertionException()
    }

    private fun expectedSemanticOutcome(scenarioKind: String): String = when (scenarioKind) {
        SCENARIO_SUCCESS -> OUTCOME_SUCCESS
        SCENARIO_WRONG_TOKEN -> OUTCOME_CONTROL_FAILURE
        SCENARIO_WRONG_SECRET -> OUTCOME_STCP_SECRET_REJECTED
        SCENARIO_BIND_CONFLICT -> OUTCOME_BIND_CONFLICT
        SCENARIO_RESTART -> OUTCOME_RECOVERED
        else -> throw AndroidInteropAssertionException()
    }

    private fun isExpectedControlRejection(backend: String, failure: Exception): Boolean = when (backend) {
        BACKEND_KOTLIN -> failure is KotlinFrpcStcpVisitorException &&
            failure.failure == KotlinFrpcStcpVisitorFailure.CONTROL_FAILED
        // The pinned GoMobile runtime maps Go errors to a checked go.* wrapper.
        // Public failed state below distinguishes control rejection from local failures.
        BACKEND_GO_MOBILE -> failure.javaClass.name.startsWith("go.")
        else -> false
    }

    private fun isExpectedBindConflict(backend: String, failure: Exception): Boolean = when (backend) {
        BACKEND_KOTLIN -> failure is KotlinFrpcStcpVisitorException &&
            failure.failure == KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT
        BACKEND_GO_MOBILE -> failure is BindException
        else -> false
    }

    private fun exceptionCategory(failure: Exception): String = when (failure) {
        is GoMobileBridgeUnavailableException -> "BRIDGE_UNAVAILABLE"
        is GoMobileBridgeApiMismatchException -> "BRIDGE_API_MISMATCH"
        is BindException -> "BIND"
        is SecurityException -> "SECURITY"
        is IOException -> "IO"
        is IllegalStateException -> "ILLEGAL_STATE"
        is IllegalArgumentException -> "ILLEGAL_ARGUMENT"
        is RuntimeException -> "RUNTIME_SUBCLASS"
        else -> when {
            failure.javaClass.name.startsWith("go.") -> "GO_PACKAGE"
            failure.javaClass.name.startsWith("frpcstcpvisitor.") -> "GENERATED_PACKAGE"
            else -> "CHECKED_SUBCLASS"
        }
    }

    private class ScenarioProgress {
        var bindPort: Int = 0
        var semanticOutcome: String? = null
            private set
        val checks = ArrayList<String>()

        fun add(check: String) {
            if (check !in ALL_CHECKS || check in checks) throw AndroidInteropAssertionException()
            checks += check
        }

        fun complete(outcome: String) {
            if (outcome !in ALL_SEMANTIC_OUTCOMES || semanticOutcome != null) {
                throw AndroidInteropAssertionException()
            }
            semanticOutcome = outcome
        }
    }

    private class AndroidInteropDeadline private constructor(
        private val deadlineNanos: Long,
    ) {
        fun isExpired(): Boolean = System.nanoTime() - deadlineNanos >= 0L

        fun remainingMillis(): Long =
            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))

        companion object {
            fun afterMillis(timeoutMillis: Long): AndroidInteropDeadline {
                if (timeoutMillis <= 0L) throw AndroidInteropAssertionException()
                val duration = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                return AndroidInteropDeadline(System.nanoTime() + duration)
            }
        }
    }

    @Serializable
    private data class AndroidInteropScenario(
        val kind: String,
    )

    @Serializable
    private data class AndroidInteropRestartMarkers(
        val deviceReadyFile: String,
        val frpsStoppedFile: String,
        val deviceDisconnectedFile: String,
        val frpsRestartedFile: String,
    ) {
        val fileNames: List<String>
            get() = listOf(deviceReadyFile, frpsStoppedFile, deviceDisconnectedFile, frpsRestartedFile)

        override fun toString(): String = "AndroidInteropRestartMarkers(configured=true)"
    }

    @Serializable
    private data class AndroidInteropInvocation(
        val schemaVersion: Int,
        val runId: String,
        val backend: String,
        val caseId: String,
        val scenario: AndroidInteropScenario,
        val serverPort: Int,
        val authToken: String,
        val stcpSecret: String,
        val bindPort: Int,
        val wireProtocol: String,
        val useEncryption: Boolean,
        val useCompression: Boolean,
        val restartMarkers: AndroidInteropRestartMarkers? = null,
    ) {
        override fun toString(): String =
            "AndroidInteropInvocation(caseId=$caseId, backend=$backend, scenario=${scenario.kind}, " +
                "wireProtocol=$wireProtocol, useEncryption=$useEncryption, " +
                "useCompression=$useCompression, authToken=<redacted>, stcpSecret=<redacted>, " +
                "restartMarkersPresent=${restartMarkers != null})"
    }

    @Serializable
    private data class AndroidInteropResult(
        val schemaVersion: Int,
        val runId: String,
        val backend: String,
        val caseId: String,
        val scenario: AndroidInteropScenario,
        val wireProtocol: String,
        val useEncryption: Boolean,
        val useCompression: Boolean,
        val status: String,
        val failedStage: String? = null,
        val failureCode: String? = null,
        val semanticOutcome: String? = null,
        val bindPort: Int = 0,
        val checks: List<String> = emptyList(),
    ) {
        override fun toString(): String =
            "AndroidInteropResult(caseId=$caseId, backend=$backend, status=$status, " +
                "semanticOutcomePresent=${semanticOutcome != null}, checkCount=${checks.size}, " +
                "failedStagePresent=${failedStage != null}, " +
                "failureCodePresent=${failureCode != null})"
    }

    @Serializable
    private data class AndroidInteropRestartMarker(
        val schemaVersion: Int,
        val runId: String,
        val caseId: String,
        val backend: String,
        val step: String,
    ) {
        override fun toString(): String = "AndroidInteropRestartMarker(step=$step)"
    }

    private data class ExpectedProfile(
        val caseId: String,
        val scenarioKind: String,
        val wireProtocol: String,
        val useEncryption: Boolean,
        val useCompression: Boolean,
    )

    private class AndroidInteropAssertionException : Exception()

    private class AndroidInteropUnexpectedFailureKindException(val code: String) : Exception()

    private class AndroidInteropTimeoutException : Exception()

    private class AndroidInteropCleanupDiagnosticsException(
        val code: String,
    ) : Exception()

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val SAFE_RUN_ID = Regex("[a-f0-9-]{36}")
        val SAFE_JSON_FILE = Regex("[A-Za-z0-9_-]{1,112}\\.json")
        val SAFE_MARKER_FILE = Regex("[A-Za-z0-9_-]{1,112}\\.marker")
        val SAFE_CREDENTIAL = Regex("[A-Za-z0-9_-]{16,512}")

        const val SCHEMA_VERSION = 2
        const val MARKER_SCHEMA_VERSION = 1
        const val BACKEND_GO_MOBILE = "gomobile"
        const val BACKEND_KOTLIN = "kotlin"
        const val STATUS_PASSED = "passed"
        const val STATUS_FAILED = "failed"
        const val ARG_ENABLED = "frpcAbEnabled"
        const val ARG_RUN_ID = "runId"
        const val ARG_BACKEND = "backend"
        const val ARG_CONFIG_FILE = "configFile"
        const val ARG_RESULT_FILE = "resultFile"
        const val IPV4_LOOPBACK_ADDRESS = "127.0.0.1"
        const val PROVIDER_USER = "interop-server"
        const val VISITOR_USER = "interop-visitor"
        const val PROVIDER_PROXY_NAME = "interop-opencode"
        const val VISITOR_NAME = "interop-visitor"
        const val GLOBAL_READY_DATA = "{\"scope\":\"global\"}"
        const val PROJECT_READY_DATA = "{\"scope\":\"project\"}"

        const val SCENARIO_SUCCESS = "success"
        const val SCENARIO_WRONG_TOKEN = "wrong_token"
        const val SCENARIO_WRONG_SECRET = "wrong_secret"
        const val SCENARIO_BIND_CONFLICT = "bind_conflict"
        const val SCENARIO_RESTART = "restart"

        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_CONTROL_FAILURE = "control_failure"
        const val OUTCOME_STCP_SECRET_REJECTED = "stcp_secret_rejected"
        const val OUTCOME_BIND_CONFLICT = "bind_conflict"
        const val OUTCOME_RECOVERED = "recovered"

        const val CHECK_OPEN_STATE = "open_state"
        const val CHECK_HEALTH = "health"
        const val CHECK_GLOBAL_SSE = "global_sse"
        const val CHECK_PROJECT_SSE = "project_sse"
        const val CHECK_CONCURRENT_REST_SSE = "concurrent_rest_sse"
        const val CHECK_CLOSED = "closed"
        const val CHECK_PORT_RELEASED = "port_released"
        const val CHECK_CONTROL_REJECTED = "control_rejected"
        const val CHECK_SESSION_FAILED = "session_failed"
        const val CHECK_LISTENER_NOT_BOUND = "listener_not_bound"
        const val CHECK_HEALTH_REJECTED = "health_rejected"
        const val CHECK_LISTENER_CONSISTENT = "listener_consistent"
        const val CHECK_BIND_REJECTED = "bind_rejected"
        const val CHECK_VISITOR_FAILED = "visitor_failed"
        const val CHECK_INITIAL_OPEN_STATE = "initial_open_state"
        const val CHECK_INITIAL_HEALTH = "initial_health"
        const val CHECK_INITIAL_GLOBAL_SSE = "initial_global_sse"
        const val CHECK_INITIAL_PROJECT_SSE = "initial_project_sse"
        const val CHECK_SSE_DISCONNECTED = "sse_disconnected"
        const val CHECK_CONTROL_UNAVAILABLE = "control_unavailable"
        const val CHECK_CONTROL_EPOCH_ADVANCED = "control_epoch_advanced"
        const val CHECK_RECOVERED_OPEN_STATE = "recovered_open_state"

        val SUCCESS_CHECKS = listOf(
            CHECK_OPEN_STATE,
            CHECK_HEALTH,
            CHECK_GLOBAL_SSE,
            CHECK_PROJECT_SSE,
            CHECK_CONCURRENT_REST_SSE,
            CHECK_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val WRONG_TOKEN_CHECKS = listOf(
            CHECK_CONTROL_REJECTED,
            CHECK_SESSION_FAILED,
            CHECK_LISTENER_NOT_BOUND,
            CHECK_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val WRONG_SECRET_CHECKS = listOf(
            CHECK_OPEN_STATE,
            CHECK_HEALTH_REJECTED,
            CHECK_LISTENER_CONSISTENT,
            CHECK_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val BIND_CONFLICT_CHECKS = listOf(
            CHECK_BIND_REJECTED,
            CHECK_VISITOR_FAILED,
            CHECK_LISTENER_NOT_BOUND,
            CHECK_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val RESTART_CHECKS = listOf(
            CHECK_INITIAL_OPEN_STATE,
            CHECK_INITIAL_HEALTH,
            CHECK_INITIAL_GLOBAL_SSE,
            CHECK_INITIAL_PROJECT_SSE,
            CHECK_SSE_DISCONNECTED,
            CHECK_CONTROL_UNAVAILABLE,
            CHECK_CONTROL_EPOCH_ADVANCED,
            CHECK_RECOVERED_OPEN_STATE,
            CHECK_HEALTH,
            CHECK_GLOBAL_SSE,
            CHECK_PROJECT_SSE,
            CHECK_CONCURRENT_REST_SSE,
            CHECK_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val ALL_CHECKS = (
            SUCCESS_CHECKS + WRONG_TOKEN_CHECKS + WRONG_SECRET_CHECKS + BIND_CONFLICT_CHECKS + RESTART_CHECKS
            ).toSet()
        val ALL_SEMANTIC_OUTCOMES = setOf(
            OUTCOME_SUCCESS,
            OUTCOME_CONTROL_FAILURE,
            OUTCOME_STCP_SECRET_REJECTED,
            OUTCOME_BIND_CONFLICT,
            OUTCOME_RECOVERED,
        )

        const val MARKER_DEVICE_READY = "device_ready"
        const val MARKER_FRPS_STOPPED = "frps_stopped"
        const val MARKER_DEVICE_DISCONNECTED = "device_disconnected"
        const val MARKER_FRPS_RESTARTED = "frps_restarted"

        const val FAILURE_BRIDGE_UNAVAILABLE = "BRIDGE_UNAVAILABLE"
        const val FAILURE_BRIDGE_API_MISMATCH = "BRIDGE_API_MISMATCH"
        const val FAILURE_CLIENT_PREFIX = "CLIENT_"
        const val FAILURE_BIND_CONFLICT = "BIND_CONFLICT"
        const val FAILURE_TUNNEL_PROBE = "TUNNEL_PROBE"
        const val FAILURE_CONTRACT_ASSERTION = "CONTRACT_ASSERTION"
        const val FAILURE_TIMEOUT = "TIMEOUT"
        const val FAILURE_SECURITY = "SECURITY"
        const val FAILURE_IO = "IO"
        const val FAILURE_UNEXPECTED = "UNEXPECTED"

        const val MAXIMUM_CONFIG_BYTES = 16 * 1024
        const val MAXIMUM_RESULT_BYTES = 16 * 1024
        const val MAXIMUM_MARKER_BYTES = 2 * 1024
        const val VISITOR_READY_TIMEOUT_MILLIS = 45_000L
        const val RESTART_READY_TIMEOUT_MILLIS = 90_000L
        const val PUBLIC_STATE_TIMEOUT_MILLIS = 30_000L
        const val CONTROL_UNAVAILABLE_TIMEOUT_MILLIS = 30_000L
        const val SSE_DISCONNECT_TIMEOUT_MILLIS = 30_000L
        const val CLEANUP_TIMEOUT_MILLIS = 15_000L
        const val STOP_SESSION_TIMEOUT_MILLIS = 10_000L
        const val PORT_RELEASE_TIMEOUT_MILLIS = 15_000L
        const val PORT_RELEASE_POLL_MILLIS = 25L
        const val STATE_POLL_MILLIS = 25L
        const val MARKER_WAIT_TIMEOUT_MILLIS = 90_000L
        const val MARKER_POLL_MILLIS = 50L
        const val SCENARIO_TIMEOUT_MILLIS = 300_000L

        const val STAGE_CONFIGURATION = "configuration"
        const val STAGE_SESSION_START = "session_start"
        const val STAGE_VISITOR_SETUP = "visitor_setup"
        const val STAGE_VISITOR_READY = "visitor_ready"
        const val STAGE_TUNNEL_HEALTH = "tunnel_health"
        const val STAGE_GLOBAL_SSE = "global_sse"
        const val STAGE_PROJECT_SSE = "project_sse"
        const val STAGE_CONCURRENT_TRAFFIC = "concurrent_traffic"
        const val STAGE_CONTROL_REJECTION = "control_rejection"
        const val STAGE_PUBLIC_STATE = "public_state"
        const val STAGE_BIND_CONFLICT = "bind_conflict"
        const val STAGE_RESTART_DEVICE_READY_MARKER = "restart_device_ready_marker"
        const val STAGE_RESTART_STOP_COMMAND = "restart_stop_command"
        const val STAGE_RESTART_DISCONNECT = "restart_disconnect"
        const val STAGE_RESTART_DISCONNECTED_MARKER = "restart_disconnected_marker"
        const val STAGE_RESTART_COMMAND = "restart_command"
        const val STAGE_RESTART_RECOVERY = "restart_recovery"
        const val STAGE_STOP_VISITOR = "stop_visitor"
        const val STAGE_STOP_SESSION = "stop_session"
        const val STAGE_CLIENT_CLOSE = "client_close"
        const val STAGE_PORT_RELEASE = "port_release"
        const val STAGE_UNKNOWN = "unknown"
        val STAGES = setOf(
            STAGE_CONFIGURATION,
            STAGE_SESSION_START,
            STAGE_VISITOR_SETUP,
            STAGE_VISITOR_READY,
            STAGE_TUNNEL_HEALTH,
            STAGE_GLOBAL_SSE,
            STAGE_PROJECT_SSE,
            STAGE_CONCURRENT_TRAFFIC,
            STAGE_CONTROL_REJECTION,
            STAGE_PUBLIC_STATE,
            STAGE_BIND_CONFLICT,
            STAGE_RESTART_DEVICE_READY_MARKER,
            STAGE_RESTART_STOP_COMMAND,
            STAGE_RESTART_DISCONNECT,
            STAGE_RESTART_DISCONNECTED_MARKER,
            STAGE_RESTART_COMMAND,
            STAGE_RESTART_RECOVERY,
            STAGE_STOP_VISITOR,
            STAGE_STOP_SESSION,
            STAGE_CLIENT_CLOSE,
            STAGE_PORT_RELEASE,
            STAGE_UNKNOWN,
        )

        val EXPECTED_PROFILES = listOf(
            ExpectedProfile("success-v1-00", SCENARIO_SUCCESS, "v1", false, false),
            ExpectedProfile("success-v1-01", SCENARIO_SUCCESS, "v1", false, true),
            ExpectedProfile("success-v1-10", SCENARIO_SUCCESS, "v1", true, false),
            ExpectedProfile("success-v1-11", SCENARIO_SUCCESS, "v1", true, true),
            ExpectedProfile("success-v2-00", SCENARIO_SUCCESS, "v2", false, false),
            ExpectedProfile("success-v2-01", SCENARIO_SUCCESS, "v2", false, true),
            ExpectedProfile("success-v2-10", SCENARIO_SUCCESS, "v2", true, false),
            ExpectedProfile("success-v2-11", SCENARIO_SUCCESS, "v2", true, true),
            ExpectedProfile("negative-wrong-token-v2-00", SCENARIO_WRONG_TOKEN, "v2", false, false),
            ExpectedProfile("negative-wrong-secret-v2-00", SCENARIO_WRONG_SECRET, "v2", false, false),
            ExpectedProfile("negative-bind-conflict-v2-00", SCENARIO_BIND_CONFLICT, "v2", false, false),
            ExpectedProfile("restart-v2-11", SCENARIO_RESTART, "v2", true, true),
        ).associateBy(ExpectedProfile::caseId)
    }
}
