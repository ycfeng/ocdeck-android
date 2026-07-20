package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.data.server.ServerConfig
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun interface OpenCodeHealthProbe {
    suspend fun probe(): ServerHealthDto
}

fun interface OpenCodeHealthProbeFactory {
    fun create(
        server: ServerConfig,
        password: String?,
        effectiveBaseUrl: String,
    ): OpenCodeHealthProbe
}

class DefaultOpenCodeHealthProbeFactory(
    private val apiFactory: OpenCodeApiFactory,
) : OpenCodeHealthProbeFactory {
    override fun create(
        server: ServerConfig,
        password: String?,
        effectiveBaseUrl: String,
    ): OpenCodeHealthProbe {
        val api = apiFactory.create(
            server = server,
            password = password,
            baseUrl = effectiveBaseUrl,
            timeouts = OpenCodeHttpTimeouts.Readiness,
        )
        return OpenCodeHealthProbe(api::getGlobalHealth)
    }
}

data class FrpcStcpReadinessPolicy(
    val nativeReadyTimeoutMillis: Long = 10_000,
    val totalTimeoutMillis: Long = 15_000,
    val attemptTimeoutMillis: Long = 2_500,
    val initialRetryDelayMillis: Long = 100,
    val maxRetryDelayMillis: Long = 1_000,
    val predecessorBindRetryTimeoutMillis: Long = 1_500,
) {
    init {
        require(nativeReadyTimeoutMillis > 0)
        require(totalTimeoutMillis > 0)
        require(attemptTimeoutMillis > 0)
        require(initialRetryDelayMillis > 0)
        require(maxRetryDelayMillis >= initialRetryDelayMillis)
        require(predecessorBindRetryTimeoutMillis >= 0)
    }
}

fun interface FrpcStcpReadinessRetryClassifier {
    fun isRetryable(throwable: Throwable): Boolean
}

object DefaultFrpcStcpReadinessRetryClassifier : FrpcStcpReadinessRetryClassifier {
    override fun isRetryable(throwable: Throwable): Boolean {
        val causes = throwable.causeChain()
        throwable.fatalCauseOrNull()?.let { throw it }
        if (
            causes.any {
                it is InboundPayloadTooLargeException ||
                    it is RetrofitInboundResponsePolicyMissingException
            }
        ) {
            return false
        }
        val requestFailure = causes.filterIsInstance<OpenCodeRequestException>().firstOrNull()?.failure
        if (
            requestFailure == OpenCodeFailure.ResponseTooLarge ||
            requestFailure == OpenCodeFailure.InvalidResponse ||
            requestFailure is OpenCodeFailure.OperationRejected
        ) {
            return false
        }
        val httpException = causes.filterIsInstance<HttpException>().firstOrNull()
        if (httpException != null) {
            return httpException.code() in RETRYABLE_HTTP_CODES || httpException.code() >= 500
        }
        if (causes.any { it is SSLException || it is UnknownHostException || it is SerializationException }) {
            return false
        }
        return causes.any {
            it is ConnectException ||
                it is SocketTimeoutException ||
                it is EOFException ||
                it is SocketException ||
                it is IOException ||
                it is OpenCodeUnhealthyException ||
                it is OpenCodeHealthAttemptTimeoutException
        }
    }

    private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429)
}

internal class OpenCodeUnhealthyException : IOException("OpenCode health endpoint reported an unhealthy state")

internal class OpenCodeHealthAttemptTimeoutException : IOException("OpenCode health probe attempt timed out")

class FrpcStcpReadinessTimeoutException(
    @Suppress("UNUSED_PARAMETER") serverId: String,
    cause: Throwable,
) : IOException(null, cause)
