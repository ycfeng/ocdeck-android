package io.github.ycfeng.ocdeck.feature.session

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedAttachmentReaderTest {
    @Test
    fun unknownSizeReadsPayloadBelowLimit() = runTest {
        val input = CloseTrackingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)))

        val result = readBoundedAttachmentBytes(input, maxBytes = 4L, declaredSize = null)

        assertArrayEquals(byteArrayOf(1, 2, 3), (result as BoundedAttachmentReadResult.Success).bytes)
        assertTrue(input.closed)
    }

    @Test
    fun unknownSizeAllowsPayloadExactlyAtLimit() = runTest {
        val input = CloseTrackingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))

        val result = readBoundedAttachmentBytes(input, maxBytes = 4L, declaredSize = null)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), (result as BoundedAttachmentReadResult.Success).bytes)
        assertTrue(input.closed)
    }

    @Test
    fun unknownSizeStopsAtOneByteOverLimit() = runTest {
        val input = CloseTrackingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))

        val result = readBoundedAttachmentBytes(input, maxBytes = 4L, declaredSize = null)

        assertEquals(BoundedAttachmentReadResult.TooLarge, result)
        assertTrue(input.closed)
    }

    @Test
    fun lowDeclaredSizeDoesNotBypassActualLimit() = runTest {
        val input = CloseTrackingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))

        val result = readBoundedAttachmentBytes(input, maxBytes = 4L, declaredSize = 1L)

        assertEquals(BoundedAttachmentReadResult.TooLarge, result)
        assertTrue(input.closed)
    }

    @Test
    fun infiniteStreamReadsAtMostMaxPlusOneBytes() = runTest {
        val input = InfiniteInputStream()

        val result = readBoundedAttachmentBytes(input, maxBytes = 32L)

        assertEquals(BoundedAttachmentReadResult.TooLarge, result)
        assertEquals(33L, input.bytesRead)
        assertTrue(input.closed)
    }

    @Test
    fun zeroLengthBulkReadFallsBackWithoutBusyLoop() = runTest {
        val input = ZeroThenDataInputStream(byteArrayOf(7, 8, 9))

        val result = readBoundedAttachmentBytes(input, maxBytes = 3L)

        assertArrayEquals(byteArrayOf(7, 8, 9), (result as BoundedAttachmentReadResult.Success).bytes)
        assertEquals(1, input.zeroReadCount)
        assertTrue(input.closed)
    }

    @Test
    fun ioExceptionReturnsReadFailedAndClosesStream() = runTest {
        val input = ThrowingInputStream()

        val result = readBoundedAttachmentBytes(input, maxBytes = 4L)

        assertEquals(BoundedAttachmentReadResult.ReadFailed, result)
        assertTrue(input.closed)
    }

    @Test
    fun cancellationPropagatesAndClosesStream() = runTest {
        val input = CloseTrackingInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3)))

        try {
            readBoundedAttachmentBytes(
                input = input,
                maxBytes = 4L,
                cancellationCheck = { throw CancellationException("cancelled") },
            )
            fail("CancellationException was not propagated")
        } catch (_: CancellationException) {
            assertTrue(input.closed)
        }
    }

    @Test
    fun errorPropagatesAndClosesStream() = runTest {
        val input = ErrorInputStream()

        try {
            readBoundedAttachmentBytes(input, maxBytes = 4L)
            fail("Error was not propagated")
        } catch (_: AssertionError) {
            assertTrue(input.closed)
        }
    }

    private class CloseTrackingInputStream(delegate: InputStream) : FilterInputStream(delegate) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
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

    private class ZeroThenDataInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        var zeroReadCount = 0
            private set
        var closed = false
            private set

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (zeroReadCount == 0) {
                zeroReadCount += 1
                return 0
            }
            return delegate.read(buffer, offset, length)
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class ThrowingInputStream : InputStream() {
        var closed = false
            private set

        override fun read(): Int = throw IOException("read failed")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw IOException("read failed")

        override fun close() {
            closed = true
        }
    }

    private class ErrorInputStream : InputStream() {
        var closed = false
            private set

        override fun read(): Int = throw AssertionError("fatal read failure")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw AssertionError("fatal read failure")

        override fun close() {
            closed = true
        }
    }
}
