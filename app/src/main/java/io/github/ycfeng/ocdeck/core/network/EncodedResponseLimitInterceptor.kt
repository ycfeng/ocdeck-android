package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

internal data class EncodedResponseLimit(
    val maxBytes: Long,
) {
    init {
        require(maxBytes in 0 until Long.MAX_VALUE) { "Encoded response limit is out of range" }
    }
}

internal class EncodedResponseLimitInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        interceptRequest(chain.request(), chain::proceed)

    internal fun interceptRequest(
        request: Request,
        proceed: (Request) -> Response,
    ): Response {
        val response = proceed(request)
        val limit = request.tag(EncodedResponseLimit::class.java) ?: return response

        // Error and EMPTY_SUCCESS bodies are closed without reads by their application-level owners.
        if (!response.isSuccessful) return response

        val body = response.body
        val declaredLength = body.contentLength()
        if (declaredLength > limit.maxBytes) {
            body.closeForInboundPolicy()
            throw EncodedResponseTooLargeException()
        }
        return response.newBuilder()
            .body(
                BoundedResponseBody(
                    delegate = body,
                    declaredLength = declaredLength,
                    maxBytes = limit.maxBytes,
                    tooLargeException = ::EncodedResponseTooLargeException,
                ),
            )
            .build()
    }
}

internal class BoundedResponseBody(
    private val delegate: ResponseBody,
    private val declaredLength: Long,
    maxBytes: Long,
    tooLargeException: () -> InboundPayloadTooLargeException,
) : ResponseBody() {
    private val boundedSource = BoundedSource(delegate.source(), maxBytes, tooLargeException).buffer()

    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = declaredLength

    override fun source(): BufferedSource = boundedSource
}

internal class BoundedSource(
    private val delegate: Source,
    private val maxBytes: Long,
    private val tooLargeException: () -> InboundPayloadTooLargeException,
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
            closeForLimit()
            throw tooLargeException()
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

    private fun closeForLimit() {
        try {
            close()
        } catch (_: Exception) {
            // The fixed, payload-free boundary exception remains authoritative.
        }
    }
}

internal fun ResponseBody.closeForInboundPolicy() {
    try {
        close()
    } catch (_: Exception) {
        // Closing must not replace the body-free policy result or exception.
    }
}
