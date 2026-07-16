package io.github.ycfeng.ocdeck.core.network

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InboundPayloadReaderTest {
    @Test
    fun declaredLengthAboveLimitRejectsWithoutReadingAndClosesBody() {
        val body = TrackingResponseBody("12345".encodeToByteArray(), declaredLength = 5L)

        val failure = runCatching {
            readBoundedResponseBody(body, maxBytes = 4L, ::SessionMessagesResponseTooLargeException)
        }.exceptionOrNull()

        assertTrue(failure is SessionMessagesResponseTooLargeException)
        assertNull(failure?.message)
        assertEquals(0, body.trackingSource.readCalls)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun unknownLengthReadsSmallPayloadAndClosesBody() {
        val body = TrackingResponseBody(byteArrayOf(1, 2, 3), declaredLength = -1L)

        val bytes = readBoundedResponseBody(body, maxBytes = 4L, ::SessionMessagesResponseTooLargeException)

        assertArrayEquals(byteArrayOf(1, 2, 3), bytes)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun unknownLengthAllowsPayloadExactlyAtLimit() {
        val body = TrackingResponseBody(byteArrayOf(1, 2, 3, 4), declaredLength = -1L)

        val bytes = readBoundedResponseBody(body, maxBytes = 4L, ::SessionMessagesResponseTooLargeException)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun limitPlusOneStopsAfterProbeByteAndClosesInput() {
        val input = InfiniteInputStream()

        val failure = runCatching {
            readBoundedBytes(input, maxBytes = 4L, ::SessionMessagesResponseTooLargeException)
        }.exceptionOrNull()

        assertTrue(failure is SessionMessagesResponseTooLargeException)
        assertEquals(5L, input.bytesRead)
        assertTrue(input.closed)
    }

    @Test
    fun understatedLengthCannotBypassStreamingLimit() {
        val body = TrackingResponseBody("12345".encodeToByteArray(), declaredLength = 1L)

        val failure = runCatching {
            readBoundedResponseBody(body, maxBytes = 4L, ::SessionMessagesResponseTooLargeException)
        }.exceptionOrNull()

        assertTrue(failure is SessionMessagesResponseTooLargeException)
        assertEquals(5L, body.trackingSource.bytesRead)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun readIOExceptionPropagatesAndClosesBody() {
        val failure = IOException("read failed")
        val body = TrackingResponseBody(byteArrayOf(1), declaredLength = -1L, readFailure = failure)

        val thrown = runCatching {
            readBoundedResponseBody(body, maxBytes = 4L, ::SessionMessagesResponseTooLargeException)
        }.exceptionOrNull()

        assertTrue(thrown === failure)
        assertTrue(body.trackingSource.closed)
    }

    @Test
    fun cancellationPropagatesAndClosesInput() {
        val input = CloseTrackingInputStream(ByteArrayInputStream(byteArrayOf(1)))

        try {
            readBoundedBytes(
                input = input,
                maxBytes = 4L,
                tooLargeException = ::SessionMessagesResponseTooLargeException,
                cancellationCheck = { throw CancellationException("cancelled") },
            )
            fail("CancellationException was not propagated")
        } catch (_: CancellationException) {
            assertTrue(input.closed)
        }
    }

    @Test
    fun errorPropagatesAndClosesBody() {
        val failure = AssertionError("fatal")
        val body = TrackingResponseBody(byteArrayOf(1), declaredLength = -1L, readFailure = failure)

        try {
            readBoundedResponseBody(body, maxBytes = 4L, ::SessionMessagesResponseTooLargeException)
            fail("Error was not propagated")
        } catch (thrown: AssertionError) {
            assertTrue(thrown === failure)
            assertTrue(body.trackingSource.closed)
        }
    }

    private class TrackingResponseBody(
        bytes: ByteArray,
        private val declaredLength: Long,
        readFailure: Throwable? = null,
    ) : ResponseBody() {
        val trackingSource = TrackingSource(bytes, readFailure)
        private val bufferedSource = trackingSource.buffer()

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = declaredLength

        override fun source(): BufferedSource = bufferedSource
    }

    private class TrackingSource(
        bytes: ByteArray,
        private val readFailure: Throwable?,
    ) : Source {
        private val data = Buffer().write(bytes)
        var readCalls = 0
            private set
        var bytesRead = 0L
            private set
        var closed = false
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            readCalls += 1
            readFailure?.let { throw it }
            return data.read(sink, byteCount).also { count ->
                if (count > 0L) bytesRead += count
            }
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }

    private class InfiniteInputStream : InputStream() {
        var bytesRead = 0L
            private set
        var closed = false
            private set

        override fun read(): Int {
            bytesRead += 1L
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            buffer.fill(0, offset, offset + length)
            bytesRead += length.toLong()
            return length
        }

        override fun close() {
            closed = true
        }
    }

    private class CloseTrackingInputStream(delegate: InputStream) : java.io.FilterInputStream(delegate) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}
