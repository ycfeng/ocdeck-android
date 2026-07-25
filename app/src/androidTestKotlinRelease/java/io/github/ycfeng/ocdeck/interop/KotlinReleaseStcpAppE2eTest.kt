package io.github.ycfeng.ocdeck.interop

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ycfeng.ocdeck.BuildConfig
import io.github.ycfeng.ocdeck.OpenCodeApplication
import io.github.ycfeng.ocdeck.core.notification.OpenCodeNotificationTarget
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.core.store.SessionListWindowState
import io.github.ycfeng.ocdeck.core.store.SseConnectionStatus
import io.github.ycfeng.ocdeck.data.server.FrpcTransportProtocol
import io.github.ycfeng.ocdeck.data.server.FrpcWireProtocol
import io.github.ycfeng.ocdeck.data.server.NewServerFrpcStcpVisitor
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
class KotlinReleaseStcpAppE2eTest {
    @Test
    fun runConfiguredAppFlow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARG_ENABLED) == "true")

        val runId = requireSafeRunId(arguments.getString(ARG_RUN_ID))
        val configFile = requireSafeFileName(arguments.getString(ARG_CONFIG_FILE))
        var stage = STAGE_CONFIGURATION
        val checks = linkedSetOf<String>()

        val result = try {
            val configured = readPrivateJson<AppE2eInvocation>(instrumentation, configFile).also {
                validateInvocation(it, runId)
            }
            runBlocking {
                withTimeout(SCENARIO_TIMEOUT_MILLIS) {
                    runScenario(configured, checks) { stage = it }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            failedResult(runId, stage, checks, FAILURE_TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Error) {
            throw failure
        } catch (_: AppE2eAssertionException) {
            failedResult(runId, stage, checks, FAILURE_ASSERTION)
        } catch (_: Exception) {
            failedResult(runId, stage, checks, FAILURE_UNEXPECTED)
        }

        try {
            val encoded = JSON.encodeToString(result)
            if (encoded.toByteArray(Charsets.UTF_8).size > MAXIMUM_JSON_BYTES) throw AppE2eAssertionException()
            instrumentation.sendStatus(0, Bundle().apply { putString(STATUS_RESULT_KEY, encoded) })
        } catch (_: Exception) {
            fail("STCP App E2E result publication failed")
        }
        if (result.status != STATUS_PASSED) {
            fail("STCP App E2E failed at ${result.failedStage} with ${result.failureCode}")
        }
    }

    private suspend fun runScenario(
        invocation: AppE2eInvocation,
        checks: MutableSet<String>,
        updateStage: (String) -> Unit,
    ): AppE2eResult {
        if (invocation.suite == SUITE_PROCESS) {
            return runProcessScenario(invocation, checks, updateStage)
        }
        if (!BuildConfig.USE_KOTLIN_FRPC_STCP_VISITOR) throw AppE2eAssertionException()
        checks += CHECK_KOTLIN_BACKEND

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as? OpenCodeApplication
            ?: throw AppE2eAssertionException()
        val container = application.container
        val bindPort = reserveLoopbackPort()
        var server: ServerConfig? = null
        var globalLease: io.github.ycfeng.ocdeck.core.network.GlobalConnectionLease? = null
        var projectLease: io.github.ycfeng.ocdeck.core.network.ProjectConnectionLease? = null
        var primaryFailure: Throwable? = null
        var initialGeneration = 0L
        var initialControlEpoch = 0L
        var recoveredGeneration = 0L
        var recoveredControlEpoch = 0L
        var deviceIdleForced = false

        try {
            updateStage(STAGE_SERVER_CONFIGURATION)
            server = container.serverRepository.addServer(
                baseUrl = "http://127.0.0.1:$bindPort/",
                name = "STCP App E2E",
                username = null,
                password = null,
                frpcStcpVisitor = NewServerFrpcStcpVisitor(
                    enabled = true,
                    serverAddr = "127.0.0.1",
                    serverPort = invocation.frpsPort,
                    authToken = invocation.authToken,
                    user = invocation.user,
                    serverUser = invocation.serverUser,
                    serverName = invocation.serverName,
                    secretKey = invocation.secretKey,
                    bindPort = bindPort,
                    transportProtocol = FrpcTransportProtocol.Tcp,
                    wireProtocol = FrpcWireProtocol.V2,
                ),
            )

            updateStage(STAGE_STCP_READY)
            val connection = container.serverRepository.getConnection(server.id)
            val generation = connection.stcpGeneration ?: throw AppE2eAssertionException()
            initialGeneration = generation.ordinal
            initialControlEpoch = connection.stcpControlEpoch ?: throw AppE2eAssertionException()
            if (connection.readinessHealth?.healthy != true || initialGeneration <= 0L || initialControlEpoch <= 0L) {
                throw AppE2eAssertionException()
            }
            checks += CHECK_STCP_READY

            updateStage(STAGE_SNAPSHOT)
            val loaded = container.openCodeRepository.loadProjectSnapshot(
                serverId = server.id,
                directory = PROJECT_DIRECTORY,
                workspace = null,
                sessionWindow = SessionListWindowState(),
                activeSessionMessages = null,
            ).getOrThrow()
            if (loaded.transportIdentity != connection.transportIdentity) throw AppE2eAssertionException()
            container.openCodeStore.applyProjectSnapshot(loaded.snapshot)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1" &&
                    state.project?.projectId == "project-e2e"
            }
            checks += CHECK_SNAPSHOT_STORE

            updateStage(STAGE_GLOBAL_SSE)
            globalLease = container.openCodeEventClient.connectGlobal(server.id)
            withTimeout(STATE_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1"
            }
            checks += CHECK_GLOBAL_SSE_STORE

            updateStage(STAGE_PROJECT_SSE)
            projectLease = container.openCodeEventClient.connectProject(server.id, PROJECT_DIRECTORY)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"
            }
            checks += CHECK_PROJECT_SSE_STORE

            if (invocation.suite == SUITE_CORE) {
                updateStage(STAGE_FOREGROUND_RECONCILIATION)
                val revisionBeforeForeground = container.openCodeStore.captureProjectDataRevision(
                    server.id,
                    PROJECT_DIRECTORY,
                )
                container.appConnectionCoordinator.onForeground()
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.connectionStatus == SseConnectionStatus.Open &&
                        state.projectDataRevision > revisionBeforeForeground &&
                        state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1"
                }
                checks += CHECK_FOREGROUND_RECONCILIATION
            } else {
                updateStage(STAGE_ACTIVITY_FOREGROUND)
                val revisionBeforeActivity = container.openCodeStore.captureProjectDataRevision(
                    server.id,
                    PROJECT_DIRECTORY,
                )
                launchMainActivity(instrumentation, server.id)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == SESSION_SNAPSHOT &&
                        state.connectionStatus == SseConnectionStatus.Open &&
                        state.projectDataRevision > revisionBeforeActivity
                }
                checks += CHECK_ACTIVITY_FOREGROUND

                updateStage(STAGE_ACTIVITY_CYCLES)
                val revisionBeforeCycles = container.openCodeStore.captureProjectDataRevision(
                    server.id,
                    PROJECT_DIRECTORY,
                )
                repeat(LIFECYCLE_CYCLE_COUNT) { cycle ->
                    updateStage("activity_cycle_${cycle + 1}_background")
                    pressHome(instrumentation)
                    awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                        state.activeSessionId == null && state.hasGenerationOneData()
                    }
                    updateStage("activity_cycle_${cycle + 1}_foreground")
                    launchMainActivity(instrumentation, server.id)
                    awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                        state.activeSessionId == SESSION_SNAPSHOT &&
                            state.connectionStatus == SseConnectionStatus.Open &&
                            state.hasGenerationOneData()
                    }
                }
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.projectDataRevision > revisionBeforeCycles && state.hasGenerationOneData()
                }
                checks += CHECK_ACTIVITY_BACKGROUND_DATA_RETAINED
                checks += CHECK_ACTIVITY_FOREGROUND_RECONCILIATION

                updateStage(STAGE_DOZE_ENTER)
                pressHome(instrumentation)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == null && state.hasGenerationOneData()
                }
                deviceIdleForced = true
                forceDeviceIdle(instrumentation)
                checks += CHECK_DOZE_ENTERED
                delay(DOZE_HOLD_MILLIS)

                updateStage(STAGE_DOZE_EXIT)
                restoreDeviceIdle(instrumentation)
                deviceIdleForced = false
                launchMainActivity(instrumentation, server.id)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == SESSION_SNAPSHOT &&
                        state.connectionStatus == SseConnectionStatus.Open &&
                        state.hasGenerationOneData()
                }
                checks += CHECK_DOZE_EXIT_RECOVERY

                pressHome(instrumentation)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == null && state.hasGenerationOneData()
                }
            }

            updateStage(STAGE_RESTART_DISCONNECT)
            writePrivateMarker(
                instrumentation,
                invocation.restartMarkers.deviceReadyFile,
            )
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus != SseConnectionStatus.Open &&
                    state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1" &&
                    state.sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1" &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"
            }
            withTimeout(STATE_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first {
                    it != SseConnectionStatus.Open
                }
            }
            checks += CHECK_DISCONNECT_DATA_RETAINED
            writePrivateMarker(
                instrumentation,
                invocation.restartMarkers.deviceDisconnectedFile,
            )

            updateStage(STAGE_RESTART_RECOVERY)
            if (invocation.suite == SUITE_LIFECYCLE) {
                awaitPrivateMarker(
                    instrumentation,
                    invocation.restartMarkers.frpsOnlyFile,
                    MARKER_FRPS_ONLY,
                )
                launchMainActivity(instrumentation, server.id)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == SESSION_SNAPSHOT && state.hasGenerationOneData()
                }
                delay(PROVIDER_DOWN_ASSERTION_MILLIS)
                val providerWindowProject = withTimeout(STATE_TIMEOUT_MILLIS) {
                    container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY).first()
                }
                val providerWindowGlobal = withTimeout(STATE_TIMEOUT_MILLIS) {
                    container.openCodeStore.observeGlobalConnection(server.id).first()
                }
                if (providerWindowProject.connectionStatus == SseConnectionStatus.Open ||
                    providerWindowGlobal == SseConnectionStatus.Open ||
                    !providerWindowProject.hasGenerationOneData()
                ) {
                    throw AppE2eAssertionException()
                }
                checks += CHECK_PROVIDER_RACE_DATA_RETAINED
                writePrivateMarker(
                    instrumentation,
                    invocation.restartMarkers.providerWindowObservedFile,
                )
            }
            updateStage(STAGE_RESTART_RECOVERY_MARKER)
            awaitPrivateMarker(
                instrumentation,
                invocation.restartMarkers.frpsRestartedFile,
                MARKER_FRPS_RESTARTED,
            )
            updateStage(STAGE_RESTART_RECOVERY_GLOBAL)
            withTimeout(RECOVERY_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            updateStage(STAGE_RESTART_RECOVERY_PROJECT_OPEN)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open
            }
            updateStage(STAGE_RESTART_RECOVERY_PROJECT_SSE)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-2"
            }
            updateStage(STAGE_RESTART_RECOVERY_SNAPSHOT)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-2"
            }
            updateStage(STAGE_RESTART_RECOVERY_GLOBAL_DATA)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_GLOBAL_SSE) in setOf("global-sse-1", "global-sse-2")
            }
            updateStage(STAGE_RESTART_RECOVERY_IDENTITY)
            val recoveredConnection = container.serverRepository.getConnection(server.id)
            recoveredGeneration = recoveredConnection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
            recoveredControlEpoch = recoveredConnection.stcpControlEpoch ?: throw AppE2eAssertionException()
            updateStage(STAGE_RESTART_RECOVERY_GENERATION)
            if (recoveredGeneration != initialGeneration) throw AppE2eAssertionException()
            updateStage(STAGE_RESTART_RECOVERY_EPOCH)
            if (recoveredControlEpoch <= initialControlEpoch) throw AppE2eAssertionException()
            updateStage(STAGE_RESTART_RECOVERY_TRANSPORT)
            if (recoveredConnection.transportIdentity.isOlderThan(connection.transportIdentity)) {
                throw AppE2eAssertionException()
            }
            if (invocation.suite == SUITE_LIFECYCLE) checks += CHECK_LONG_OUTAGE_DATA_RETAINED
            checks += CHECK_CONTROL_EPOCH_ADVANCED
            checks += CHECK_RESTART_RECONCILIATION

            updateStage(STAGE_POST_RESTART_FOREGROUND)
            val revisionBeforeRecoveryForeground = container.openCodeStore.captureProjectDataRevision(
                server.id,
                PROJECT_DIRECTORY,
            )
            if (invocation.suite == SUITE_CORE) {
                container.appConnectionCoordinator.onForeground()
            } else {
                pressHome(instrumentation)
                awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                    state.activeSessionId == null && state.hasGenerationTwoData()
                }
                launchMainActivity(instrumentation, server.id)
            }
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.projectDataRevision > revisionBeforeRecoveryForeground &&
                    state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-2" &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-2" &&
                    (invocation.suite == SUITE_CORE || state.activeSessionId == SESSION_SNAPSHOT)
            }
            checks += CHECK_POST_RESTART_FOREGROUND
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = withContext(NonCancellable) {
            try {
                if (primaryFailure == null) updateStage(STAGE_CLEANUP)
                if (deviceIdleForced) {
                    restoreDeviceIdle(instrumentation)
                    deviceIdleForced = false
                }
                projectLease?.release()
                globalLease?.release()
                server?.let { configured ->
                    container.openCodeEventClient.closeServer(configured.id)
                    awaitProject(container.openCodeStore.observeProject(configured.id, PROJECT_DIRECTORY)) { state ->
                        state.connectionStatus == SseConnectionStatus.Closed
                    }
                    withTimeout(STATE_TIMEOUT_MILLIS) {
                        container.openCodeStore.observeGlobalConnection(configured.id).first {
                            it == SseConnectionStatus.Closed
                        }
                    }
                    checks += CHECK_STREAMS_CLOSED
                    container.serverRepository.deleteServer(configured)
                    awaitPortReleased(bindPort)
                    checks += CHECK_VISITOR_CLOSED
                    checks += CHECK_PORT_RELEASED
                }
                null
            } catch (failure: Throwable) {
                failure
            }
        }
        throwSelected(primaryFailure, cleanupFailure)

        val expectedChecks = expectedChecks(invocation.suite)
        if (checks != expectedChecks) throw AppE2eAssertionException()
        return AppE2eResult(
            schemaVersion = SCHEMA_VERSION,
            runId = invocation.runId,
            status = STATUS_PASSED,
            checks = checks.toList(),
            bindPort = bindPort,
            initialGeneration = initialGeneration,
            initialControlEpoch = initialControlEpoch,
            recoveredGeneration = recoveredGeneration,
            recoveredControlEpoch = recoveredControlEpoch,
            suite = invocation.suite,
            topology = TOPOLOGY_ADB_REVERSE,
            networkSwitchProven = false,
            phase = invocation.phase,
            processId = Process.myPid(),
        )
    }

    private suspend fun runProcessScenario(
        invocation: AppE2eInvocation,
        checks: MutableSet<String>,
        updateStage: (String) -> Unit,
    ): AppE2eResult = when (invocation.phase) {
        PHASE_PROCESS_PREPARE -> runProcessPrepare(invocation, checks, updateStage)
        PHASE_PROCESS_RECOVER -> runProcessRecover(invocation, checks, updateStage)
        else -> throw AppE2eAssertionException()
    }

    private suspend fun runProcessPrepare(
        invocation: AppE2eInvocation,
        checks: MutableSet<String>,
        updateStage: (String) -> Unit,
    ): AppE2eResult {
        if (!BuildConfig.USE_KOTLIN_FRPC_STCP_VISITOR) throw AppE2eAssertionException()
        checks += CHECK_KOTLIN_BACKEND

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as? OpenCodeApplication
            ?: throw AppE2eAssertionException()
        val container = application.container
        val bindPort = reserveLoopbackPort()
        var server: ServerConfig? = null
        var globalLease: io.github.ycfeng.ocdeck.core.network.GlobalConnectionLease? = null
        var projectLease: io.github.ycfeng.ocdeck.core.network.ProjectConnectionLease? = null
        var primaryFailure: Throwable? = null
        var generation = 0L
        var controlEpoch = 0L

        try {
            updateStage(STAGE_PROCESS_SERVER_PERSIST)
            server = container.serverRepository.addServer(
                baseUrl = "http://127.0.0.1:$bindPort/",
                name = SERVER_NAME,
                username = null,
                password = null,
                frpcStcpVisitor = NewServerFrpcStcpVisitor(
                    enabled = true,
                    serverAddr = "127.0.0.1",
                    serverPort = invocation.frpsPort,
                    authToken = invocation.authToken,
                    user = invocation.user,
                    serverUser = invocation.serverUser,
                    serverName = invocation.serverName,
                    secretKey = invocation.secretKey,
                    bindPort = bindPort,
                    transportProtocol = FrpcTransportProtocol.Tcp,
                    wireProtocol = FrpcWireProtocol.V2,
                ),
            )
            val persisted = withTimeout(STATE_TIMEOUT_MILLIS) {
                container.serverRepository.observeServers().first { servers ->
                    servers.size == 1 && servers.single().id == server.id
                }
            }.single()
            if (persisted.frpcStcpVisitor?.bindPort != bindPort) throw AppE2eAssertionException()
            checks += CHECK_SERVER_PERSISTED

            updateStage(STAGE_PROCESS_INITIAL_CONNECTION)
            val connection = container.serverRepository.getConnection(server.id)
            generation = connection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
            controlEpoch = connection.stcpControlEpoch ?: throw AppE2eAssertionException()
            if (connection.readinessHealth?.healthy != true || generation <= 0L || controlEpoch <= 0L) {
                throw AppE2eAssertionException()
            }
            checks += CHECK_STCP_READY

            updateStage(STAGE_PROCESS_INITIAL_SNAPSHOT)
            val loaded = container.openCodeRepository.loadProjectSnapshot(
                serverId = server.id,
                directory = PROJECT_DIRECTORY,
                workspace = null,
                sessionWindow = SessionListWindowState(),
                activeSessionMessages = null,
            ).getOrThrow()
            if (loaded.transportIdentity != connection.transportIdentity) throw AppE2eAssertionException()
            container.openCodeStore.applyProjectSnapshot(loaded.snapshot)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1"
            }
            checks += CHECK_SNAPSHOT_STORE

            updateStage(STAGE_PROCESS_INITIAL_STREAMS)
            globalLease = container.openCodeEventClient.connectGlobal(server.id)
            withTimeout(STATE_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1"
            }
            projectLease = container.openCodeEventClient.connectProject(server.id, PROJECT_DIRECTORY)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"
            }
            checks += CHECK_INITIAL_STREAMS

            updateStage(STAGE_PROCESS_INITIAL_ACTIVITY)
            launchMainActivity(instrumentation, server.id)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.activeSessionId == SESSION_SNAPSHOT && state.hasGenerationOneData()
            }
            checks += CHECK_INITIAL_ACTIVITY_ROUTE
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = withContext(NonCancellable) {
            val cleanup = CleanupCollector()
            cleanup.run { projectLease?.release() }
            cleanup.run { globalLease?.release() }
            val configured = server
            val streamsClosed = configured != null && cleanup.run {
                container.openCodeEventClient.closeServer(configured.id)
                awaitStreamsClosed(container, configured.id)
            }
            val visitorClosed = configured != null && cleanup.run {
                container.frpcStcpVisitorManager.closeVisitor(configured.id)
            }
            val portReleased = bindPort > 0 && cleanup.run { awaitPortReleased(bindPort) }
            val preserveServer = primaryFailure == null && cleanup.failure() == null && configured != null
            val persistenceVerified = preserveServer && cleanup.run {
                val persisted = container.serverRepository.observeServers().first()
                if (persisted.size != 1 || persisted.single().id != configured.id) {
                    throw AppE2eAssertionException()
                }
            }
            if (persistenceVerified) {
                if (streamsClosed && visitorClosed) checks += CHECK_RUNTIME_CLOSED
                if (portReleased) checks += CHECK_PORT_RELEASED
            } else {
                cleanup.run { deleteProcessServers(container, configured) }
            }
            cleanup.failure()
        }
        throwSelected(primaryFailure, cleanupFailure)

        if (checks != PROCESS_PREPARE_EXPECTED_CHECKS) throw AppE2eAssertionException()
        return AppE2eResult(
            schemaVersion = SCHEMA_VERSION,
            runId = invocation.runId,
            status = STATUS_PASSED,
            checks = checks.toList(),
            bindPort = bindPort,
            initialGeneration = generation,
            initialControlEpoch = controlEpoch,
            suite = invocation.suite,
            topology = TOPOLOGY_ADB_REVERSE,
            networkSwitchProven = false,
            phase = invocation.phase,
            processId = Process.myPid(),
        )
    }

    private suspend fun runProcessRecover(
        invocation: AppE2eInvocation,
        checks: MutableSet<String>,
        updateStage: (String) -> Unit,
    ): AppE2eResult {
        if (!BuildConfig.USE_KOTLIN_FRPC_STCP_VISITOR) throw AppE2eAssertionException()
        checks += CHECK_KOTLIN_BACKEND

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as? OpenCodeApplication
            ?: throw AppE2eAssertionException()
        val container = application.container
        var server: ServerConfig? = null
        var globalLease: io.github.ycfeng.ocdeck.core.network.GlobalConnectionLease? = null
        var projectLease: io.github.ycfeng.ocdeck.core.network.ProjectConnectionLease? = null
        var primaryFailure: Throwable? = null
        var generation = 0L
        var controlEpoch = 0L
        var bindPort = 0

        try {
            updateStage(STAGE_PROCESS_PERSISTED_LOAD)
            server = withTimeout(STATE_TIMEOUT_MILLIS) {
                container.serverRepository.observeServers().first { servers ->
                    servers.size == 1 && servers.single().name == SERVER_NAME
                }
            }.single()
            bindPort = server.frpcStcpVisitor?.bindPort ?: throw AppE2eAssertionException()
            if (bindPort !in 1..65_535) throw AppE2eAssertionException()
            checks += CHECK_PERSISTED_SERVER_LOADED

            updateStage(STAGE_PROCESS_RECONNECTED)
            val connection = container.serverRepository.getConnection(server.id)
            generation = connection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
            controlEpoch = connection.stcpControlEpoch ?: throw AppE2eAssertionException()
            if (connection.readinessHealth?.healthy != true || generation <= 0L || controlEpoch <= 0L) {
                throw AppE2eAssertionException()
            }
            checks += CHECK_STCP_RECONNECTED

            updateStage(STAGE_PROCESS_RESTORED_SNAPSHOT)
            val loaded = container.openCodeRepository.loadProjectSnapshot(
                serverId = server.id,
                directory = PROJECT_DIRECTORY,
                workspace = null,
                sessionWindow = SessionListWindowState(),
                activeSessionMessages = null,
            ).getOrThrow()
            if (loaded.transportIdentity != connection.transportIdentity) throw AppE2eAssertionException()
            container.openCodeStore.applyProjectSnapshot(loaded.snapshot)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-1"
            }
            checks += CHECK_SNAPSHOT_RESTORED

            updateStage(STAGE_PROCESS_RESTORED_STREAMS)
            globalLease = container.openCodeEventClient.connectGlobal(server.id)
            withTimeout(STATE_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1"
            }
            projectLease = container.openCodeEventClient.connectProject(server.id, PROJECT_DIRECTORY)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"
            }
            checks += CHECK_STREAMS_RESTORED

            updateStage(STAGE_PROCESS_RESTORED_ACTIVITY)
            launchMainActivity(instrumentation, server.id)
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.activeSessionId == SESSION_SNAPSHOT && state.hasGenerationOneData()
            }
            checks += CHECK_ACTIVITY_ROUTE_RESTORED
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = withContext(NonCancellable) {
            val cleanup = CleanupCollector()
            cleanup.run { projectLease?.release() }
            cleanup.run { globalLease?.release() }
            val configured = server
            val streamsClosed = configured != null && cleanup.run {
                container.openCodeEventClient.closeServer(configured.id)
                awaitStreamsClosed(container, configured.id)
            }
            if (streamsClosed) checks += CHECK_STREAMS_CLOSED
            if (configured != null) {
                cleanup.run { container.frpcStcpVisitorManager.closeVisitor(configured.id) }
            }
            val serverDeleted = cleanup.run {
                deleteProcessServers(container, configured)
                if (container.serverRepository.observeServers().first().isNotEmpty()) {
                    throw AppE2eAssertionException()
                }
            }
            if (serverDeleted) {
                checks += CHECK_SERVER_DELETED
                checks += CHECK_VISITOR_CLOSED
            }
            val portReleased = bindPort > 0 && cleanup.run { awaitPortReleased(bindPort) }
            if (portReleased) checks += CHECK_PORT_RELEASED
            cleanup.failure()
        }
        throwSelected(primaryFailure, cleanupFailure)

        if (checks != PROCESS_RECOVER_EXPECTED_CHECKS) throw AppE2eAssertionException()
        return AppE2eResult(
            schemaVersion = SCHEMA_VERSION,
            runId = invocation.runId,
            status = STATUS_PASSED,
            checks = checks.toList(),
            bindPort = bindPort,
            initialGeneration = generation,
            initialControlEpoch = controlEpoch,
            suite = invocation.suite,
            topology = TOPOLOGY_ADB_REVERSE,
            networkSwitchProven = false,
            phase = invocation.phase,
            processId = Process.myPid(),
        )
    }

    private suspend fun awaitStreamsClosed(
        container: io.github.ycfeng.ocdeck.app.AppContainer,
        serverId: String,
    ) {
        awaitProject(container.openCodeStore.observeProject(serverId, PROJECT_DIRECTORY)) { state ->
            state.connectionStatus == SseConnectionStatus.Closed
        }
        withTimeout(STATE_TIMEOUT_MILLIS) {
            container.openCodeStore.observeGlobalConnection(serverId).first { it == SseConnectionStatus.Closed }
        }
    }

    private suspend fun deleteProcessServers(
        container: io.github.ycfeng.ocdeck.app.AppContainer,
        preferred: ServerConfig?,
    ) {
        val persisted = container.serverRepository.observeServers().first()
        (listOfNotNull(preferred) + persisted.filter { it.name == SERVER_NAME })
            .distinctBy(ServerConfig::id)
            .forEach { container.serverRepository.deleteServer(it) }
        if (container.serverRepository.observeServers().first().any { it.name == SERVER_NAME }) {
            throw AppE2eAssertionException()
        }
    }

    private suspend fun awaitProject(
        flow: Flow<OpenCodeProjectState>,
        predicate: (OpenCodeProjectState) -> Boolean,
    ): OpenCodeProjectState = withTimeout(STATE_TIMEOUT_MILLIS) { flow.first(predicate) }

    private suspend fun reserveLoopbackPort(): Int = withContext(Dispatchers.IO) {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(IPV4_LOOPBACK, 0), 1)
            socket.localPort
        }
    }

    private suspend fun awaitPortReleased(port: Int) = withTimeout(PORT_RELEASE_TIMEOUT_MILLIS) {
        while (true) {
            val released = withContext(Dispatchers.IO) {
                try {
                    ServerSocket().use { socket ->
                        // Active listeners still prevent the bind, while reuse avoids TIME_WAIT false positives.
                        socket.reuseAddress = true
                        socket.bind(InetSocketAddress(IPV4_LOOPBACK, port), 1)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (released) return@withTimeout
            delay(PORT_RELEASE_POLL_MILLIS)
        }
    }

    private fun OpenCodeProjectState.sessionTitle(id: String): String? =
        sessions.firstOrNull { it.id == id }?.title

    private fun OpenCodeProjectState.hasGenerationOneData(): Boolean =
        sessionTitle(SESSION_SNAPSHOT) == "snapshot-1" &&
            sessionTitle(SESSION_GLOBAL_SSE) == "global-sse-1" &&
            sessionTitle(SESSION_PROJECT_SSE) == "project-sse-1"

    private fun OpenCodeProjectState.hasGenerationTwoData(): Boolean =
        sessionTitle(SESSION_SNAPSHOT) == "snapshot-2" &&
            sessionTitle(SESSION_PROJECT_SSE) == "project-sse-2"

    private fun launchMainActivity(instrumentation: Instrumentation, serverId: String) {
        val intent = Intent().apply {
            setClassName(TARGET_PACKAGE, MAIN_ACTIVITY_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(OpenCodeNotificationTarget.ExtraServerId, serverId)
            putExtra(OpenCodeNotificationTarget.ExtraDirectory, PROJECT_DIRECTORY)
            putExtra(OpenCodeNotificationTarget.ExtraSessionId, SESSION_SNAPSHOT)
        }
        instrumentation.targetContext.startActivity(intent)
    }

    private fun pressHome(instrumentation: Instrumentation) {
        readShellOutput(instrumentation, "input keyevent KEYCODE_HOME")
    }

    private suspend fun forceDeviceIdle(instrumentation: Instrumentation) {
        readShellOutput(instrumentation, "dumpsys battery unplug")
        readShellOutput(instrumentation, "cmd deviceidle force-idle")
        awaitDeviceIdleState(instrumentation, DEVICE_IDLE)
    }

    private suspend fun restoreDeviceIdle(instrumentation: Instrumentation) {
        readShellOutput(instrumentation, "cmd deviceidle unforce")
        readShellOutput(instrumentation, "dumpsys battery reset")
        readShellOutput(instrumentation, "input keyevent KEYCODE_WAKEUP")
        readShellOutput(instrumentation, "wm dismiss-keyguard")
        awaitDeviceIdleState(instrumentation, DEVICE_ACTIVE)
    }

    private suspend fun awaitDeviceIdleState(instrumentation: Instrumentation, expected: String) =
        withTimeout(DEVICE_IDLE_TIMEOUT_MILLIS) {
            while (true) {
                if (readShellOutput(instrumentation, "cmd deviceidle get deep") == expected) return@withTimeout
                delay(MARKER_POLL_MILLIS)
            }
        }

    private fun validateInvocation(invocation: AppE2eInvocation, runId: String) {
        if (invocation.schemaVersion != SCHEMA_VERSION || invocation.runId != runId ||
            invocation.suite !in setOf(SUITE_CORE, SUITE_LIFECYCLE, SUITE_PROCESS) ||
            !isValidPhase(invocation.suite, invocation.phase) ||
            invocation.outageMillis !in MINIMUM_OUTAGE_MILLIS..MAXIMUM_OUTAGE_MILLIS ||
            invocation.frpsPort !in 1..65_535 || invocation.authToken.isBlank() ||
            invocation.user.isBlank() || invocation.serverUser.isBlank() ||
            invocation.serverName.isBlank() || invocation.secretKey.isBlank()
        ) {
            throw AppE2eAssertionException()
        }
        val markerFiles = invocation.restartMarkers.allFiles
        if (markerFiles.distinct().size != markerFiles.size || markerFiles.any { !SAFE_FILE_NAME.matches(it) }) {
            throw AppE2eAssertionException()
        }
    }

    private inline fun <reified T> readPrivateJson(instrumentation: Instrumentation, fileName: String): T {
        val output = readShellOutput(
            instrumentation,
            "run-as $TEST_PACKAGE cat files/$fileName",
        )
        return JSON.decodeFromString(output)
    }

    private suspend fun awaitPrivateMarker(
        instrumentation: Instrumentation,
        fileName: String,
        expected: String,
    ) = withTimeout(MARKER_TIMEOUT_MILLIS) {
        while (true) {
            val output = readShellOutput(
                instrumentation,
                "run-as $TEST_PACKAGE cat files/$fileName",
            )
            if (output == expected) return@withTimeout
            delay(MARKER_POLL_MILLIS)
        }
    }

    private fun writePrivateMarker(
        instrumentation: Instrumentation,
        fileName: String,
    ) {
        val output = readShellOutput(
            instrumentation,
            "run-as $TEST_PACKAGE touch files/$fileName",
        )
        if (output.isNotEmpty()) throw AppE2eAssertionException()
    }

    private fun readShellOutput(instrumentation: Instrumentation, command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        val output = ByteArrayOutputStream()
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val buffer = ByteArray(JSON_BUFFER_BYTES)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAXIMUM_JSON_BYTES) throw AppE2eAssertionException()
                output.write(buffer, 0, count)
            }
            buffer.fill(0)
        }
        return output.toString(Charsets.UTF_8.name()).trim()
    }

    private fun requireSafeRunId(value: String?): String = value
        ?.takeIf { SAFE_RUN_ID.matches(it) }
        ?: throw AppE2eAssertionException()

    private fun requireSafeFileName(value: String?): String = value
        ?.takeIf { SAFE_FILE_NAME.matches(it) && it.endsWith(".json") }
        ?: throw AppE2eAssertionException()

    private fun failedResult(
        runId: String,
        stage: String,
        checks: Set<String>,
        failureCode: String,
    ): AppE2eResult = AppE2eResult(
        schemaVersion = SCHEMA_VERSION,
        runId = runId,
        status = STATUS_FAILED,
        checks = checks.toList(),
        failedStage = stage,
        failureCode = failureCode,
    )

    private fun throwSelected(vararg failures: Throwable?) {
        failures.firstOrNull { it is Error }?.let { throw it }
        failures.firstOrNull { it is CancellationException }?.let { throw it }
        failures.firstOrNull()?.let { throw it }
    }

    private class CleanupCollector {
        private val failures = mutableListOf<Throwable>()

        suspend fun run(block: suspend () -> Unit): Boolean = try {
            block()
            true
        } catch (failure: Throwable) {
            failures += failure
            false
        }

        fun failure(): Throwable? = failures.firstOrNull { it is Error }
            ?: failures.firstOrNull { it is CancellationException }
            ?: failures.firstOrNull()
    }

    private class AppE2eAssertionException : Exception()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val SAFE_RUN_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val SAFE_FILE_NAME = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        const val SCHEMA_VERSION = 3
        const val ARG_ENABLED = "ocdeck.appE2e.enabled"
        const val ARG_RUN_ID = "ocdeck.appE2e.runId"
        const val ARG_CONFIG_FILE = "ocdeck.appE2e.configFile"
        const val STATUS_RESULT_KEY = "ocdeckAppE2eResult"
        const val TEST_PACKAGE = "io.github.ycfeng.ocdeck.kotlinrelease.test"
        const val TARGET_PACKAGE = "io.github.ycfeng.ocdeck.kotlinrelease"
        const val MAIN_ACTIVITY_CLASS = "io.github.ycfeng.ocdeck.MainActivity"
        const val SUITE_CORE = "core"
        const val SUITE_LIFECYCLE = "lifecycle"
        const val SUITE_PROCESS = "process"
        const val PHASE_SINGLE = "single"
        const val PHASE_PROCESS_PREPARE = "process_prepare"
        const val PHASE_PROCESS_RECOVER = "process_recover"
        const val TOPOLOGY_ADB_REVERSE = "adb_reverse"
        const val PROJECT_DIRECTORY = "/workspace"
        const val SESSION_SNAPSHOT = "session-snapshot"
        const val SESSION_GLOBAL_SSE = "session-global-sse"
        const val SESSION_PROJECT_SSE = "session-project-sse"
        const val SERVER_NAME = "STCP App E2E"
        const val STATUS_PASSED = "passed"
        const val STATUS_FAILED = "failed"
        const val FAILURE_TIMEOUT = "TIMEOUT"
        const val FAILURE_ASSERTION = "ASSERTION"
        const val FAILURE_UNEXPECTED = "UNEXPECTED"
        const val STAGE_CONFIGURATION = "configuration"
        const val STAGE_SERVER_CONFIGURATION = "server_configuration"
        const val STAGE_STCP_READY = "stcp_ready"
        const val STAGE_SNAPSHOT = "snapshot"
        const val STAGE_GLOBAL_SSE = "global_sse"
        const val STAGE_PROJECT_SSE = "project_sse"
        const val STAGE_FOREGROUND_RECONCILIATION = "foreground_reconciliation"
        const val STAGE_ACTIVITY_FOREGROUND = "activity_foreground"
        const val STAGE_ACTIVITY_CYCLES = "activity_cycles"
        const val STAGE_DOZE_ENTER = "doze_enter"
        const val STAGE_DOZE_EXIT = "doze_exit"
        const val STAGE_RESTART_DISCONNECT = "restart_disconnect"
        const val STAGE_RESTART_RECOVERY = "restart_recovery"
        const val STAGE_RESTART_RECOVERY_MARKER = "restart_recovery_marker"
        const val STAGE_RESTART_RECOVERY_GLOBAL = "restart_recovery_global"
        const val STAGE_RESTART_RECOVERY_PROJECT_OPEN = "restart_recovery_project_open"
        const val STAGE_RESTART_RECOVERY_PROJECT_SSE = "restart_recovery_project_sse"
        const val STAGE_RESTART_RECOVERY_SNAPSHOT = "restart_recovery_snapshot"
        const val STAGE_RESTART_RECOVERY_GLOBAL_DATA = "restart_recovery_global_data"
        const val STAGE_RESTART_RECOVERY_IDENTITY = "restart_recovery_identity"
        const val STAGE_RESTART_RECOVERY_GENERATION = "restart_recovery_generation"
        const val STAGE_RESTART_RECOVERY_EPOCH = "restart_recovery_epoch"
        const val STAGE_RESTART_RECOVERY_TRANSPORT = "restart_recovery_transport"
        const val STAGE_POST_RESTART_FOREGROUND = "post_restart_foreground"
        const val STAGE_CLEANUP = "cleanup"
        const val STAGE_PROCESS_SERVER_PERSIST = "process_server_persist"
        const val STAGE_PROCESS_INITIAL_CONNECTION = "process_initial_connection"
        const val STAGE_PROCESS_INITIAL_SNAPSHOT = "process_initial_snapshot"
        const val STAGE_PROCESS_INITIAL_STREAMS = "process_initial_streams"
        const val STAGE_PROCESS_INITIAL_ACTIVITY = "process_initial_activity"
        const val STAGE_PROCESS_PERSISTED_LOAD = "process_persisted_load"
        const val STAGE_PROCESS_RECONNECTED = "process_reconnected"
        const val STAGE_PROCESS_RESTORED_SNAPSHOT = "process_restored_snapshot"
        const val STAGE_PROCESS_RESTORED_STREAMS = "process_restored_streams"
        const val STAGE_PROCESS_RESTORED_ACTIVITY = "process_restored_activity"
        const val CHECK_KOTLIN_BACKEND = "kotlin_backend"
        const val CHECK_STCP_READY = "stcp_ready"
        const val CHECK_SNAPSHOT_STORE = "snapshot_store"
        const val CHECK_GLOBAL_SSE_STORE = "global_sse_store"
        const val CHECK_PROJECT_SSE_STORE = "project_sse_store"
        const val CHECK_FOREGROUND_RECONCILIATION = "foreground_reconciliation"
        const val CHECK_ACTIVITY_FOREGROUND = "activity_foreground"
        const val CHECK_ACTIVITY_BACKGROUND_DATA_RETAINED = "activity_background_data_retained"
        const val CHECK_ACTIVITY_FOREGROUND_RECONCILIATION = "activity_foreground_reconciliation"
        const val CHECK_DOZE_ENTERED = "doze_entered"
        const val CHECK_DOZE_EXIT_RECOVERY = "doze_exit_recovery"
        const val CHECK_DISCONNECT_DATA_RETAINED = "disconnect_data_retained"
        const val CHECK_LONG_OUTAGE_DATA_RETAINED = "long_outage_data_retained"
        const val CHECK_PROVIDER_RACE_DATA_RETAINED = "provider_race_data_retained"
        const val CHECK_CONTROL_EPOCH_ADVANCED = "control_epoch_advanced"
        const val CHECK_RESTART_RECONCILIATION = "restart_reconciliation"
        const val CHECK_POST_RESTART_FOREGROUND = "post_restart_foreground"
        const val CHECK_STREAMS_CLOSED = "streams_closed"
        const val CHECK_VISITOR_CLOSED = "visitor_closed"
        const val CHECK_PORT_RELEASED = "port_released"
        const val CHECK_SERVER_PERSISTED = "server_persisted"
        const val CHECK_INITIAL_STREAMS = "initial_streams"
        const val CHECK_INITIAL_ACTIVITY_ROUTE = "initial_activity_route"
        const val CHECK_RUNTIME_CLOSED = "runtime_closed"
        const val CHECK_PERSISTED_SERVER_LOADED = "persisted_server_loaded"
        const val CHECK_STCP_RECONNECTED = "stcp_reconnected"
        const val CHECK_SNAPSHOT_RESTORED = "snapshot_restored"
        const val CHECK_STREAMS_RESTORED = "streams_restored"
        const val CHECK_ACTIVITY_ROUTE_RESTORED = "activity_route_restored"
        const val CHECK_SERVER_DELETED = "server_deleted"
        val CORE_EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_STCP_READY,
            CHECK_SNAPSHOT_STORE,
            CHECK_GLOBAL_SSE_STORE,
            CHECK_PROJECT_SSE_STORE,
            CHECK_FOREGROUND_RECONCILIATION,
            CHECK_DISCONNECT_DATA_RETAINED,
            CHECK_CONTROL_EPOCH_ADVANCED,
            CHECK_RESTART_RECONCILIATION,
            CHECK_POST_RESTART_FOREGROUND,
            CHECK_STREAMS_CLOSED,
            CHECK_VISITOR_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val LIFECYCLE_EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_STCP_READY,
            CHECK_SNAPSHOT_STORE,
            CHECK_GLOBAL_SSE_STORE,
            CHECK_PROJECT_SSE_STORE,
            CHECK_ACTIVITY_FOREGROUND,
            CHECK_ACTIVITY_BACKGROUND_DATA_RETAINED,
            CHECK_ACTIVITY_FOREGROUND_RECONCILIATION,
            CHECK_DOZE_ENTERED,
            CHECK_DOZE_EXIT_RECOVERY,
            CHECK_DISCONNECT_DATA_RETAINED,
            CHECK_PROVIDER_RACE_DATA_RETAINED,
            CHECK_LONG_OUTAGE_DATA_RETAINED,
            CHECK_CONTROL_EPOCH_ADVANCED,
            CHECK_RESTART_RECONCILIATION,
            CHECK_POST_RESTART_FOREGROUND,
            CHECK_STREAMS_CLOSED,
            CHECK_VISITOR_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val PROCESS_PREPARE_EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_SERVER_PERSISTED,
            CHECK_STCP_READY,
            CHECK_SNAPSHOT_STORE,
            CHECK_INITIAL_STREAMS,
            CHECK_INITIAL_ACTIVITY_ROUTE,
            CHECK_RUNTIME_CLOSED,
            CHECK_PORT_RELEASED,
        )
        val PROCESS_RECOVER_EXPECTED_CHECKS = linkedSetOf(
            CHECK_KOTLIN_BACKEND,
            CHECK_PERSISTED_SERVER_LOADED,
            CHECK_STCP_RECONNECTED,
            CHECK_SNAPSHOT_RESTORED,
            CHECK_STREAMS_RESTORED,
            CHECK_ACTIVITY_ROUTE_RESTORED,
            CHECK_STREAMS_CLOSED,
            CHECK_SERVER_DELETED,
            CHECK_VISITOR_CLOSED,
            CHECK_PORT_RELEASED,
        )
        fun expectedChecks(suite: String): Set<String> = when (suite) {
            SUITE_CORE -> CORE_EXPECTED_CHECKS
            SUITE_LIFECYCLE -> LIFECYCLE_EXPECTED_CHECKS
            else -> throw AppE2eAssertionException()
        }
        fun isValidPhase(suite: String, phase: String): Boolean = when (suite) {
            SUITE_CORE, SUITE_LIFECYCLE -> phase == PHASE_SINGLE
            SUITE_PROCESS -> phase in setOf(PHASE_PROCESS_PREPARE, PHASE_PROCESS_RECOVER)
            else -> false
        }
        const val MAXIMUM_JSON_BYTES = 64 * 1024
        const val JSON_BUFFER_BYTES = 8 * 1024
        const val SCENARIO_TIMEOUT_MILLIS = 600_000L
        const val STATE_TIMEOUT_MILLIS = 45_000L
        const val RECOVERY_TIMEOUT_MILLIS = 90_000L
        const val MARKER_TIMEOUT_MILLIS = 360_000L
        const val MARKER_POLL_MILLIS = 100L
        const val PORT_RELEASE_TIMEOUT_MILLIS = 30_000L
        const val PORT_RELEASE_POLL_MILLIS = 100L
        const val MARKER_FRPS_RESTARTED = "frps_restarted"
        const val MARKER_FRPS_ONLY = "frps_only"
        const val DEVICE_IDLE = "IDLE"
        const val DEVICE_ACTIVE = "ACTIVE"
        const val DEVICE_IDLE_TIMEOUT_MILLIS = 30_000L
        const val DOZE_HOLD_MILLIS = 5_000L
        const val PROVIDER_DOWN_ASSERTION_MILLIS = 5_000L
        const val LIFECYCLE_CYCLE_COUNT = 3
        const val MINIMUM_OUTAGE_MILLIS = 60_000L
        const val MAXIMUM_OUTAGE_MILLIS = 300_000L
    }
}

@Serializable
private data class AppE2eInvocation(
    val schemaVersion: Int,
    val runId: String,
    val suite: String,
    val phase: String,
    val outageMillis: Long,
    val frpsPort: Int,
    val authToken: String,
    val user: String,
    val serverUser: String,
    val serverName: String,
    val secretKey: String,
    val restartMarkers: AppE2eRestartMarkers,
) {
    override fun toString(): String =
        "AppE2eInvocation(schemaVersion=$schemaVersion, runId=<redacted>, suite=$suite, phase=$phase, " +
            "outageMillis=$outageMillis, frpsPort=$frpsPort, " +
            "authToken=<redacted>, user=<redacted>, serverUser=<redacted>, " +
            "serverName=<redacted>, secretKey=<redacted>)"
}

@Serializable
private data class AppE2eRestartMarkers(
    val deviceReadyFile: String,
    val deviceDisconnectedFile: String,
    val frpsOnlyFile: String,
    val providerWindowObservedFile: String,
    val frpsRestartedFile: String,
) {
    val allFiles: List<String>
        get() = listOf(
            deviceReadyFile,
            deviceDisconnectedFile,
            frpsOnlyFile,
            providerWindowObservedFile,
            frpsRestartedFile,
        )

    override fun toString(): String = "AppE2eRestartMarkers(configured=true)"
}

@Serializable
private data class AppE2eResult(
    val schemaVersion: Int,
    val runId: String,
    val status: String,
    val checks: List<String>,
    val bindPort: Int? = null,
    val initialGeneration: Long? = null,
    val initialControlEpoch: Long? = null,
    val recoveredGeneration: Long? = null,
    val recoveredControlEpoch: Long? = null,
    val suite: String? = null,
    val topology: String? = null,
    val networkSwitchProven: Boolean? = null,
    val phase: String? = null,
    val processId: Int? = null,
    val failedStage: String? = null,
    val failureCode: String? = null,
) {
    override fun toString(): String =
        "AppE2eResult(schemaVersion=$schemaVersion, runId=<redacted>, status=$status, " +
            "checkCount=${checks.size}, bindPort=$bindPort, initialGeneration=$initialGeneration, " +
            "initialControlEpoch=$initialControlEpoch, recoveredGeneration=$recoveredGeneration, " +
            "recoveredControlEpoch=$recoveredControlEpoch, suite=$suite, topology=$topology, " +
            "networkSwitchProven=$networkSwitchProven, phase=$phase, processPresent=${processId != null}, " +
            "failedStage=$failedStage, failureCode=$failureCode)"
}
