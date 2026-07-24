package io.github.ycfeng.ocdeck.interop

import android.app.Instrumentation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ycfeng.ocdeck.BuildConfig
import io.github.ycfeng.ocdeck.OpenCodeApplication
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
            awaitPrivateMarker(
                instrumentation,
                invocation.restartMarkers.frpsRestartedFile,
                MARKER_FRPS_RESTARTED,
            )
            withTimeout(RECOVERY_TIMEOUT_MILLIS) {
                container.openCodeStore.observeGlobalConnection(server.id).first { it == SseConnectionStatus.Open }
            }
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-2" &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-2" &&
                    state.sessionTitle(SESSION_GLOBAL_SSE) in setOf("global-sse-1", "global-sse-2")
            }
            val recoveredConnection = container.serverRepository.getConnection(server.id)
            recoveredGeneration = recoveredConnection.stcpGeneration?.ordinal ?: throw AppE2eAssertionException()
            recoveredControlEpoch = recoveredConnection.stcpControlEpoch ?: throw AppE2eAssertionException()
            if (recoveredGeneration != initialGeneration || recoveredControlEpoch <= initialControlEpoch ||
                recoveredConnection.transportIdentity.isOlderThan(connection.transportIdentity)
            ) {
                throw AppE2eAssertionException()
            }
            checks += CHECK_CONTROL_EPOCH_ADVANCED
            checks += CHECK_RESTART_RECONCILIATION

            updateStage(STAGE_POST_RESTART_FOREGROUND)
            val revisionBeforeRecoveryForeground = container.openCodeStore.captureProjectDataRevision(
                server.id,
                PROJECT_DIRECTORY,
            )
            container.appConnectionCoordinator.onForeground()
            awaitProject(container.openCodeStore.observeProject(server.id, PROJECT_DIRECTORY)) { state ->
                state.connectionStatus == SseConnectionStatus.Open &&
                    state.projectDataRevision > revisionBeforeRecoveryForeground &&
                    state.sessionTitle(SESSION_SNAPSHOT) == "snapshot-2" &&
                    state.sessionTitle(SESSION_PROJECT_SSE) == "project-sse-2"
            }
            checks += CHECK_POST_RESTART_FOREGROUND
        } catch (failure: Throwable) {
            primaryFailure = failure
        }

        val cleanupFailure = withContext(NonCancellable) {
            try {
                if (primaryFailure == null) updateStage(STAGE_CLEANUP)
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

        val expectedChecks = EXPECTED_CHECKS
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
        )
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

    private fun validateInvocation(invocation: AppE2eInvocation, runId: String) {
        if (invocation.schemaVersion != SCHEMA_VERSION || invocation.runId != runId ||
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

    private class AppE2eAssertionException : Exception()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val SAFE_RUN_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val SAFE_FILE_NAME = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        const val SCHEMA_VERSION = 1
        const val ARG_ENABLED = "ocdeck.appE2e.enabled"
        const val ARG_RUN_ID = "ocdeck.appE2e.runId"
        const val ARG_CONFIG_FILE = "ocdeck.appE2e.configFile"
        const val STATUS_RESULT_KEY = "ocdeckAppE2eResult"
        const val TEST_PACKAGE = "io.github.ycfeng.ocdeck.kotlinrelease.test"
        const val PROJECT_DIRECTORY = "/workspace"
        const val SESSION_SNAPSHOT = "session-snapshot"
        const val SESSION_GLOBAL_SSE = "session-global-sse"
        const val SESSION_PROJECT_SSE = "session-project-sse"
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
        const val STAGE_RESTART_DISCONNECT = "restart_disconnect"
        const val STAGE_RESTART_RECOVERY = "restart_recovery"
        const val STAGE_POST_RESTART_FOREGROUND = "post_restart_foreground"
        const val STAGE_CLEANUP = "cleanup"
        const val CHECK_KOTLIN_BACKEND = "kotlin_backend"
        const val CHECK_STCP_READY = "stcp_ready"
        const val CHECK_SNAPSHOT_STORE = "snapshot_store"
        const val CHECK_GLOBAL_SSE_STORE = "global_sse_store"
        const val CHECK_PROJECT_SSE_STORE = "project_sse_store"
        const val CHECK_FOREGROUND_RECONCILIATION = "foreground_reconciliation"
        const val CHECK_DISCONNECT_DATA_RETAINED = "disconnect_data_retained"
        const val CHECK_CONTROL_EPOCH_ADVANCED = "control_epoch_advanced"
        const val CHECK_RESTART_RECONCILIATION = "restart_reconciliation"
        const val CHECK_POST_RESTART_FOREGROUND = "post_restart_foreground"
        const val CHECK_STREAMS_CLOSED = "streams_closed"
        const val CHECK_VISITOR_CLOSED = "visitor_closed"
        const val CHECK_PORT_RELEASED = "port_released"
        val EXPECTED_CHECKS = linkedSetOf(
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
        const val MAXIMUM_JSON_BYTES = 64 * 1024
        const val JSON_BUFFER_BYTES = 8 * 1024
        const val SCENARIO_TIMEOUT_MILLIS = 300_000L
        const val STATE_TIMEOUT_MILLIS = 45_000L
        const val RECOVERY_TIMEOUT_MILLIS = 90_000L
        const val MARKER_TIMEOUT_MILLIS = 60_000L
        const val MARKER_POLL_MILLIS = 100L
        const val PORT_RELEASE_TIMEOUT_MILLIS = 30_000L
        const val PORT_RELEASE_POLL_MILLIS = 100L
        const val MARKER_FRPS_RESTARTED = "frps_restarted"
    }
}

@Serializable
private data class AppE2eInvocation(
    val schemaVersion: Int,
    val runId: String,
    val frpsPort: Int,
    val authToken: String,
    val user: String,
    val serverUser: String,
    val serverName: String,
    val secretKey: String,
    val restartMarkers: AppE2eRestartMarkers,
) {
    override fun toString(): String =
        "AppE2eInvocation(schemaVersion=$schemaVersion, runId=<redacted>, frpsPort=$frpsPort, " +
            "authToken=<redacted>, user=<redacted>, serverUser=<redacted>, " +
            "serverName=<redacted>, secretKey=<redacted>)"
}

@Serializable
private data class AppE2eRestartMarkers(
    val deviceReadyFile: String,
    val deviceDisconnectedFile: String,
    val frpsRestartedFile: String,
) {
    val allFiles: List<String>
        get() = listOf(deviceReadyFile, deviceDisconnectedFile, frpsRestartedFile)

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
    val failedStage: String? = null,
    val failureCode: String? = null,
) {
    override fun toString(): String =
        "AppE2eResult(schemaVersion=$schemaVersion, runId=<redacted>, status=$status, " +
            "checkCount=${checks.size}, bindPort=$bindPort, initialGeneration=$initialGeneration, " +
            "initialControlEpoch=$initialControlEpoch, recoveredGeneration=$recoveredGeneration, " +
            "recoveredControlEpoch=$recoveredControlEpoch, failedStage=$failedStage, failureCode=$failureCode)"
}
