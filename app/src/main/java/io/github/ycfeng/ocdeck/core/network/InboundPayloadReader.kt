package io.github.ycfeng.ocdeck.core.network

import java.io.IOException
import java.io.InputStream
import okhttp3.ResponseBody
import okio.Buffer

internal fun readBoundedResponseBody(
    body: ResponseBody,
    maxBytes: Long,
    tooLargeException: () -> InboundPayloadTooLargeException,
    cancellationCheck: () -> Unit = {},
): ByteArray = body.use { responseBody ->
    require(maxBytes in 0..Int.MAX_VALUE.toLong()) { "Inbound response limit is out of range" }
    cancellationCheck()

    // Content-Length is only an early rejection hint. A missing or understated value never bypasses streaming checks.
    val declaredLength = responseBody.contentLength()
    if (declaredLength > maxBytes) throw tooLargeException()

    readBoundedBytes(
        input = responseBody.byteStream(),
        maxBytes = maxBytes,
        tooLargeException = tooLargeException,
        cancellationCheck = cancellationCheck,
    )
}

internal fun readBoundedBytes(
    input: InputStream,
    maxBytes: Long,
    tooLargeException: () -> InboundPayloadTooLargeException,
    cancellationCheck: () -> Unit = {},
): ByteArray = input.use { stream ->
    require(maxBytes in 0..Int.MAX_VALUE.toLong()) { "Inbound byte limit is out of range" }
    val output = Buffer()
    val readBuffer = ByteArray(InboundPayloadLimits.READ_BUFFER_BYTES)
    var totalBytes = 0L

    while (true) {
        cancellationCheck()
        val remaining = maxBytes - totalBytes
        val requested = minOf(readBuffer.size.toLong(), remaining + 1L).toInt()
        val count = stream.read(readBuffer, 0, requested)
        cancellationCheck()

        when {
            count < 0 -> break
            count > requested -> throw IOException()
            count == 0 -> {
                val value = stream.read()
                cancellationCheck()
                if (value < 0) break
                if (totalBytes == maxBytes) throw tooLargeException()
                output.writeByte(value)
                totalBytes += 1L
            }
            count.toLong() > remaining -> throw tooLargeException()
            else -> {
                output.write(readBuffer, 0, count)
                totalBytes += count.toLong()
            }
        }
    }

    cancellationCheck()
    output.readByteArray()
}
