package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import okhttp3.ResponseBody

@OptIn(ExperimentalSerializationApi::class)
internal fun ResponseBody.readSessionMessages(
    json: Json,
    maxBytes: Long = InboundPayloadLimits.SESSION_MESSAGES_RESPONSE_BYTES,
): List<MessageWithPartsDto> = use { responseBody ->
    require(maxBytes >= 0L) { "Inbound response limit is out of range" }
    val declaredLength = responseBody.contentLength()
    if (declaredLength > maxBytes) throw SessionMessagesResponseTooLargeException()

    val input = BoundedInputStream(
        delegate = responseBody.byteStream(),
        maxBytes = maxBytes,
        tooLargeException = ::SessionMessagesResponseTooLargeException,
    )
    input.use { boundedInput ->
        val messages = try {
            json.decodeFromStream<List<MessageWithPartsDto>>(boundedInput)
        } catch (exception: Exception) {
            if (boundedInput.limitExceeded) throw SessionMessagesResponseTooLargeException()
            throw exception
        }

        // Some decoders stop after the root value. Drain through the same limiter so trailing or
        // understated content cannot avoid the wire-byte boundary or EOF verification.
        try {
            boundedInput.consumeToEof()
        } catch (exception: Exception) {
            if (boundedInput.limitExceeded) throw SessionMessagesResponseTooLargeException()
            throw exception
        }
        messages
    }
}

private class BoundedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
    private val tooLargeException: () -> InboundPayloadTooLargeException,
) : InputStream() {
    private var bytesRead = 0L
    var limitExceeded = false
        private set

    override fun read(): Int {
        val value = delegate.read()
        if (value < 0) return -1
        if (bytesRead == maxBytes) throwTooLarge()
        bytesRead += 1L
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        require(offset >= 0 && length >= 0 && length <= buffer.size - offset)
        val remaining = maxBytes - bytesRead
        val requested = minOf(length.toLong(), remaining + 1L).toInt()
        val count = delegate.read(buffer, offset, requested)
        if (count < 0) return -1
        if (count == 0) return readOneInto(buffer, offset)
        if (count > requested) throw IOException()
        if (count.toLong() > remaining) throwTooLarge()
        bytesRead += count.toLong()
        return count
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0L) return 0L
        val buffer = ByteArray(minOf(InboundPayloadLimits.READ_BUFFER_BYTES.toLong(), byteCount).toInt())
        var skipped = 0L
        while (skipped < byteCount) {
            val count = read(buffer, 0, minOf(buffer.size.toLong(), byteCount - skipped).toInt())
            if (count < 0) break
            skipped += count.toLong()
        }
        return skipped
    }

    override fun available(): Int = minOf(delegate.available().toLong(), maxBytes - bytesRead).toInt()

    override fun markSupported(): Boolean = false

    override fun close() = delegate.close()

    fun consumeToEof() {
        val buffer = ByteArray(InboundPayloadLimits.READ_BUFFER_BYTES)
        while (true) {
            if (read(buffer) < 0) return
        }
    }

    private fun readOneInto(buffer: ByteArray, offset: Int): Int {
        val value = read()
        if (value < 0) return -1
        buffer[offset] = value.toByte()
        return 1
    }

    private fun throwTooLarge(): Nothing {
        limitExceeded = true
        throw tooLargeException()
    }
}
