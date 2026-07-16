package io.github.ycfeng.ocdeck.frpcstcpvisitor

import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GoMobileFrpcStcpVisitorClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun startSessionEncodesSessionConfig() = runTest {
        val bridge = FakeBridge()
        val client = GoMobileFrpcStcpVisitorClient(json, bridge, Unit)

        val sessionId = client.startSession(
            FrpcSessionConfig(
                serverAddr = "frps.example.com",
                serverPort = 7001,
                authToken = "token-value",
                user = "mobile",
                transportProtocol = "tcp",
                wireProtocol = "v2",
            ),
        )

        assertEquals("session-1", sessionId)
        assertEquals(
            FrpcSessionConfig(
                serverAddr = "frps.example.com",
                serverPort = 7001,
                authToken = "token-value",
                user = "mobile",
                transportProtocol = "tcp",
                wireProtocol = "v2",
            ),
            json.decodeFromString<FrpcSessionConfig>(bridge.startedConfigJson!!),
        )
    }

    @Test
    fun startSessionEncodesDefaultServerPort() = runTest {
        val bridge = FakeBridge()
        val client = GoMobileFrpcStcpVisitorClient(json, bridge, Unit)

        client.startSession(
            FrpcSessionConfig(
                serverAddr = "frps.example.com",
                authToken = "",
            ),
        )

        assertTrue(bridge.startedConfigJson!!.contains("\"serverPort\":7000"))
    }

    @Test
    fun ensureVisitorEncodesVisitorConfigAndReturnsRevision() = runTest {
        val bridge = FakeBridge(
            ensureJson = """{"bindPort":5096,"desiredRevision":7}""",
        )
        val client = GoMobileFrpcStcpVisitorClient(json, bridge, Unit)

        val result = client.ensureVisitor(
            sessionId = "session-1",
            visitor = FrpcStcpVisitorConfig(
                name = "opencode_android_remote",
                serverName = "opencode",
                serverUser = "server-user",
                secretKey = "secret-value",
                bindAddr = "127.0.0.1",
                bindPort = 5096,
                useEncryption = true,
                useCompression = true,
            ),
        )

        assertEquals(FrpcEnsureVisitorResult(bindPort = 5096, desiredRevision = 7), result)
        assertEquals("session-1", bridge.ensuredSessionId)
        assertEquals(
            FrpcStcpVisitorConfig(
                name = "opencode_android_remote",
                serverName = "opencode",
                serverUser = "server-user",
                secretKey = "secret-value",
                bindAddr = "127.0.0.1",
                bindPort = 5096,
                useEncryption = true,
                useCompression = true,
            ),
            json.decodeFromString<FrpcStcpVisitorConfig>(bridge.ensuredVisitorJson!!),
        )
    }

    @Test
    fun waitVisitorReadyDelegatesRevisionAndTimeout() = runTest {
        val bridge = FakeBridge(
            readyJson = """{
                "name":"visitor-1",
                "desiredRevision":7,
                "phase":"ready",
                "listenerBound":true,
                "boundControlEpoch":3
            }""".trimIndent(),
        )
        val client = GoMobileFrpcStcpVisitorClient(json, bridge, Unit)

        val result = client.waitVisitorReady(
            sessionId = "session-1",
            visitorName = "visitor-1",
            desiredRevision = 7,
            timeoutMillis = 8_000,
        )

        assertEquals("visitor-1", result.name)
        assertEquals(7, result.desiredRevision)
        assertEquals(3, result.boundControlEpoch)
        assertTrue(result.listenerBound)
        assertEquals(ReadyCall("session-1", "visitor-1", 7, 8_000), bridge.readyCalls.single())
    }

    @Test
    fun ensureVisitorEncodesDefaultBindPort() = runTest {
        val bridge = FakeBridge()
        val client = GoMobileFrpcStcpVisitorClient(json, bridge, Unit)

        client.ensureVisitor(
            sessionId = "session-1",
            visitor = FrpcStcpVisitorConfig(
                name = "opencode_android_remote",
                serverName = "opencode",
                secretKey = "secret-value",
            ),
        )

        assertTrue(bridge.ensuredVisitorJson!!.contains("\"bindPort\":4096"))
    }

    @Test
    fun sensitiveValueObjectsUseSafeSummaryToString() {
        val endpoint = "endpoint-leak-marker.invalid"
        val bindEndpoint = "bind-endpoint-leak-marker.invalid"
        val user = "user-leak-marker"
        val token = "token-leak-marker"
        val visitorName = "visitor-name-leak-marker"
        val serverName = "server-name-leak-marker"
        val serverUser = "server-user-leak-marker"
        val secret = "secret-leak-marker"
        val sessionId = "session-leak-marker"
        val lastError = "last-error-leak-marker"
        val visitorMapKey = "visitor-map-key-leak-marker"
        val session = FrpcSessionConfig(
            serverAddr = endpoint,
            serverPort = 7001,
            authToken = token,
            user = user,
        )
        val visitor = FrpcStcpVisitorConfig(
            name = visitorName,
            serverName = serverName,
            serverUser = serverUser,
            secretKey = secret,
            bindAddr = bindEndpoint,
            bindPort = 5096,
            useEncryption = true,
            useCompression = true,
        )
        val ready = FrpcVisitorReadyResult(
            name = visitorName,
            desiredRevision = 7,
            phase = "ready",
            listenerBound = true,
            boundControlEpoch = 3,
            lastError = lastError,
        )
        val runtime = FrpcVisitorRuntimeState(
            desiredRevision = 7,
            phase = "ready",
            listenerBound = true,
            boundControlEpoch = 3,
            lastError = lastError,
        )
        val state = FrpcStcpVisitorState(
            sessionId = sessionId,
            phase = "open",
            configRevision = 7,
            controlEpoch = 3,
            lastError = lastError,
            visitors = mapOf(visitorMapKey to runtime),
        )
        val stopped = FrpcStopSessionResult(sessionId = sessionId, phase = "closed")

        val summaries = listOf(session, visitor, ready, runtime, state, stopped).map(Any::toString)
        summaries.forEach { summary ->
            assertOmits(
                summary,
                endpoint,
                bindEndpoint,
                user,
                token,
                visitorName,
                serverName,
                serverUser,
                secret,
                sessionId,
                lastError,
                visitorMapKey,
            )
        }
        assertTrue(session.toString().contains("serverPort=7001"))
        assertTrue(session.toString().contains("authToken=<redacted>"))
        assertTrue(session.toString().contains("userPresent=true"))
        assertTrue(visitor.toString().contains("secretKey=<redacted>"))
        assertTrue(visitor.toString().contains("bindPort=5096"))
        assertTrue(ready.toString().contains("lastErrorPresent=true"))
        assertTrue(runtime.toString().contains("lastErrorPresent=true"))
        assertTrue(state.toString().contains("visitorCount=1"))
        assertTrue(stopped.toString().contains("phase=closed"))
    }

    @Test
    fun unavailableClientUsesTypedSafeFailureAndState() = runTest {
        val unsafeReason = "reason-leak-marker endpoint-leak-marker token-leak-marker"
        val sessionId = "session-leak-marker"
        val visitorName = "visitor-leak-marker"
        val client = UnavailableFrpcStcpVisitorClient(unsafeReason)

        val failures = listOf(
            suspendFailureOf {
                client.startSession(
                    FrpcSessionConfig(
                        serverAddr = "endpoint-leak-marker.invalid",
                        authToken = "token-leak-marker",
                    ),
                )
            },
            suspendFailureOf {
                client.ensureVisitor(
                    sessionId = sessionId,
                    visitor = FrpcStcpVisitorConfig(
                        name = visitorName,
                        serverName = "server-name-leak-marker",
                        secretKey = "secret-leak-marker",
                    ),
                )
            },
            suspendFailureOf {
                client.waitVisitorReady(sessionId, visitorName, 1, 1_000)
            },
        )

        failures.forEach { failure ->
            assertTrue(failure is GoMobileBridgeUnavailableException)
            assertEquals(GO_MOBILE_BRIDGE_UNAVAILABLE_MESSAGE, failure.message)
            assertNull(failure.cause)
            assertOmits(failure.toString(), unsafeReason, sessionId, visitorName, "token-leak-marker")
        }

        val state = client.getState(sessionId)
        assertEquals("unavailable", state.phase)
        assertNull(state.lastError)
        assertOmits(state.toString(), unsafeReason, sessionId)
    }

    @Test
    fun missingBridgeClassThrowsTypedSafeUnavailableFailure() {
        val missingClassName =
            "io.github.ycfeng.ocdeck.frpcstcpvisitor.missing.EndpointCredentialLeakMarker"
        val bridge = ReflectiveGoMobileFrpcStcpVisitorBridge(missingClassName)

        val failure = failureOf {
            bridge.startSession("{\"token\":\"json-token-leak-marker\"}")
        }

        assertTrue(failure is GoMobileBridgeUnavailableException)
        assertEquals(GO_MOBILE_BRIDGE_UNAVAILABLE_MESSAGE, failure.message)
        assertNull(failure.cause)
        assertOmits(failure.toString(), missingClassName, "json-token-leak-marker")
    }

    @Test
    fun missingBridgeMethodThrowsTypedSafeApiMismatchFailure() {
        val bridgeClassName = MissingMethodsBridge::class.java.name
        val bridge = ReflectiveGoMobileFrpcStcpVisitorBridge(bridgeClassName)
        val sensitiveJson = "{\"endpoint\":\"endpoint-leak-marker\",\"token\":\"token-leak-marker\"}"

        val failure = failureOf { bridge.startSession(sensitiveJson) }

        assertTrue(failure is GoMobileBridgeApiMismatchException)
        assertEquals(GO_MOBILE_BRIDGE_API_MISMATCH_MESSAGE, failure.message)
        assertNull(failure.cause)
        assertOmits(failure.toString(), bridgeClassName, sensitiveJson, "endpoint-leak-marker", "token-leak-marker")
    }

    @Test
    fun malformedBridgeJsonThrowsTypedSafeApiMismatchFailure() = runTest {
        val malformedJson =
            "{\"bindPort\":\"endpoint-leak-marker\",\"credential\":\"credential-leak-marker\""
        val bridge = FakeBridge(ensureJson = malformedJson)
        val client = GoMobileFrpcStcpVisitorClient(json, bridge, Unit)

        val failure = suspendFailureOf {
            client.ensureVisitor(
                sessionId = "session-leak-marker",
                visitor = FrpcStcpVisitorConfig(
                    name = "visitor-leak-marker",
                    serverName = "server-leak-marker",
                    secretKey = "secret-leak-marker",
                ),
            )
        }

        assertTrue(failure is GoMobileBridgeApiMismatchException)
        assertEquals(GO_MOBILE_BRIDGE_API_MISMATCH_MESSAGE, failure.message)
        generateSequence(failure) { it.cause }.forEach { reportedFailure ->
            assertTrue(reportedFailure is GoMobileBridgeApiMismatchException)
            assertEquals(GO_MOBILE_BRIDGE_API_MISMATCH_MESSAGE, reportedFailure.message)
            assertOmits(
                reportedFailure.toString(),
                malformedJson,
                "endpoint-leak-marker",
                "credential-leak-marker",
                "session-leak-marker",
                "visitor-leak-marker",
                "server-leak-marker",
                "secret-leak-marker",
            )
        }
    }

    @Test
    fun reflectiveBridgePreservesTargetExceptionsCancellationAndJvmErrors() {
        val bridge = ReflectiveGoMobileFrpcStcpVisitorBridge(ThrowingBridge::class.java.name)
        val targetFailures = listOf(
            IllegalArgumentException("target-message-leak-marker"),
            CancellationException("cancellation-message-leak-marker"),
            AssertionError("jvm-error-message-leak-marker"),
        )

        targetFailures.forEach { expected ->
            ThrowingBridge.failure = expected
            val actual = failureOf { bridge.startSession("json-leak-marker") }
            assertSame(expected, actual)
        }
    }

    @Test
    fun getStateDecodesUnknownFields() = runTest {
        val bridge = FakeBridge(
            stateJson = """{
                "sessionId":"session-1",
                "phase":"open",
                "configRevision":7,
                "controlEpoch":3,
                "visitors":{
                    "visitor-1":{
                        "desiredRevision":7,
                        "phase":"ready",
                        "listenerBound":true,
                        "boundControlEpoch":3
                    }
                },
                "extra":"ignored"
            }""".trimIndent(),
        )
        val client = GoMobileFrpcStcpVisitorClient(json, bridge, Unit)

        val state = client.getState("session-1")

        assertEquals("open", state.phase)
        assertEquals(7, state.configRevision)
        assertEquals(3, state.controlEpoch)
        assertTrue(state.visitors.getValue("visitor-1").listenerBound)
    }

    @Test
    fun stopMethodsDelegateToBridge() = runTest {
        val bridge = FakeBridge()
        val client = GoMobileFrpcStcpVisitorClient(json, bridge, Unit)

        client.stopVisitor("session-1", "visitor-1")
        val stopped = client.stopSession("session-1", timeoutMillis = 12_000)

        assertTrue(bridge.stoppedVisitors.contains("session-1" to "visitor-1"))
        assertTrue(bridge.stoppedSessions.contains("session-1" to 12_000L))
        assertEquals(FrpcStopSessionResult("session-1", "closed"), stopped)
    }

    private class FakeBridge(
        private val ensureJson: String = """{"bindPort":4096,"desiredRevision":1}""",
        private val readyJson: String = """{
            "name":"visitor-1",
            "desiredRevision":1,
            "phase":"ready",
            "listenerBound":true,
            "boundControlEpoch":1
        }""".trimIndent(),
        private val stateJson: String = """{"sessionId":"session-1","phase":"closed"}""",
    ) : GoMobileFrpcStcpVisitorBridge {
        var startedConfigJson: String? = null
        var ensuredSessionId: String? = null
        var ensuredVisitorJson: String? = null
        val readyCalls = mutableListOf<ReadyCall>()
        val stoppedVisitors = mutableListOf<Pair<String, String>>()
        val stoppedSessions = mutableListOf<Pair<String, Long>>()

        override fun startSession(configJson: String): String {
            startedConfigJson = configJson
            return "session-1"
        }

        override fun ensureVisitor(sessionId: String, visitorJson: String): String {
            ensuredSessionId = sessionId
            ensuredVisitorJson = visitorJson
            return ensureJson
        }

        override fun waitVisitorReady(
            sessionId: String,
            visitorName: String,
            desiredRevision: Long,
            timeoutMillis: Long,
        ): String {
            readyCalls += ReadyCall(sessionId, visitorName, desiredRevision, timeoutMillis)
            return readyJson
        }

        override fun stopVisitor(sessionId: String, visitorName: String) {
            stoppedVisitors += sessionId to visitorName
        }

        override fun stopSession(sessionId: String, timeoutMillis: Long): String {
            stoppedSessions += sessionId to timeoutMillis
            return """{"sessionId":"$sessionId","phase":"closed"}"""
        }

        override fun getState(sessionId: String): String = stateJson
    }

    private data class ReadyCall(
        val sessionId: String,
        val visitorName: String,
        val desiredRevision: Long,
        val timeoutMillis: Long,
    )

    private class MissingMethodsBridge

    private class ThrowingBridge {
        companion object {
            lateinit var failure: Throwable

            @JvmStatic
            fun startSession(@Suppress("UNUSED_PARAMETER") configJson: String): String = throw failure
        }
    }

    private fun assertOmits(summary: String, vararg sensitiveValues: String) {
        sensitiveValues.forEach { sensitiveValue ->
            assertFalse("Summary leaked sensitive data", summary.contains(sensitiveValue))
        }
    }

    private fun failureOf(block: () -> Unit): Throwable {
        var failure: Throwable? = null
        try {
            block()
        } catch (caught: Throwable) {
            failure = caught
        }
        return failure ?: throw AssertionError("Expected failure")
    }

    private suspend fun suspendFailureOf(block: suspend () -> Unit): Throwable {
        var failure: Throwable? = null
        try {
            block()
        } catch (caught: Throwable) {
            failure = caught
        }
        return failure ?: throw AssertionError("Expected failure")
    }
}
