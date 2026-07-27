package io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.yamux

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YamuxFrameCodecTest {
    @Test
    fun k0TracesRoundTripAcrossDeclaredChunkPlans() = runTest {
        val traces = listOf(
            TraceCase("client-to-server.trace.bin", listOf(1, 11, 2, 5), 9),
            TraceCase("server-to-client.trace.bin", listOf(1, 11, 2, 5), 9),
            TraceCase("flow-control/client-to-server.trace.bin", listOf(1, 11, 4_096, 3), 3),
            TraceCase("flow-control/server-to-client.trace.bin", listOf(1, 11, 4_096, 3), 3),
            TraceCase("reset/client-to-server.trace.bin", listOf(2, 1, 9, 4), 3),
            TraceCase("reset/server-to-client.trace.bin", listOf(2, 1, 9, 4), 3),
        )

        traces.forEach { trace ->
            val expected = loadYamuxFixture(trace.path)
            val frames = parseYamuxTrace(expected, trace.chunkSizes)

            assertEquals("Unexpected frame count for ${trace.path}", trace.frameCount, frames.size)
            assertArrayEquals("Trace did not round-trip: ${trace.path}", expected, encodeYamuxTrace(frames))
        }
    }

    @Test
    fun lifecycleTraceHasPinnedFrameSemantics() = runTest {
        val frames = parseYamuxTrace(loadYamuxFixture("client-to-server.trace.bin"))

        assertEquals(
            listOf(
                Signature(YamuxFrameType.WINDOW_UPDATE, YamuxFlags.SYN, 1L, YAMUX_FRP_WINDOW - YAMUX_INITIAL_WINDOW),
                Signature(YamuxFrameType.DATA, 0, 1L, 15L),
                Signature(YamuxFrameType.WINDOW_UPDATE, YamuxFlags.FIN, 1L, 0L),
                Signature(YamuxFrameType.WINDOW_UPDATE, YamuxFlags.ACK, 2L, YAMUX_FRP_WINDOW - YAMUX_INITIAL_WINDOW),
                Signature(YamuxFrameType.DATA, 0, 2L, 16L),
                Signature(YamuxFrameType.WINDOW_UPDATE, YamuxFlags.FIN, 2L, 0L),
                Signature(YamuxFrameType.GOAWAY, 0, 0L, 0L),
                Signature(YamuxFrameType.PING, YamuxFlags.SYN, 0L, 0L),
                Signature(YamuxFrameType.PING, YamuxFlags.ACK, 0L, 0L),
            ),
            frames.map { frame ->
                Signature(frame.header.type, frame.header.flags, frame.header.streamId, frame.header.length)
            },
        )
        assertArrayEquals("client-odd-data".encodeToByteArray(), frames[1].body)
        assertArrayEquals("client-even-data".encodeToByteArray(), frames[4].body)
    }

    @Test
    fun unknownTypeMutationIsRejectedWithoutReportingPayload() = runTest {
        val marker = "client-odd-data"
        val mutated = applyYamuxManifestMutation("yamux-unknown-type")
        val transport = YamuxTestTransport().also {
            it.feed(mutated)
            it.finishInput()
        }

        val failure = suspendFailureOf { YamuxFrameCodec.readHeaderOrNull(transport) }

        assertTrue(failure is YamuxProtocolFailure)
        assertEquals(YamuxProtocolError.UNKNOWN_TYPE, (failure as YamuxProtocolFailure).reason)
        assertFalse(failure.toString().contains(marker))
        assertEquals(null, failure.cause)
    }

    @Test
    fun k0DataBitFlipRemainsAValidUnauthenticatedPayload() = runTest {
        val mutated = applyYamuxManifestMutation("yamux-data-bit-flip")

        val frames = parseYamuxTrace(mutated, listOf(1, 11, 2, 5))

        assertEquals(9, frames.size)
        assertArrayEquals(mutated, encodeYamuxTrace(frames))
        assertFalse(frames[1].body.contentEquals("client-odd-data".encodeToByteArray()))
    }

    @Test
    fun truncatedHeaderAndBodyUseTypedFailures() = runTest {
        val headerTransport = YamuxTestTransport().also {
            it.feed(ByteArray(YamuxProtocol.HEADER_SIZE - 1))
            it.finishInput()
        }
        val headerFailure = suspendFailureOf { YamuxFrameCodec.readHeaderOrNull(headerTransport) }
        assertTrue(headerFailure is YamuxTruncatedFrameFailure)
        assertEquals(YamuxFrameSection.HEADER, (headerFailure as YamuxTruncatedFrameFailure).section)

        val bodyTransport = YamuxTestTransport().also {
            it.feed(ByteArray(7))
            it.finishInput()
        }
        val bodyFailure = suspendFailureOf {
            YamuxFrameCodec.readDataSegments(bodyTransport, length = 8L, maximumLength = 8, segmentSize = 3)
        }
        assertTrue(bodyFailure is YamuxTruncatedFrameFailure)
        assertEquals(YamuxFrameSection.DATA, (bodyFailure as YamuxTruncatedFrameFailure).section)
    }

    @Test
    fun zeroLengthStreamFramesWithoutFlagsRemainWireCompatible() {
        listOf(YamuxFrameType.DATA, YamuxFrameType.WINDOW_UPDATE).forEach { type ->
            val expected = YamuxFrameHeader(type, flags = 0, streamId = 1L, length = 0L)
            assertEquals(expected, YamuxFrameCodec.decodeHeader(YamuxFrameCodec.encodeHeader(expected)))
        }
    }

    @Test
    fun oversizedDataIsRejectedBeforeAnyTransportRead() = runTest {
        val transport = YamuxTestTransport()

        val failure = suspendFailureOf {
            YamuxFrameCodec.readDataSegments(
                transport = transport,
                length = 257L,
                maximumLength = 256,
                segmentSize = 16,
            )
        }

        assertTrue(failure is YamuxProtocolFailure)
        assertEquals(YamuxProtocolError.DATA_FRAME_TOO_LARGE, (failure as YamuxProtocolFailure).reason)
    }

    @Test
    fun zeroLengthTransportReadIsAContractFailure() = runTest {
        val transport = object : YamuxPhysicalTransport {
            override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int = 0

            override suspend fun writeFully(source: ByteArray, offset: Int, length: Int) = Unit

            override fun close() = Unit
        }

        val failure = suspendFailureOf { YamuxFrameCodec.readHeaderOrNull(transport) }

        assertTrue(failure is YamuxTransportFailure)
        assertEquals(YamuxTransportOperation.READ_CONTRACT, (failure as YamuxTransportFailure).operation)
    }

    @Test
    fun inboundAndOutboundFrameLimitsAreIndependentAndBounded() = runTest {
        assertEquals(YAMUX_INITIAL_WINDOW.toInt(), YamuxConfig().maxDataFrameSize)
        assertEquals(16 * 1024, YamuxConfig().outboundDataFrameSize)
        assertEquals(
            YAMUX_FRP_WINDOW.toInt(),
            YamuxConfig(receiveWindowSize = YAMUX_FRP_WINDOW).maxDataFrameSize,
        )

        val fixtureBytes = loadYamuxFixture("flow-control/client-to-server.trace.bin")
        assertEquals(
            3,
            parseYamuxTrace(
                fixtureBytes,
                maximumDataFrameSize = (YAMUX_INITIAL_WINDOW / 2L).toInt(),
            ).size,
        )
        assertTrue(
            suspendFailureOf {
                parseYamuxTrace(
                    ByteArray((1 shl 20) + 1),
                    maximumDataFrameSize = YAMUX_FRP_WINDOW.toInt(),
                )
            } is IllegalArgumentException,
        )

        val failure = failureOf {
            YamuxConfig(receiveWindowSize = YAMUX_FRP_WINDOW, maxDataFrameSize = YAMUX_INITIAL_WINDOW.toInt())
        }
        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failureOf {
                YamuxConfig(
                    outboundDataFrameSize = 32 * 1024,
                    writerQueuedByteCapacity = 16 * 1024L,
                )
            } is IllegalArgumentException,
        )
        assertTrue(
            failureOf {
                YamuxConfig(keepAliveEnabled = false, keepAliveTimeoutMillis = 0L)
            } is IllegalArgumentException,
        )
    }

    @Test
    fun receiveBufferCoalescesTinyFramesIntoBoundedSixteenKiBBlocks() {
        val buffer = YamuxReceiveBuffer()
        val tinyFrameCount = 100_000
        repeat(tinyFrameCount) { index ->
            buffer.appendFrame(
                YamuxPayloadSegments(
                    segments = listOf(byteArrayOf(index.toByte())),
                    length = 1,
                ),
            )
        }
        assertEquals(
            (tinyFrameCount + YamuxReceiveBuffer.BLOCK_SIZE - 1) / YamuxReceiveBuffer.BLOCK_SIZE,
            buffer.blockCount,
        )

        val maximumBufferedBytes = 8 * 1024 * 1024
        var remaining = maximumBufferedBytes - tinyFrameCount
        val chunk = ByteArray(YamuxReceiveBuffer.BLOCK_SIZE) { index -> index.toByte() }
        while (remaining > 0) {
            val count = minOf(remaining, chunk.size)
            buffer.appendFrame(
                YamuxPayloadSegments(
                    segments = listOf(chunk.copyOf(count)),
                    length = count,
                ),
            )
            remaining -= count
        }

        assertEquals(maximumBufferedBytes.toLong(), buffer.byteCount)
        assertEquals(maximumBufferedBytes / YamuxReceiveBuffer.BLOCK_SIZE, buffer.blockCount)

        val destination = ByteArray(32 * 1024)
        while (buffer.byteCount > 0L) {
            assertTrue(buffer.read(destination, 0, destination.size) > 0)
        }
        assertEquals(0, buffer.blockCount)
    }

    @Test
    fun eightMiBPayloadUsesABoundedNumberOfTemporarySegments() = runTest {
        val length = 8 * 1024 * 1024
        val transport = YamuxTestTransport().also { it.feed(ByteArray(length)) }

        val payload = YamuxFrameCodec.readDataSegments(
            transport = transport,
            length = length.toLong(),
            maximumLength = length,
            segmentSize = 1,
        )

        assertEquals(length, payload.length)
        assertTrue(payload.segments.size <= YamuxFrameCodec.MAX_PAYLOAD_SEGMENTS)
        assertEquals(YamuxFrameCodec.MAX_PAYLOAD_SEGMENTS, payload.segments.size)
    }

    private data class TraceCase(
        val path: String,
        val chunkSizes: List<Int>,
        val frameCount: Int,
    )

    private data class Signature(
        val type: YamuxFrameType,
        val flags: Int,
        val streamId: Long,
        val length: Long,
    )

    private fun failureOf(block: () -> Unit): Throwable =
        runCatching(block).exceptionOrNull() ?: throw AssertionError("Expected failure")

    private suspend fun suspendFailureOf(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (failure: Throwable) {
            return failure
        }
        throw AssertionError("Expected failure")
    }
}
