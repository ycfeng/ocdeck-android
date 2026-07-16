package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FileContentResponseReaderTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun readsContentWithinLimit() {
        val body = """{"type":"text","content":"hello"}""".toResponseBody()

        val content = body.readFileContentDto(json, maxBytes = 128)

        assertEquals("text", content.type)
        assertEquals("hello", content.content)
    }

    @Test
    fun rejectsDeclaredContentLengthAboveLimit() {
        val failure = runCatching {
            "12345".toResponseBody().readFileContentDto(json, maxBytes = 4)
        }.exceptionOrNull()

        assertTrue(failure is FileContentResponseTooLargeException)
    }

    @Test
    fun rejectsStreamedContentAboveLimitWhenLengthIsUnknown() {
        val failure = runCatching {
            UnknownLengthResponseBody("12345".encodeToByteArray())
                .readFileContentDto(json, maxBytes = 4)
        }.exceptionOrNull()

        assertTrue(failure is FileContentResponseTooLargeException)
    }

    @Test
    fun fileReaderPreservesUnifiedInboundLimitFailure() {
        val expected = RetrofitInboundResponseTooLargeException()

        val failure = runCatching {
            ThrowingResponseBody(expected).readFileContentDto(json, maxBytes = 128)
        }.exceptionOrNull()

        assertSame(expected, failure)
        assertTrue(failure is InboundPayloadTooLargeException)
    }

    private class UnknownLengthResponseBody(
        private val bytes: ByteArray,
    ) : ResponseBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = Buffer().write(bytes)
    }

    private class ThrowingResponseBody(
        private val failure: IOException,
    ) : ResponseBody() {
        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long = throw failure

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()
    }
}
