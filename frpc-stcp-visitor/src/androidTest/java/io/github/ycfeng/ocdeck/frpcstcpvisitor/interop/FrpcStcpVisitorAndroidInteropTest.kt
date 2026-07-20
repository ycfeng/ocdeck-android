package io.github.ycfeng.ocdeck.frpcstcpvisitor.interop

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcSessionConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeApiMismatchException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeUnavailableException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileFrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
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
        val configFileName = requireSafeFileName(arguments.getString(ARG_CONFIG_FILE))
        val resultFileName = requireSafeFileName(arguments.getString(ARG_RESULT_FILE))
        val context = instrumentation.context
        val configFile = privateFile(context, configFileName)
        val resultFile = privateFile(context, resultFileName)
        var stage = STAGE_CONFIGURATION

        val result = try {
            val invocation = readInvocation(configFile).also {
                validateInvocation(it, runId, backend)
            }
            runBlocking {
                withTimeout(SCENARIO_TIMEOUT_MILLIS) {
                    runScenario(invocation) { current -> stage = current }
                }
            }
        } catch (failure: VirtualMachineError) {
            throw failure
        } catch (failure: ThreadDeath) {
            throw failure
        } catch (failure: Throwable) {
            failedResult(runId, backend, stage, failure)
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
        invocation: AndroidInteropInvocation,
        updateStage: (String) -> Unit,
    ): AndroidInteropResult {
        val bindPort = if (invocation.bindPort == 0) allocateLoopbackPort() else invocation.bindPort
        val client = createClient(invocation.backend)
        var sessionId: String? = null
        var primaryFailure: Throwable? = null
        var cleanupFailure: Throwable? = null
        var openStateVerified = false
        var healthVerified = false
        var globalSseVerified = false
        var projectSseVerified = false
        var concurrentTrafficVerified = false
        var closedVerified = false
        var portReleased = false

        try {
            updateStage(STAGE_SESSION_START)
            sessionId = client.startSession(
                FrpcSessionConfig(
                    serverAddr = IPV4_LOOPBACK_ADDRESS,
                    serverPort = invocation.serverPort,
                    authToken = invocation.authToken,
                    user = VISITOR_USER,
                    transportProtocol = "tcp",
                    wireProtocol = invocation.wireProtocol,
                ),
            )
            if (sessionId.isBlank()) throw AndroidInteropAssertionException()

            updateStage(STAGE_VISITOR_SETUP)
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

            updateStage(STAGE_VISITOR_READY)
            val ready = client.waitVisitorReady(
                sessionId,
                VISITOR_NAME,
                ensured.desiredRevision,
                VISITOR_READY_TIMEOUT_MILLIS,
            )
            val state = client.getState(sessionId)
            val visitor = state.visitors[VISITOR_NAME]
            if (ready.name != VISITOR_NAME || ready.desiredRevision != ensured.desiredRevision ||
                ready.phase != "ready" || !ready.listenerBound || ready.boundControlEpoch <= 0L ||
                state.sessionId != sessionId || state.phase != "open" || state.configRevision <= 0L ||
                state.controlEpoch != ready.boundControlEpoch || visitor == null || visitor.phase != "ready" ||
                !visitor.listenerBound || visitor.desiredRevision != ensured.desiredRevision ||
                visitor.boundControlEpoch != state.controlEpoch
            ) {
                throw AndroidInteropAssertionException()
            }
            openStateVerified = true

            updateStage(STAGE_TUNNEL_HEALTH)
            AndroidTunnelProbe.health(bindPort)
            healthVerified = true

            updateStage(STAGE_GLOBAL_SSE)
            AndroidTunnelProbe.firstSseEvent(bindPort, "/global/event", "{\"scope\":\"global\"}")
            globalSseVerified = true

            updateStage(STAGE_PROJECT_SSE)
            AndroidTunnelProbe.firstSseEvent(bindPort, "/event", "{\"scope\":\"project\"}")
            projectSseVerified = true

            updateStage(STAGE_CONCURRENT_TRAFFIC)
            AndroidTunnelProbe.concurrentFlowControlTraffic(bindPort)
            concurrentTrafficVerified = true
        } catch (failure: VirtualMachineError) {
            throw failure
        } catch (failure: ThreadDeath) {
            throw failure
        } catch (failure: Throwable) {
            primaryFailure = failure
        } finally {
            cleanupFailure = cleanupClient(client, sessionId, bindPort) { cleanupStage ->
                if (primaryFailure == null) updateStage(cleanupStage)
            }
            if (cleanupFailure == null) {
                closedVerified = true
                portReleased = true
            }
        }

        primaryFailure?.let { throw it }
        cleanupFailure?.let { throw it }
        return AndroidInteropResult(
            schemaVersion = SCHEMA_VERSION,
            runId = invocation.runId,
            backend = invocation.backend,
            status = STATUS_PASSED,
            bindPort = bindPort,
            openStateVerified = openStateVerified,
            healthVerified = healthVerified,
            globalSseVerified = globalSseVerified,
            projectSseVerified = projectSseVerified,
            concurrentTrafficVerified = concurrentTrafficVerified,
            closedVerified = closedVerified,
            portReleased = portReleased,
        )
    }

    private suspend fun cleanupClient(
        client: FrpcStcpVisitorClient,
        sessionId: String?,
        bindPort: Int,
        updateStage: (String) -> Unit,
    ): Throwable? = withContext(NonCancellable) {
        var selected: Throwable? = null
        if (sessionId != null) {
            updateStage(STAGE_STOP_VISITOR)
            try {
                withTimeout(CLEANUP_TIMEOUT_MILLIS) { client.stopVisitor(sessionId, VISITOR_NAME) }
            } catch (failure: Throwable) {
                selected = safeCleanupFailure(failure)
            }
            updateStage(STAGE_STOP_SESSION)
            try {
                val stopped = withTimeout(CLEANUP_TIMEOUT_MILLIS) {
                    client.stopSession(sessionId, CLEANUP_TIMEOUT_MILLIS)
                }
                if (stopped.phase != "closed" || client.getState(sessionId).phase != "closed") {
                    if (selected == null) selected = AndroidInteropAssertionException()
                }
            } catch (failure: Throwable) {
                if (selected == null) selected = safeCleanupFailure(failure)
            }
        }
        if (client is AutoCloseable) {
            updateStage(STAGE_CLIENT_CLOSE)
            try {
                client.close()
            } catch (failure: Throwable) {
                if (selected == null) selected = safeCleanupFailure(failure)
            }
        }
        updateStage(STAGE_PORT_RELEASE)
        try {
            awaitPortReleased(bindPort)
        } catch (failure: Throwable) {
            if (selected == null) selected = safeCleanupFailure(failure)
        }
        selected
    }

    private fun safeCleanupFailure(failure: Throwable): Throwable {
        if (failure is VirtualMachineError) throw failure
        if (failure is ThreadDeath) throw failure
        return failure
    }

    private suspend fun awaitPortReleased(port: Int) {
        repeat(PORT_RELEASE_ATTEMPTS) {
            val released = try {
                ServerSocket().use { socket ->
                    // Active listeners still prevent the bind, while SO_REUSEADDR avoids
                    // treating completed relay connections in TIME_WAIT as a listener leak.
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
            delay(PORT_RELEASE_POLL_MILLIS)
        }
        throw AndroidInteropAssertionException()
    }

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
        if (file.exists()) throw IOException()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        if (temporary.exists()) throw IOException()
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

    private fun readBoundedFile(file: File, maximumBytes: Int): ByteArray {
        if (!file.isFile || file.length() !in 1..maximumBytes.toLong()) throw IOException()
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

    private fun validateInvocation(invocation: AndroidInteropInvocation, runId: String, backend: String) {
        if (invocation.schemaVersion != SCHEMA_VERSION || invocation.runId != runId ||
            invocation.backend != backend || invocation.serverPort !in 1..65_535 ||
            invocation.authToken.isBlank() || invocation.authToken.length > MAXIMUM_CREDENTIAL_CHARS ||
            invocation.stcpSecret.isBlank() || invocation.stcpSecret.length > MAXIMUM_CREDENTIAL_CHARS ||
            invocation.bindPort !in 0..65_535 || invocation.wireProtocol !in setOf("v1", "v2")
        ) {
            throw AndroidInteropAssertionException()
        }
    }

    private fun failedResult(
        runId: String,
        backend: String,
        stage: String,
        failure: Throwable,
    ): AndroidInteropResult = AndroidInteropResult(
        schemaVersion = SCHEMA_VERSION,
        runId = runId,
        backend = backend,
        status = STATUS_FAILED,
        failedStage = stage.takeIf(STAGES::contains) ?: STAGE_UNKNOWN,
        failureCode = failureCode(failure),
    )

    private fun failureCode(failure: Throwable): String = when (failure) {
        is KotlinFrpcStcpVisitorException -> "KOTLIN_${failure.failure.name}"
        is GoMobileBridgeUnavailableException -> "GOMOBILE_BRIDGE_UNAVAILABLE"
        is GoMobileBridgeApiMismatchException -> "GOMOBILE_BRIDGE_API_MISMATCH"
        is TimeoutCancellationException -> "TIMEOUT"
        is CancellationException -> "CANCELLED"
        is BindException -> "BIND_CONFLICT"
        is LinkageError -> "NATIVE_LINKAGE"
        is AndroidTunnelProbeException -> "TUNNEL_PROBE"
        is AndroidInteropAssertionException -> "CONTRACT_ASSERTION"
        is SecurityException -> "SECURITY"
        is IOException -> "IO"
        else -> "UNEXPECTED"
    }

    private fun privateFile(context: Context, name: String): File = File(context.filesDir, name).also { file ->
        if (file.parentFile != context.filesDir) throw AndroidInteropAssertionException()
    }

    private fun requireSafeRunId(value: String?): String =
        value?.takeIf { SAFE_RUN_ID.matches(it) } ?: throw AndroidInteropAssertionException()

    private fun requireBackend(value: String?): String =
        value?.takeIf { it == BACKEND_GO_MOBILE || it == BACKEND_KOTLIN }
            ?: throw AndroidInteropAssertionException()

    private fun requireSafeFileName(value: String?): String =
        value?.takeIf { SAFE_FILE_NAME.matches(it) && !it.startsWith('.') }
            ?: throw AndroidInteropAssertionException()

    @Serializable
    private data class AndroidInteropInvocation(
        val schemaVersion: Int,
        val runId: String,
        val backend: String,
        val serverPort: Int,
        val authToken: String,
        val stcpSecret: String,
        val bindPort: Int,
        val wireProtocol: String,
        val useEncryption: Boolean,
        val useCompression: Boolean,
    ) {
        override fun toString(): String =
            "AndroidInteropInvocation(backend=$backend, authToken=<redacted>, stcpSecret=<redacted>)"
    }

    @Serializable
    private data class AndroidInteropResult(
        val schemaVersion: Int,
        val runId: String,
        val backend: String,
        val status: String,
        val failedStage: String? = null,
        val failureCode: String? = null,
        val bindPort: Int = 0,
        val openStateVerified: Boolean = false,
        val healthVerified: Boolean = false,
        val globalSseVerified: Boolean = false,
        val projectSseVerified: Boolean = false,
        val concurrentTrafficVerified: Boolean = false,
        val closedVerified: Boolean = false,
        val portReleased: Boolean = false,
    )

    private class AndroidInteropAssertionException : Exception()

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val SAFE_RUN_ID = Regex("[a-f0-9-]{36}")
        val SAFE_FILE_NAME = Regex("[A-Za-z0-9_-]{1,96}\\.json")
        const val SCHEMA_VERSION = 1
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
        const val MAXIMUM_CREDENTIAL_CHARS = 512
        const val MAXIMUM_CONFIG_BYTES = 8 * 1024
        const val MAXIMUM_RESULT_BYTES = 8 * 1024
        const val VISITOR_READY_TIMEOUT_MILLIS = 30_000L
        const val CLEANUP_TIMEOUT_MILLIS = 10_000L
        const val SCENARIO_TIMEOUT_MILLIS = 120_000L
        const val PORT_RELEASE_ATTEMPTS = 200
        const val PORT_RELEASE_POLL_MILLIS = 25L
        const val STAGE_CONFIGURATION = "configuration"
        const val STAGE_SESSION_START = "session_start"
        const val STAGE_VISITOR_SETUP = "visitor_setup"
        const val STAGE_VISITOR_READY = "visitor_ready"
        const val STAGE_TUNNEL_HEALTH = "tunnel_health"
        const val STAGE_GLOBAL_SSE = "global_sse"
        const val STAGE_PROJECT_SSE = "project_sse"
        const val STAGE_CONCURRENT_TRAFFIC = "concurrent_traffic"
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
            STAGE_STOP_VISITOR,
            STAGE_STOP_SESSION,
            STAGE_CLIENT_CLOSE,
            STAGE_PORT_RELEASE,
        )
    }
}
