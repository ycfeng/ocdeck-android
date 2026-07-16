package io.github.ycfeng.ocdeck.core.security

import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal object SshPrivateKeyLimits {
    const val MAX_BYTES = 256 * 1024
    const val READ_BUFFER_BYTES = 8 * 1024

    fun requireValidUtf8Size(privateKey: String) {
        var byteCount = 0L
        var index = 0
        while (index < privateKey.length) {
            val character = privateKey[index]
            byteCount += when {
                character.code <= 0x7f -> 1L
                character.code <= 0x7ff -> 2L
                character.isHighSurrogate() && privateKey.getOrNull(index + 1)?.isLowSurrogate() == true -> {
                    index += 1
                    4L
                }
                character.isSurrogate() -> 1L
                else -> 3L
            }
            if (byteCount > MAX_BYTES) throw SshPrivateKeyTooLargeException()
            index += 1
        }
    }
}

internal class SshPrivateKeyTooLargeException :
    IllegalArgumentException("SSH private key exceeds the allowed size")

internal object SshPrivateKeyReader {
    suspend fun readUtf8(
        declaredSizeBytes: Long?,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        openInputStream: () -> InputStream,
    ): String = withContext(dispatcher) {
        if (declaredSizeBytes != null && declaredSizeBytes >= 0L && declaredSizeBytes > SshPrivateKeyLimits.MAX_BYTES) {
            throw SshPrivateKeyTooLargeException()
        }

        val context = currentCoroutineContext()
        context.ensureActive()
        openInputStream().use { input ->
            val output = ByteArray(SshPrivateKeyLimits.MAX_BYTES + 1)
            val buffer = ByteArray(SshPrivateKeyLimits.READ_BUFFER_BYTES)
            var totalBytes = 0
            try {
                while (true) {
                    context.ensureActive()
                    val probeRemaining = output.size - totalBytes
                    if (probeRemaining == 0) throw SshPrivateKeyTooLargeException()
                    val read = input.read(buffer, 0, minOf(buffer.size, probeRemaining))
                    context.ensureActive()
                    if (read < 0) break
                    if (read == 0) {
                        val singleByte = input.read()
                        context.ensureActive()
                        if (singleByte < 0) break
                        output[totalBytes++] = singleByte.toByte()
                    } else {
                        buffer.copyInto(output, destinationOffset = totalBytes, endIndex = read)
                        totalBytes += read
                    }
                    if (totalBytes > SshPrivateKeyLimits.MAX_BYTES) throw SshPrivateKeyTooLargeException()
                    yield()
                }
                String(output, 0, totalBytes, Charsets.UTF_8)
            } finally {
                buffer.fill(0)
                output.fill(0)
            }
        }
    }
}
