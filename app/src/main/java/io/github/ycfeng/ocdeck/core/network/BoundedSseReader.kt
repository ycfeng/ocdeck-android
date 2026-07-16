package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import okio.Buffer
import okio.Source

internal data class BoundedSseEvent(
    val id: String?,
    val type: String?,
    val data: String,
) {
    override fun toString(): String =
        "BoundedSseEvent(id=${if (id == null) "null" else "<redacted>"}, " +
            "typePresent=${type != null}, dataLength=${data.length})"
}

internal class BoundedSseReader(
    private val maxLineBytes: Long = InboundPayloadLimits.SSE_LINE_BYTES,
    private val maxEventDataBytes: Long = InboundPayloadLimits.SSE_EVENT_DATA_BYTES,
    private val readBufferBytes: Int = InboundPayloadLimits.READ_BUFFER_BYTES,
) {
    init {
        require(maxLineBytes in 0..Int.MAX_VALUE.toLong()) { "SSE line limit is out of range" }
        require(maxEventDataBytes in 0..Int.MAX_VALUE.toLong()) { "SSE event limit is out of range" }
        require(readBufferBytes > 0) { "SSE read buffer must be positive" }
    }

    fun read(source: Source, onEvent: (BoundedSseEvent) -> Unit) {
        source.use { stream ->
            val eventAssembler = SseEventAssembler(maxEventDataBytes)
            val lineBuffer = Buffer()
            val sourceBuffer = Buffer()
            val readBuffer = ByteArray(readBufferBytes)
            var lineBytes = 0L
            var firstLine = true
            var skipLfAfterCr = false

            fun appendLineBytes(offset: Int, byteCount: Int) {
                if (byteCount == 0) return
                if (byteCount.toLong() > maxLineBytes - lineBytes) throw SseLineTooLargeException()
                lineBuffer.write(readBuffer, offset, byteCount)
                lineBytes += byteCount.toLong()
            }

            fun finishLine() {
                val stripBom = firstLine && lineBuffer.startsWithUtf8Bom()
                val dataValueWireBytes = lineBuffer.sseDataValueWireBytes(stripBom)
                var line = lineBuffer.readString(Charsets.UTF_8)
                lineBytes = 0L
                if (firstLine) {
                    firstLine = false
                    if (stripBom) line = line.substring(1)
                }
                eventAssembler.acceptLine(line, dataValueWireBytes, onEvent)
            }

            while (true) {
                val remainingBeforeOverflow = maxLineBytes - lineBytes + 1L
                val requested = minOf(readBuffer.size.toLong(), remainingBeforeOverflow)
                val count = stream.read(sourceBuffer, requested)
                if (count < 0L) break
                if (count == 0L || count > requested) throw IOException()

                val countInt = count.toInt()
                if (sourceBuffer.size != count) throw IOException()
                if (sourceBuffer.read(readBuffer, 0, countInt) != countInt) throw IOException()

                var index = 0
                if (skipLfAfterCr) {
                    skipLfAfterCr = false
                    if (readBuffer[0] == LF) index = 1
                }
                var segmentStart = index
                while (index < countInt) {
                    when (readBuffer[index]) {
                        LF -> {
                            appendLineBytes(segmentStart, index - segmentStart)
                            finishLine()
                            index += 1
                            segmentStart = index
                        }
                        CR -> {
                            appendLineBytes(segmentStart, index - segmentStart)
                            finishLine()
                            index += 1
                            if (index < countInt && readBuffer[index] == LF) {
                                index += 1
                            } else if (index == countInt) {
                                skipLfAfterCr = true
                            }
                            segmentStart = index
                        }
                        else -> index += 1
                    }
                }
                appendLineBytes(segmentStart, countInt - segmentStart)
            }

            // The event stream grammar dispatches only on an empty line. EOF discards an
            // unterminated line and any event that was not followed by a complete empty line.
        }
    }

    private companion object {
        const val LF: Byte = 0x0A
        const val CR: Byte = 0x0D
    }
}

private class SseEventAssembler(
    private val maxEventDataBytes: Long,
) {
    private var data = StringBuilder()
    private var dataBytes = 0L
    private var hasData = false
    private var eventType: String? = null
    private var lastEventId: String? = null

    fun acceptLine(
        line: String,
        dataValueWireBytes: Long?,
        onEvent: (BoundedSseEvent) -> Unit,
    ) {
        if (line.isEmpty()) {
            dispatch(onEvent)
            return
        }
        if (line[0] == ':') return

        val separator = line.indexOf(':')
        val field = if (separator >= 0) line.substring(0, separator) else line
        var value = if (separator >= 0) line.substring(separator + 1) else ""
        if (value.startsWith(' ')) value = value.substring(1)

        when (field) {
            "data" -> appendData(value, checkNotNull(dataValueWireBytes))
            "event" -> eventType = value
            "id" -> if ('\u0000' !in value) lastEventId = value
            // The server-provided retry field cannot override the client's bounded backoff policy.
            "retry" -> Unit
        }
    }

    fun dispatch(onEvent: (BoundedSseEvent) -> Unit) {
        if (!hasData) {
            eventType = null
            return
        }

        val event = BoundedSseEvent(
            id = lastEventId,
            type = eventType?.takeIf(String::isNotEmpty),
            data = data.toString(),
        )
        data = StringBuilder()
        dataBytes = 0L
        hasData = false
        eventType = null
        onEvent(event)
    }

    private fun appendData(value: String, valueWireBytes: Long) {
        val separatorBytes = if (hasData) 1L else 0L
        val remaining = maxEventDataBytes - dataBytes
        if (separatorBytes > remaining || valueWireBytes > remaining - separatorBytes) {
            throw SseEventDataTooLargeException()
        }

        if (hasData) data.append('\n')
        data.append(value)
        dataBytes += separatorBytes + valueWireBytes
        hasData = true
    }
}

private fun Buffer.startsWithUtf8Bom(): Boolean = size >= UTF8_BOM_BYTES &&
    this[0L] == UTF8_BOM_0 &&
    this[1L] == UTF8_BOM_1 &&
    this[2L] == UTF8_BOM_2

private fun Buffer.sseDataValueWireBytes(skipUtf8Bom: Boolean): Long? {
    val fieldOffset = if (skipUtf8Bom) UTF8_BOM_BYTES else 0L
    if (size - fieldOffset < DATA_FIELD_BYTES) return null
    if (
        this[fieldOffset] != DATA_0 ||
        this[fieldOffset + 1L] != DATA_1 ||
        this[fieldOffset + 2L] != DATA_2 ||
        this[fieldOffset + 3L] != DATA_3
    ) {
        return null
    }

    var valueOffset = fieldOffset + DATA_FIELD_BYTES
    if (valueOffset == size) return 0L
    if (this[valueOffset] != COLON) return null
    valueOffset += 1L
    if (valueOffset < size && this[valueOffset] == SPACE) valueOffset += 1L
    return size - valueOffset
}

private const val UTF8_BOM_BYTES = 3L
private const val DATA_FIELD_BYTES = 4L
private val UTF8_BOM_0 = 0xEF.toByte()
private val UTF8_BOM_1 = 0xBB.toByte()
private val UTF8_BOM_2 = 0xBF.toByte()
private val DATA_0 = 'd'.code.toByte()
private val DATA_1 = 'a'.code.toByte()
private val DATA_2 = 't'.code.toByte()
private val DATA_3 = 'a'.code.toByte()
private val COLON = ':'.code.toByte()
private val SPACE = ' '.code.toByte()
