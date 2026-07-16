package io.github.ycfeng.ocdeck.core.network

import okio.Buffer
import okio.ForwardingSource
import okio.Source
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BoundedSseReaderTest {
    @Test
    fun parsesBomCommentsFieldsMixedLineEndingsAndMultilineData() {
        val source = TrackingSource(
            Buffer().writeUtf8(
                "\uFEFF: comment\r\n" +
                    "id: 7\r\n" +
                    "event: update\n" +
                    "data: first\r" +
                    "data:second\r\n" +
                    "retry: 1\n\n",
            ),
        )
        val events = mutableListOf<BoundedSseEvent>()

        BoundedSseReader(maxLineBytes = 64L, maxEventDataBytes = 64L).read(source, events::add)

        assertEquals(listOf(BoundedSseEvent("7", "update", "first\nsecond")), events)
        assertTrue(source.closed)
    }

    @Test
    fun eofWithoutLineEndingDropsPendingLineAndEvent() {
        val source = TrackingSource(Buffer().writeUtf8("data: tail"))
        val events = mutableListOf<BoundedSseEvent>()

        BoundedSseReader(maxLineBytes = 32L, maxEventDataBytes = 16L).read(source, events::add)

        assertTrue(events.isEmpty())
        assertTrue(source.closed)
    }

    @Test
    fun eofAfterSingleLineEndingDropsPendingEvent() {
        val source = TrackingSource(Buffer().writeUtf8("data: tail\n"))
        val events = mutableListOf<BoundedSseEvent>()

        BoundedSseReader(maxLineBytes = 32L, maxEventDataBytes = 16L).read(source, events::add)

        assertTrue(events.isEmpty())
        assertTrue(source.closed)
    }

    @Test
    fun completeEmptyLineDispatchesEventBeforeEof() {
        val source = TrackingSource(Buffer().writeUtf8("data: tail\n\n"))
        val events = mutableListOf<BoundedSseEvent>()

        BoundedSseReader(maxLineBytes = 32L, maxEventDataBytes = 16L).read(source, events::add)

        assertEquals(listOf(BoundedSseEvent(null, null, "tail")), events)
        assertTrue(source.closed)
    }

    @Test
    fun parsesCrLfWhenEachByteArrivesInADifferentChunk() {
        val source = TrackingSource(Buffer().writeUtf8("data: value\r\n\r\n"))
        val events = mutableListOf<BoundedSseEvent>()

        BoundedSseReader(
            maxLineBytes = 32L,
            maxEventDataBytes = 16L,
            readBufferBytes = 1,
        ).read(source, events::add)

        assertEquals(listOf(BoundedSseEvent(null, null, "value")), events)
        assertTrue(source.closed)
    }

    @Test
    fun allowsPhysicalLineExactlyAtLimit() {
        val source = TrackingSource(Buffer().writeUtf8(":123\n"))

        BoundedSseReader(maxLineBytes = 4L, maxEventDataBytes = 4L).read(source) {
            fail("Comment must not dispatch an event")
        }

        assertTrue(source.closed)
    }

    @Test
    fun allowsEventDataExactlyAtUtf8ByteLimit() {
        val source = TrackingSource(Buffer().writeUtf8("data: \u00E9\u00E9\n\n"))
        val events = mutableListOf<BoundedSseEvent>()

        BoundedSseReader(maxLineBytes = 16L, maxEventDataBytes = 4L).read(source, events::add)

        assertEquals("\u00E9\u00E9", events.single().data)
        assertTrue(source.closed)
    }

    @Test
    fun malformedUtf8UsesOriginalWireBytesAtEventBoundary() {
        val source = TrackingSource(malformedUtf8DataEvent())
        val events = mutableListOf<BoundedSseEvent>()

        BoundedSseReader(maxLineBytes = 16L, maxEventDataBytes = 3L).read(source, events::add)

        assertEquals("\uFFFD\n\uFFFD", events.single().data)
        assertTrue(source.closed)
    }

    @Test
    fun malformedUtf8AboveOriginalWireBoundaryFailsBeforeDispatch() {
        val source = TrackingSource(malformedUtf8DataEvent())
        var callbackCount = 0

        val failure = runCatching {
            BoundedSseReader(maxLineBytes = 16L, maxEventDataBytes = 2L).read(source) {
                callbackCount += 1
            }
        }.exceptionOrNull()

        assertTrue(failure is SseEventDataTooLargeException)
        assertEquals(0, callbackCount)
        assertTrue(source.closed)
    }

    @Test
    fun oversizedUnterminatedLineConsumesOnlyLimitPlusOneWithoutCreatingEvent() {
        val source = InfiniteSource()
        var callbackCount = 0

        val failure = runCatching {
            BoundedSseReader(maxLineBytes = 4L, maxEventDataBytes = 16L).read(source) {
                callbackCount += 1
            }
        }.exceptionOrNull()

        assertTrue(failure is SseLineTooLargeException)
        assertEquals(5L, source.bytesRead)
        assertEquals(0, callbackCount)
        assertTrue(source.closed)
    }

    @Test
    fun cumulativeEventDataAboveLimitFailsBeforeDispatch() {
        val source = TrackingSource(Buffer().writeUtf8("data: ab\ndata: cd\n\n"))
        var callbackCount = 0

        val failure = runCatching {
            BoundedSseReader(maxLineBytes = 16L, maxEventDataBytes = 4L).read(source) {
                callbackCount += 1
            }
        }.exceptionOrNull()

        assertTrue(failure is SseEventDataTooLargeException)
        assertEquals(0, callbackCount)
        assertTrue(source.closed)
    }

    @Test
    fun callbackExceptionPropagatesAndClosesSource() {
        val source = TrackingSource(Buffer().writeUtf8("data: value\n\n"))
        val expected = IllegalStateException("callback failed")

        try {
            BoundedSseReader(maxLineBytes = 32L, maxEventDataBytes = 16L).read(source) {
                throw expected
            }
            fail("Callback exception was not propagated")
        } catch (failure: IllegalStateException) {
            assertTrue(failure === expected)
            assertTrue(source.closed)
        }
    }

    private fun malformedUtf8DataEvent(): Buffer = Buffer().apply {
        writeUtf8("data: ")
        writeByte(0x80)
        writeByte('\n'.code)
        writeUtf8("data: ")
        writeByte(0x80)
        writeUtf8("\n\n")
    }

    private class TrackingSource(delegate: Source) : ForwardingSource(delegate) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class InfiniteSource : Source {
        var bytesRead = 0L
            private set
        var closed = false
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            repeat(byteCount.toInt()) { sink.writeByte(0) }
            bytesRead += byteCount
            return byteCount
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
        }
    }
}
