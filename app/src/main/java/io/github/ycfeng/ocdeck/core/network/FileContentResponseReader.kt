package io.github.ycfeng.ocdeck.core.network

import java.io.ByteArrayOutputStream
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody

internal class FileContentResponseTooLargeException : InboundPayloadTooLargeException()

internal fun ResponseBody.readFileContentDto(
    json: Json,
    maxBytes: Long = MaxFileContentDecodedResponseBytes,
): FileContentDto = use { body ->
    require(maxBytes > 0) { "File content response limit must be positive" }
    val declaredLength = body.contentLength()
    if (declaredLength > maxBytes) throw FileContentResponseTooLargeException()

    val initialCapacity = declaredLength
        .takeIf { it in 1..maxBytes }
        ?.toInt()
        ?: DefaultResponseBufferBytes
    val output = ByteArrayOutputStream(initialCapacity)
    body.byteStream().use { input ->
        val buffer = ByteArray(DefaultResponseBufferBytes)
        var totalBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            if (totalBytes > maxBytes) throw FileContentResponseTooLargeException()
            output.write(buffer, 0, count)
        }
    }
    json.decodeFromString(output.toByteArray().toString(Charsets.UTF_8))
}

internal const val MaxFileContentDecodedResponseBytes = InboundPayloadLimits.FILE_CONTENT_DECODED_RESPONSE_BYTES
private const val DefaultResponseBufferBytes = 8 * 1024
