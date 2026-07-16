package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Invocation
import retrofit2.http.GET

class RetrofitInboundResponsePolicyTest {
    @Test
    fun everyOpenCodeApiMethodDeclaresAnExplicitMode() {
        val methods = OpenCodeApi::class.java.declaredMethods
            .filter { Modifier.isAbstract(it.modifiers) && !it.isSynthetic }
        val policies = methods.associate { method ->
            method.name to method.getAnnotation(RetrofitInboundResponse::class.java)?.mode
        }

        assertTrue(methods.isNotEmpty())
        assertFalse(policies.values.any { it == null })
        assertEquals(
            setOf("deleteSession", "sendPromptAsync", "sendCommand"),
            policies.filterValues { it == RetrofitInboundResponseMode.EMPTY_SUCCESS }.keys,
        )
        assertTrue(
            policies
                .filterKeys { it !in setOf("deleteSession", "sendPromptAsync", "sendCommand") }
                .values
                .all { it == RetrofitInboundResponseMode.BOUNDED },
        )
    }

    @Test
    fun missingPolicyFailsBeforeProceedWithoutSensitiveDetails() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(maxDecodedBytes = 4L)
        val request = requestFor(IncompleteOpenCodeApi::class.java.getDeclaredMethod("missingPolicy"))
        var proceedCalls = 0

        val failure = runCatching {
            interceptor.interceptRequest(request) {
                proceedCalls += 1
                response(it, 200, TrackingResponseBody(byteArrayOf(), 0L))
            }
        }.exceptionOrNull()

        assertTrue(failure is RetrofitInboundResponsePolicyMissingException)
        assertNull(failure?.message)
        assertFalse(failure.toString().contains("secret-value"))
        assertEquals(0, proceedCalls)
    }

    @Test
    fun declaredLengthAboveLimitRejectsWithoutReadingAndClosesBody() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(maxDecodedBytes = 4L)
        val request = requestFor(openCodeMethod("getGlobalHealth"))
        val body = TrackingResponseBody("12345".encodeToByteArray(), declaredLength = 5L)

        val failure = runCatching {
            interceptor.interceptRequest(request) { response(it, 200, body) }
        }.exceptionOrNull()

        assertTrue(failure is RetrofitInboundResponseTooLargeException)
        assertNull(failure?.message)
        assertEquals(0, body.trackingSource.readCalls)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun exactLimitIsReadable() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(maxDecodedBytes = 4L)
        val request = requestFor(openCodeMethod("getGlobalHealth"))
        val body = TrackingResponseBody(byteArrayOf(1, 2, 3, 4), declaredLength = -1L)
        val bounded = interceptor.interceptRequest(request) { response(it, 200, body) }

        val bytes = bounded.body.use { it.source().readByteArray() }

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun eachDelegateReadIsLimitedToRemainingPlusOne() {
        val delegate = TrackingSource(Buffer().write(byteArrayOf(1, 2, 3, 4)), readFailure = null)
        val bounded = BoundedSource(
            delegate = delegate,
            maxBytes = 4L,
            tooLargeException = ::RetrofitInboundResponseTooLargeException,
        )
        val sink = Buffer()

        assertEquals(4L, bounded.read(sink, Long.MAX_VALUE))
        assertEquals(-1L, bounded.read(sink, Long.MAX_VALUE))

        assertEquals(listOf(5L, 1L), delegate.requestedByteCounts)
        bounded.close()
        assertTrue(delegate.closed)
    }

    @Test
    fun unknownLengthFailsAtMaxPlusOneAndClosesSource() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(maxDecodedBytes = 4L)
        val request = requestFor(openCodeMethod("getGlobalHealth"))
        val body = TrackingResponseBody("12345".encodeToByteArray(), declaredLength = -1L)
        val bounded = interceptor.interceptRequest(request) { response(it, 200, body) }

        val failure = runCatching { bounded.body.source().readByteArray() }.exceptionOrNull()

        assertTrue(failure is RetrofitInboundResponseTooLargeException)
        assertNull(failure?.message)
        assertEquals(5L, body.trackingSource.bytesRead)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun understatedLengthCannotBypassStreamingLimit() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(maxDecodedBytes = 4L)
        val request = requestFor(openCodeMethod("getGlobalHealth"))
        val body = TrackingResponseBody("12345".encodeToByteArray(), declaredLength = 1L)
        val bounded = interceptor.interceptRequest(request) { response(it, 200, body) }

        val failure = runCatching { bounded.body.source().readByteArray() }.exceptionOrNull()

        assertTrue(failure is RetrofitInboundResponseTooLargeException)
        assertEquals(5L, body.trackingSource.bytesRead)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun nonSuccessfulResponseDiscardsBodyWithoutReadingAndClearsEntityHeaders() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(
            maxDecodedBytes = 4L,
            maxEncodedBytes = 7L,
        )
        val request = requestFor(openCodeMethod("getGlobalHealth"))
        val body = TrackingResponseBody(
            bytes = byteArrayOf(1),
            declaredLength = Long.MAX_VALUE,
            readFailure = AssertionError("response body was read"),
        )

        val result = interceptor.interceptRequest(request) {
            assertEquals(7L, it.tag(EncodedResponseLimit::class.java)?.maxBytes)
            response(it, 503, body)
                .newBuilder()
                .header("Content-Length", Long.MAX_VALUE.toString())
                .header("Content-Encoding", "gzip")
                .build()
        }

        assertEquals(503, result.code)
        assertEquals(0L, result.body.contentLength())
        assertNull(result.header("Content-Length"))
        assertNull(result.header("Content-Encoding"))
        assertEquals(0, body.trackingSource.readCalls)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun emptySuccessModeDiscardsSuccessfulUnitBodyWithoutReading() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(
            maxDecodedBytes = 4L,
            maxEncodedBytes = 7L,
        )
        val request = requestFor(openCodeMethod("deleteSession"))
        val body = TrackingResponseBody(
            bytes = byteArrayOf(1),
            declaredLength = Long.MAX_VALUE,
            readFailure = AssertionError("response body was read"),
        )

        val result = interceptor.interceptRequest(request) {
            assertNull(it.tag(EncodedResponseLimit::class.java))
            response(it, 200, body)
                .newBuilder()
                .header("Content-Length", Long.MAX_VALUE.toString())
                .header("Content-Encoding", "gzip")
                .build()
        }

        assertEquals(200, result.code)
        assertEquals(0L, result.body.contentLength())
        assertNull(result.header("Content-Length"))
        assertNull(result.header("Content-Encoding"))
        assertEquals(0, body.trackingSource.readCalls)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun streamingMethodIsBoundedWithoutEagerlyReading() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(maxDecodedBytes = 4L)
        val request = requestFor(openCodeMethod("getFileContent"))
        val body = TrackingResponseBody("12345".encodeToByteArray(), declaredLength = -1L)

        val bounded = interceptor.interceptRequest(request) { response(it, 200, body) }

        assertEquals(0, body.trackingSource.readCalls)
        val failure = runCatching { bounded.body.source().readByteArray() }.exceptionOrNull()
        assertTrue(failure is RetrofitInboundResponseTooLargeException)
        assertEquals(5L, body.trackingSource.bytesRead)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun requestWithoutInvocationPassesThroughUnchanged() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(maxDecodedBytes = 4L)
        val request = Request.Builder().url(TEST_URL).build()
        val body = TrackingResponseBody(byteArrayOf(1), declaredLength = Long.MAX_VALUE)
        val original = response(request, 503, body)

        val result = interceptor.interceptRequest(request) { original }

        assertSame(original, result)
        assertFalse(body.trackingSource.closed)
        assertEquals(0, body.trackingSource.readCalls)
        body.close()
    }

    @Test
    fun sourceFailurePropagatesUnchangedAndClosesSource() {
        val interceptor = RetrofitInboundResponsePolicyInterceptor(maxDecodedBytes = 4L)
        val request = requestFor(openCodeMethod("getGlobalHealth"))
        val expected = IOException("read failed")
        val body = TrackingResponseBody(byteArrayOf(1), declaredLength = -1L, readFailure = expected)
        val bounded = interceptor.interceptRequest(request) { response(it, 200, body) }

        val failure = runCatching { bounded.body.source().readByteArray() }.exceptionOrNull()

        assertSame(expected, failure)
        assertTrue(body.trackingSource.closed)
    }

    private fun openCodeMethod(name: String) =
        OpenCodeApi::class.java.declaredMethods.single { it.name == name && !it.isSynthetic }

    private fun requestFor(method: java.lang.reflect.Method): Request {
        val service = method.declaringClass
        val instance = Proxy.newProxyInstance(service.classLoader, arrayOf(service)) { _, _, _ ->
            throw UnsupportedOperationException()
        }
        @Suppress("UNCHECKED_CAST")
        val invocation = Invocation.of(
            service as Class<Any>,
            instance,
            method,
            listOf("secret-value"),
        )
        return Request.Builder()
            .url(TEST_URL)
            .tag(Invocation::class.java, invocation)
            .build()
    }

    private fun response(
        request: Request,
        code: Int,
        body: ResponseBody,
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status")
        .body(body)
        .build()

    private interface IncompleteOpenCodeApi : OpenCodeApi {
        @GET("missing")
        fun missingPolicy(): ResponseBody
    }

    private class TrackingResponseBody(
        bytes: ByteArray,
        private val declaredLength: Long,
        readFailure: Throwable? = null,
    ) : ResponseBody() {
        val trackingSource = TrackingSource(Buffer().write(bytes), readFailure)
        private val bufferedSource = trackingSource.buffer()

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = declaredLength

        override fun source(): BufferedSource = bufferedSource
    }

    private class TrackingSource(
        private val delegate: Buffer,
        private val readFailure: Throwable?,
    ) : Source {
        val requestedByteCounts = mutableListOf<Long>()
        var readCalls = 0
            private set
        var bytesRead = 0L
            private set
        var closed = false
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            readCalls += 1
            requestedByteCounts += byteCount
            readFailure?.let { throw it }
            return delegate.read(sink, byteCount).also { count ->
                if (count > 0L) bytesRead += count
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private companion object {
        const val TEST_URL = "https://example.test/path?token=secret-value"
    }
}
