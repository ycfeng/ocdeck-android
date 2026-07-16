package io.github.ycfeng.ocdeck.core.network

import kotlinx.serialization.SerializationException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException

class FrpcStcpReadinessRetryClassifierTest {
    @Test
    fun retriesTransientNetworkAndServerFailures() {
        assertTrue(DefaultFrpcStcpReadinessRetryClassifier.isRetryable(ConnectException("refused")))
        assertTrue(DefaultFrpcStcpReadinessRetryClassifier.isRetryable(httpException(503)))
        assertTrue(DefaultFrpcStcpReadinessRetryClassifier.isRetryable(httpException(429)))
    }

    @Test
    fun failsFastForAuthenticationTlsAndSerializationFailures() {
        assertFalse(DefaultFrpcStcpReadinessRetryClassifier.isRetryable(httpException(401)))
        assertFalse(DefaultFrpcStcpReadinessRetryClassifier.isRetryable(httpException(404)))
        assertFalse(DefaultFrpcStcpReadinessRetryClassifier.isRetryable(SSLHandshakeException("certificate")))
        assertFalse(DefaultFrpcStcpReadinessRetryClassifier.isRetryable(SerializationException("invalid JSON")))
    }

    @Test
    fun failsFastForInboundPolicyAndWrappedPermanentFailures() {
        val failures = listOf(
            RetrofitInboundResponseTooLargeException(),
            RetrofitInboundResponsePolicyMissingException(),
            OpenCodeRequestException(OpenCodeFailure.ResponseTooLarge),
            OpenCodeRequestException(OpenCodeFailure.InvalidResponse),
            OpenCodeRequestException(OpenCodeFailure.OperationRejected()),
        )

        failures.forEach { failure ->
            assertFalse(
                DefaultFrpcStcpReadinessRetryClassifier.isRetryable(
                    IOException("wrapper", failure),
                ),
            )
        }
    }

    @Test
    fun readinessTimeoutDoesNotExposeServerId() {
        val serverId = "server-id-leak-marker"
        val cause = IOException("timeout")

        val failure = FrpcStcpReadinessTimeoutException(serverId, cause)

        assertTrue(failure.cause === cause)
        assertFalse(failure.message.orEmpty().contains(serverId))
        assertFalse(failure.toString().contains(serverId))
    }

    private fun httpException(code: Int): HttpException {
        val rawResponse = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.com/global/health").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .build()
        return HttpException(Response.error<Unit>("".toResponseBody(), rawResponse))
    }
}
