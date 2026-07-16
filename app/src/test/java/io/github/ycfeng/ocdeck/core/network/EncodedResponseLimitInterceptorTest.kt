package io.github.ycfeng.ocdeck.core.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Invocation

class EncodedResponseLimitInterceptorTest {
    @Test
    fun encodedOverLimitDecodedSmallFailsBeforeBridgeDecoding() {
        val decoded = "[]".encodeToByteArray()
        val encoded = gzip(decoded)
        assertTrue(encoded.size > decoded.size)

        withHttpResponse(
            body = encoded,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Content-Encoding" to "gzip",
                "Content-Length" to encoded.size.toString(),
            ),
        ) { url ->
            val failure = execute(
                url = url,
                maxDecodedBytes = decoded.size.toLong(),
                maxEncodedBytes = encoded.size.toLong() - 1L,
            )

            val boundary = failure.causeChain().filterIsInstance<EncodedResponseTooLargeException>().single()
            assertNull(boundary.message)
            assertFalse(failure.toString().contains(url.toString()))
        }
    }

    @Test
    fun encodedSmallDecodedOverLimitFailsAtDecodedBoundary() {
        val decoded = ByteArray(4_096) { 'a'.code.toByte() }
        val encoded = gzip(decoded)
        assertTrue(encoded.size < 128)

        withHttpResponse(
            body = encoded,
            headers = mapOf(
                "Content-Type" to "application/json",
                "Content-Encoding" to "gzip",
                "Content-Length" to encoded.size.toString(),
            ),
        ) { url ->
            val failure = execute(
                url = url,
                maxDecodedBytes = 128L,
                maxEncodedBytes = encoded.size.toLong(),
            )

            assertTrue(failure.causeChain().any { it is RetrofitInboundResponseTooLargeException })
            assertFalse(failure.causeChain().any { it is EncodedResponseTooLargeException })
        }
    }

    @Test
    fun unknownEncodedLengthFailsAtMaxPlusOneOnRealOkHttpChain() {
        withHttpResponse(
            body = "12345".encodeToByteArray(),
            headers = mapOf("Content-Type" to "application/json"),
        ) { url ->
            val failure = execute(
                url = url,
                maxDecodedBytes = 8L,
                maxEncodedBytes = 4L,
            )

            assertTrue(failure.causeChain().any { it is EncodedResponseTooLargeException })
        }
    }

    @Test
    fun understatedEncodedLengthCannotBypassStreamingLimit() {
        val request = Request.Builder()
            .url(TEST_URL)
            .tag(EncodedResponseLimit::class.java, EncodedResponseLimit(4L))
            .build()
        val body = TrackingResponseBody("12345".encodeToByteArray(), declaredLength = 1L)
        val interceptor = EncodedResponseLimitInterceptor()
        val response = interceptor.interceptRequest(request) {
            response(it, code = 200, body = body)
        }

        val failure = runCatching { response.body.source().readByteArray() }.exceptionOrNull()

        assertTrue(failure is EncodedResponseTooLargeException)
        assertEquals(5L, body.trackingSource.bytesRead)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun successfulResponsePreservesEncodingHeadersForBridge() {
        val request = Request.Builder()
            .url(TEST_URL)
            .tag(EncodedResponseLimit::class.java, EncodedResponseLimit(4L))
            .build()
        val body = TrackingResponseBody("12".encodeToByteArray(), declaredLength = 2L)
        val original = response(request, code = 200, body = body).newBuilder()
            .header("Content-Encoding", "gzip")
            .header("Content-Length", "2")
            .build()

        val result = EncodedResponseLimitInterceptor().interceptRequest(request) { original }

        assertEquals("gzip", result.header("Content-Encoding"))
        assertEquals("2", result.header("Content-Length"))
        assertEquals(0, body.trackingSource.readCalls)
        result.close()
    }

    @Test
    fun nonSuccessfulResponseBypassesEncodedBoundaryWithoutReading() {
        val request = Request.Builder()
            .url(TEST_URL)
            .tag(EncodedResponseLimit::class.java, EncodedResponseLimit(4L))
            .build()
        val body = TrackingResponseBody(
            bytes = byteArrayOf(1),
            declaredLength = Long.MAX_VALUE,
            readFailure = AssertionError("body was read"),
        )
        val original = response(request, code = 503, body = body)

        val result = EncodedResponseLimitInterceptor().interceptRequest(request) { original }

        assertSame(original, result)
        assertEquals(0, body.trackingSource.readCalls)
        assertFalse(body.trackingSource.closed)
        result.close()
    }

    private fun execute(
        url: HttpUrl,
        maxDecodedBytes: Long,
        maxEncodedBytes: Long,
    ): Throwable {
        val client = OkHttpClient.Builder()
            .addInterceptor(
                RetrofitInboundResponsePolicyInterceptor(
                    maxDecodedBytes = maxDecodedBytes,
                    maxEncodedBytes = maxEncodedBytes,
                ),
            )
            .addNetworkInterceptor(EncodedResponseLimitInterceptor())
            .build()
        val failure = runCatching {
            client.newCall(requestFor(url)).execute().use { response ->
                response.body.source().readByteArray()
            }
        }.exceptionOrNull()
        return checkNotNull(failure)
    }

    private fun requestFor(url: HttpUrl): Request {
        val method = OpenCodeApi::class.java.declaredMethods
            .single { it.name == "getGlobalHealth" && !it.isSynthetic }
        val service = method.declaringClass
        val instance = Proxy.newProxyInstance(service.classLoader, arrayOf(service)) { _, _, _ ->
            throw UnsupportedOperationException()
        }
        @Suppress("UNCHECKED_CAST")
        val invocation = Invocation.of(
            service as Class<Any>,
            instance,
            method,
            emptyList<Any>(),
        )
        return Request.Builder()
            .url(url)
            .tag(Invocation::class.java, invocation)
            .build()
    }

    private fun withHttpResponse(
        body: ByteArray,
        headers: Map<String, String>,
        block: (HttpUrl) -> Unit,
    ) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val serverResult = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 5_000
                consumeRequestHeaders(socket.getInputStream())
                socket.getOutputStream().use { output ->
                    output.write("HTTP/1.1 200 OK\r\n".encodeToByteArray())
                    headers.forEach { (name, value) ->
                        output.write("$name: $value\r\n".encodeToByteArray())
                    }
                    output.write("Connection: close\r\n\r\n".encodeToByteArray())
                    output.write(body)
                    output.flush()
                }
            }
        }

        try {
            block("http://127.0.0.1:${server.localPort}/response".toHttpUrl())
            serverResult.get(5, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    private fun consumeRequestHeaders(input: InputStream) {
        var matched = 0
        val delimiter = byteArrayOf(
            '\r'.code.toByte(),
            '\n'.code.toByte(),
            '\r'.code.toByte(),
            '\n'.code.toByte(),
        )
        while (matched < delimiter.size) {
            val value = input.read()
            check(value >= 0)
            matched = if (value.toByte() == delimiter[matched]) matched + 1 else 0
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip -> gzip.write(bytes) }
        output.toByteArray()
    }

    private fun response(request: Request, code: Int, body: ResponseBody): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("status")
        .body(body)
        .build()

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
        delegate: Source,
        private val readFailure: Throwable?,
    ) : ForwardingSource(delegate) {
        var readCalls = 0
            private set
        var bytesRead = 0L
            private set
        var closed = false
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            readCalls += 1
            readFailure?.let { throw it }
            return super.read(sink, byteCount).also { count ->
                if (count > 0L) bytesRead += count
            }
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val TEST_URL = "https://example.test/response"
    }
}
