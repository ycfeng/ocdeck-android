package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
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
    private val maxBytes: Long = InboundPayloadLimits.RETROFIT_RESPONSE_BYTES,
) : Interceptor {
    init {
        require(maxBytes in 0 until Long.MAX_VALUE) { "Inbound response limit is out of range" }
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

        val response = proceed(request)
        if (!response.isSuccessful || policy.mode == RetrofitInboundResponseMode.EMPTY_SUCCESS) {
            return response.withDiscardedBody()
        }

        val body = response.body
        val declaredLength = body.contentLength()
        if (declaredLength > maxBytes) {
            body.closeForPolicy()
            throw RetrofitInboundResponseTooLargeException()
        }
        return response.newBuilder()
            .body(BoundedResponseBody(body, declaredLength, maxBytes))
            .build()
    }
}

private class BoundedResponseBody(
    private val delegate: ResponseBody,
    private val declaredLength: Long,
    maxBytes: Long,
) : ResponseBody() {
    private val boundedSource = BoundedSource(delegate.source(), maxBytes).buffer()

    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = declaredLength

    override fun source(): BufferedSource = boundedSource
}

internal class BoundedSource(
    private val delegate: Source,
    private val maxBytes: Long,
) : Source {
    private var totalBytes = 0L
    private var closed = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0" }
        if (byteCount == 0L) return 0L

        val remaining = maxBytes - totalBytes
        val requested = minOf(byteCount, remaining + 1L)
        val staging = Buffer()
        val count = try {
            delegate.read(staging, requested)
        } catch (failure: Throwable) {
            closePreserving(failure)
            throw failure
        }

        if (count == -1L) return -1L
        if (count < -1L || count > requested) {
            val failure = IOException()
            closePreserving(failure)
            throw failure
        }
        if (count > remaining) {
            closeForPolicy()
            throw RetrofitInboundResponseTooLargeException()
        }

        try {
            sink.write(staging, count)
        } catch (failure: Throwable) {
            closePreserving(failure)
            throw failure
        }
        totalBytes += count
        return count
    }

    override fun timeout(): Timeout = delegate.timeout()

    override fun close() {
        if (closed) return
        closed = true
        delegate.close()
    }

    private fun closePreserving(failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
        }
    }

    private fun closeForPolicy() {
        try {
            close()
        } catch (_: Exception) {
            // The fixed, payload-free policy exception remains authoritative.
        }
    }
}

private fun Response.withDiscardedBody(): Response {
    body.closeForPolicy()
    return newBuilder()
        .removeHeader("Content-Length")
        .removeHeader("Content-Encoding")
        .body(ByteArray(0).toResponseBody())
        .build()
}

private fun ResponseBody.closeForPolicy() {
    try {
        close()
    } catch (_: Exception) {
        // Closing must not replace the body-free policy result or exception.
    }
}
