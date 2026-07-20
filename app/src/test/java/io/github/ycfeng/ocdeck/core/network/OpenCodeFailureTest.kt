package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeApiMismatchException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeUnavailableException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorFailure
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OpenCodeFailureTest {
    @Test
    fun classifierUsesSemanticTypesWithoutReadingMessages() {
        assertEquals(OpenCodeFailure.Timeout, OpenCodeFailureClassifier.classify(SocketTimeoutException("secret")))
        assertEquals(
            OpenCodeFailure.NetworkUnavailable,
            OpenCodeFailureClassifier.classify(UnknownHostException("secret")),
        )
        assertEquals(
            OpenCodeFailure.ResponseTooLarge,
            OpenCodeFailureClassifier.classify(SessionMessagesResponseTooLargeException()),
        )
        assertEquals(
            OpenCodeFailure.ResponseTooLarge,
            OpenCodeFailureClassifier.classify(EncodedResponseTooLargeException()),
        )
        assertEquals(
            OpenCodeFailure.InvalidResponse,
            OpenCodeFailureClassifier.classify(SseContentEncodingException()),
        )
        assertEquals(
            OpenCodeFailure.OperationRejected(),
            OpenCodeFailureClassifier.classify(SshHostKeyMismatchException()),
        )
        assertEquals(
            OpenCodeFailure.OperationRejected(OpenCodeOperationRejectionReason.StcpComponentUnavailable),
            OpenCodeFailureClassifier.classify(
                IllegalStateException("wrapper", GoMobileBridgeUnavailableException()),
            ),
        )
        assertEquals(
            OpenCodeFailure.InvalidResponse,
            OpenCodeFailureClassifier.classify(
                IllegalStateException("wrapper", GoMobileBridgeApiMismatchException()),
            ),
        )
    }

    @Test
    fun kotlinFrpcRuntimeFailuresMapExhaustivelyWithoutReadingMessages() {
        val cases = mapOf(
            KotlinFrpcStcpVisitorFailure.INVALID_CONFIGURATION to OpenCodeFailure.OperationRejected(),
            KotlinFrpcStcpVisitorFailure.UNSUPPORTED_CONFIGURATION to OpenCodeFailure.OperationRejected(),
            KotlinFrpcStcpVisitorFailure.CLIENT_CLOSED to OpenCodeFailure.TransportChanged,
            KotlinFrpcStcpVisitorFailure.SESSION_LIMIT to OpenCodeFailure.OperationRejected(),
            KotlinFrpcStcpVisitorFailure.SESSION_NOT_FOUND to OpenCodeFailure.TransportChanged,
            KotlinFrpcStcpVisitorFailure.SESSION_CLOSED to OpenCodeFailure.TransportChanged,
            KotlinFrpcStcpVisitorFailure.MAILBOX_FULL to OpenCodeFailure.OperationRejected(),
            KotlinFrpcStcpVisitorFailure.VISITOR_LIMIT to OpenCodeFailure.OperationRejected(),
            KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT to
                OpenCodeFailure.OperationRejected(OpenCodeOperationRejectionReason.LocalPortInUse),
            KotlinFrpcStcpVisitorFailure.LISTENER_BIND_FAILED to OpenCodeFailure.OperationRejected(),
            KotlinFrpcStcpVisitorFailure.CONTROL_FAILED to OpenCodeFailure.Unknown,
            KotlinFrpcStcpVisitorFailure.VISITOR_FAILED to OpenCodeFailure.TransportChanged,
            KotlinFrpcStcpVisitorFailure.VISITOR_NOT_FOUND to OpenCodeFailure.TransportChanged,
            KotlinFrpcStcpVisitorFailure.VISITOR_SUPERSEDED to OpenCodeFailure.TransportChanged,
            KotlinFrpcStcpVisitorFailure.WAIT_TIMEOUT to OpenCodeFailure.Timeout,
            KotlinFrpcStcpVisitorFailure.RELAY_LIMIT to OpenCodeFailure.OperationRejected(),
            KotlinFrpcStcpVisitorFailure.VISITOR_HANDSHAKE_FAILED to OpenCodeFailure.Unknown,
            KotlinFrpcStcpVisitorFailure.VISITOR_HANDSHAKE_TIMEOUT to OpenCodeFailure.Timeout,
            KotlinFrpcStcpVisitorFailure.RELAY_FAILED to OpenCodeFailure.NetworkUnavailable,
            KotlinFrpcStcpVisitorFailure.STOP_TIMEOUT to OpenCodeFailure.Timeout,
        )
        assertEquals(KotlinFrpcStcpVisitorFailure.entries.toSet(), cases.keys)

        cases.forEach { (runtimeFailure, expected) ->
            val typed = KotlinFrpcStcpVisitorException(runtimeFailure)
            val wrapped = MessageAccessFailsException(typed)

            assertEquals(expected, OpenCodeFailureClassifier.classify(typed))
            assertEquals(expected, OpenCodeFailureClassifier.classify(wrapped))
            assertEquals(expected, OpenCodeFailureClassifier.toRequestException(wrapped).failure)
        }
    }

    @Test
    fun requestFailurePrecedesNestedKotlinFrpcRuntimeFailure() {
        val expected = OpenCodeFailure.InvalidResponse
        val requestFailure = OpenCodeRequestException(
            expected,
            KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.RELAY_FAILED),
        )

        assertEquals(expected, OpenCodeFailureClassifier.classify(requestFailure))
    }

    @Test
    fun causeChainStopsAtIdentityCycle() {
        val first = IllegalStateException()
        val second = IllegalArgumentException()
        first.initCause(second)
        second.initCause(first)

        assertEquals(listOf(first, second), first.causeChain())
        assertEquals(OpenCodeFailure.Unknown, OpenCodeFailureClassifier.classify(first))
    }

    @Test
    fun requestExceptionRetainsCauseButHasSafeSummary() {
        val secret = "Authorization: Bearer request-secret"
        val cause = IllegalStateException(secret)

        val failure = OpenCodeFailureClassifier.toRequestException(cause)

        assertEquals(OpenCodeFailure.Unknown, failure.failure)
        assertTrue(failure.cause === cause)
        assertNull(failure.message)
        assertFalse(failure.toString().contains(secret))
    }

    @Test
    fun typedRequestExceptionIsPreserved() {
        val expected = LocalPortInUseException(4096, IllegalStateException("bind secret"))

        val actual = OpenCodeFailureClassifier.toRequestException(expected)

        assertTrue(actual === expected)
        assertEquals(
            OpenCodeFailure.OperationRejected(OpenCodeOperationRejectionReason.LocalPortInUse),
            actual.failure,
        )
        assertFalse(actual.toString().contains("bind secret"))
    }

    @Test
    fun classifierPropagatesTopLevelCancellationAndJvmError() {
        val cancellation = CancellationException("cancel secret")
        try {
            OpenCodeFailureClassifier.classify(cancellation)
            fail("CancellationException was not propagated")
        } catch (failure: CancellationException) {
            assertTrue(failure === cancellation)
        }

        val fatal = AssertionError("fatal secret")
        try {
            OpenCodeFailureClassifier.classify(fatal)
            fail("Error was not propagated")
        } catch (failure: AssertionError) {
            assertTrue(failure === fatal)
        }
    }

    @Test
    fun classifierAndRequestConversionPropagateNestedCancellationAndJvmError() {
        val cancellation = CancellationException("cancel secret")
        val fatal = AssertionError("fatal secret")

        listOf(cancellation, fatal).forEach { expected ->
            val wrapped = OpenCodeRequestException(OpenCodeFailure.Unknown, IllegalStateException("wrapper", expected))
            listOf<(Throwable) -> Unit>(
                { OpenCodeFailureClassifier.classify(it) },
                { OpenCodeFailureClassifier.toRequestException(it) },
            ).forEach { classify ->
                try {
                    classify(wrapped)
                    fail("Nested fatal cause was not propagated")
                } catch (actual: Throwable) {
                    assertTrue(actual === expected)
                }
            }
        }
    }

    @Test
    fun jvmErrorPrecedesEarlierCancellationInCauseChain() {
        val fatal = AssertionError("fatal secret")
        val cancellation = CancellationException("cancel secret").apply { initCause(fatal) }
        val wrapped = IllegalStateException("wrapper", cancellation)

        listOf<(Throwable) -> Unit>(
            { OpenCodeFailureClassifier.classify(it) },
            { OpenCodeFailureClassifier.toRequestException(it) },
        ).forEach { classify ->
            try {
                classify(wrapped)
                fail("Nested JVM Error was not propagated")
            } catch (actual: Throwable) {
                assertTrue(actual === fatal)
            }
        }
    }
}

private class MessageAccessFailsException(cause: Throwable) : RuntimeException(null, cause) {
    override val message: String?
        get() = throw AssertionError("Throwable.message must not be read")
}
