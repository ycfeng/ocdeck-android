package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeApiMismatchException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileBridgeUnavailableException
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
}
