package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.domain.prompt.AttachmentLimits
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface BoundedAttachmentReadResult {
    data class Success(val bytes: ByteArray) : BoundedAttachmentReadResult {
        override fun toString(): String = "BoundedAttachmentReadResult.Success(byteCount=${bytes.size})"
    }

    data object TooLarge : BoundedAttachmentReadResult

    data object ReadFailed : BoundedAttachmentReadResult
}

internal suspend fun readBoundedAttachmentBytes(
    input: InputStream,
    maxBytes: Long,
    declaredSize: Long? = null,
    cancellationCheck: () -> Unit = {},
): BoundedAttachmentReadResult {
    require(maxBytes in 0..Int.MAX_VALUE.toLong()) { "Attachment read limit is out of range" }
    val context = currentCoroutineContext()
    return try {
        input.use { stream ->
            val output = ByteArrayOutputStream(initialCapacity(declaredSize, maxBytes))
            val buffer = ByteArray(AttachmentLimits.READ_BUFFER_BYTES)
            var totalRead = 0L
            while (true) {
                context.ensureActive()
                cancellationCheck()
                val probeBytesRemaining = maxBytes + 1L - totalRead
                val requested = minOf(buffer.size.toLong(), probeBytesRemaining).toInt()
                val count = stream.read(buffer, 0, requested)
                context.ensureActive()
                cancellationCheck()
                when {
                    count < 0 -> return@use BoundedAttachmentReadResult.Success(output.toByteArray())
                    count > requested -> return@use BoundedAttachmentReadResult.ReadFailed
                    count == 0 -> {
                        val value = stream.read()
                        context.ensureActive()
                        cancellationCheck()
                        if (value < 0) {
                            return@use BoundedAttachmentReadResult.Success(output.toByteArray())
                        }
                        totalRead += 1L
                        if (totalRead > maxBytes) return@use BoundedAttachmentReadResult.TooLarge
                        output.write(value)
                    }
                    else -> {
                        totalRead += count.toLong()
                        if (totalRead > maxBytes) return@use BoundedAttachmentReadResult.TooLarge
                        output.write(buffer, 0, count)
                    }
                }
            }
            error("Unreachable")
        }
    } catch (_: IOException) {
        BoundedAttachmentReadResult.ReadFailed
    } catch (_: SecurityException) {
        BoundedAttachmentReadResult.ReadFailed
    }
}

private fun initialCapacity(declaredSize: Long?, maxBytes: Long): Int {
    val capacityLimit = minOf(maxBytes, AttachmentLimits.MAX_INITIAL_CAPACITY_BYTES.toLong())
    return declaredSize
        ?.takeIf { it >= 0L }
        ?.coerceAtMost(capacityLimit)
        ?.toInt()
        ?: minOf(AttachmentLimits.READ_BUFFER_BYTES.toLong(), capacityLimit).toInt()
}
