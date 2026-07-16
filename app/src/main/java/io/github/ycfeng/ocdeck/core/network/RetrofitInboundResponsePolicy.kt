package io.github.ycfeng.ocdeck.core.network

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Invocation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class RetrofitInboundResponse(
    val mode: RetrofitInboundResponseMode,
)

internal enum class RetrofitInboundResponseMode {
    BOUNDED,
    EMPTY_SUCCESS,
}

internal class RetrofitInboundResponsePolicyInterceptor(
    private val maxDecodedBytes: Long = InboundPayloadLimits.RETROFIT_DECODED_RESPONSE_BYTES,
    private val maxEncodedBytes: Long = InboundPayloadLimits.REST_ENCODED_RESPONSE_BYTES,
) : Interceptor {
    init {
        require(maxDecodedBytes in 0 until Long.MAX_VALUE) { "Decoded response limit is out of range" }
        require(maxEncodedBytes in 0 until Long.MAX_VALUE) { "Encoded response limit is out of range" }
    }

    override fun intercept(chain: Interceptor.Chain): Response =
        interceptRequest(chain.request(), chain::proceed)

    internal fun interceptRequest(
        request: Request,
        proceed: (Request) -> Response,
    ): Response {
        val invocation = request.tag(Invocation::class.java)
            ?: return proceed(request)
        val method = invocation.method()
        if (!OpenCodeApi::class.java.isAssignableFrom(invocation.service())) {
            return proceed(request)
        }
        val policy = method.getAnnotation(RetrofitInboundResponse::class.java)
            ?: throw RetrofitInboundResponsePolicyMissingException()

        val policyRequest = if (policy.mode == RetrofitInboundResponseMode.BOUNDED) {
            request.newBuilder()
                .tag(EncodedResponseLimit::class.java, EncodedResponseLimit(maxEncodedBytes))
                .build()
        } else {
            request
        }
        val response = proceed(policyRequest)
        if (!response.isSuccessful || policy.mode == RetrofitInboundResponseMode.EMPTY_SUCCESS) {
            return response.withDiscardedBody()
        }

        val body = response.body
        val declaredLength = body.contentLength()
        if (declaredLength > maxDecodedBytes) {
            body.closeForInboundPolicy()
            throw RetrofitInboundResponseTooLargeException()
        }
        return response.newBuilder()
            .body(
                BoundedResponseBody(
                    delegate = body,
                    declaredLength = declaredLength,
                    maxBytes = maxDecodedBytes,
                    tooLargeException = ::RetrofitInboundResponseTooLargeException,
                ),
            )
            .build()
    }
}

private fun Response.withDiscardedBody(): Response {
    body.closeForInboundPolicy()
    return newBuilder()
        .removeHeader("Content-Length")
        .removeHeader("Content-Encoding")
        .body(ByteArray(0).toResponseBody())
        .build()
}
