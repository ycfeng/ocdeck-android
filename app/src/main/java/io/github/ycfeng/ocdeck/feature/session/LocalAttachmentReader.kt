package io.github.ycfeng.ocdeck.feature.session

import android.content.ContentResolver
import android.database.Cursor
import android.database.SQLException
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.ycfeng.ocdeck.domain.prompt.AttachmentBudget
import io.github.ycfeng.ocdeck.domain.prompt.AttachmentBudgetFailure
import io.github.ycfeng.ocdeck.domain.prompt.AttachmentLimits
import io.github.ycfeng.ocdeck.domain.prompt.OpenCodeIdGenerator
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachment
import io.github.ycfeng.ocdeck.domain.prompt.createLocalDataUrlAttachment
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Locale

internal enum class LocalAttachmentFailureReason {
    CountLimit,
    TotalSizeLimit,
    UnsupportedType,
    TooLarge,
    ReadFailed,
}

internal data class LocalAttachmentReadResult(
    val attachments: List<PromptAttachment>,
    val failureReason: LocalAttachmentFailureReason? = null,
) {
    override fun toString(): String =
        "LocalAttachmentReadResult(attachmentCount=${attachments.size}, failureReason=$failureReason)"
}

internal object LocalAttachmentReader {
    val pickerMimeTypes: Array<String> = arrayOf("*/*")

    suspend fun read(
        contentResolver: ContentResolver,
        uris: List<Uri>,
        budget: AttachmentBudget,
    ): LocalAttachmentReadResult = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext LocalAttachmentReadResult(emptyList())
        val initialFailure = when {
            budget.remainingCount == 0 -> LocalAttachmentFailureReason.CountLimit
            budget.hasUnknownSize || budget.totalSizeBytes > AttachmentLimits.MAX_TOTAL_BYTES ->
                LocalAttachmentFailureReason.TotalSizeLimit
            else -> null
        }
        if (initialFailure != null) {
            return@withContext LocalAttachmentReadResult(emptyList(), initialFailure)
        }

        val attachments = mutableListOf<PromptAttachment>()
        var failureReason: LocalAttachmentFailureReason? = null
        var currentBudget = budget
        val urisToRead = uris.take(budget.remainingCount)

        urisToRead.forEach { uri ->
            currentCoroutineContext().ensureActive()
            when (val result = readOne(contentResolver, uri, currentBudget)) {
                is ReadOneResult.Success -> {
                    val budgetUpdate = currentBudget.add(result.attachment.sizeBytes)
                    if (budgetUpdate.failure == null) {
                        attachments += result.attachment
                        currentBudget = budgetUpdate.budget
                    } else if (failureReason == null) {
                        failureReason = budgetUpdate.failure.toLocalAttachmentFailureReason()
                    }
                }
                is ReadOneResult.Failure -> if (failureReason == null) failureReason = result.reason
            }
        }
        if (uris.size > urisToRead.size && failureReason == null) {
            failureReason = LocalAttachmentFailureReason.CountLimit
        }

        LocalAttachmentReadResult(
            attachments = attachments,
            failureReason = failureReason,
        )
    }

    private suspend fun readOne(
        contentResolver: ContentResolver,
        uri: Uri,
        budget: AttachmentBudget,
    ): ReadOneResult {
        val metadata = when (val result = contentResolver.openableMetadata(uri)) {
            is ResolverCallResult.Success -> result.value
            ResolverCallResult.Failure -> return ReadOneResult.Failure(LocalAttachmentFailureReason.ReadFailed)
        }
        val displayName = metadata.displayName
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "attachment"
        val declaredSize = metadata.declaredSize
        if (declaredSize != null && declaredSize > AttachmentLimits.MAX_FILE_BYTES) {
            return ReadOneResult.Failure(LocalAttachmentFailureReason.TooLarge)
        }
        if (declaredSize != null && declaredSize > budget.remainingBytes) {
            return ReadOneResult.Failure(LocalAttachmentFailureReason.TotalSizeLimit)
        }

        val declaredMime = when (val result = contentResolver.controlledGetType(uri)) {
            is ResolverCallResult.Success -> result.value
            ResolverCallResult.Failure -> return ReadOneResult.Failure(LocalAttachmentFailureReason.ReadFailed)
        }
        val input = when (val result = contentResolver.controlledOpenInputStream(uri)) {
            is ResolverCallResult.Success -> result.value
            ResolverCallResult.Failure -> null
        } ?: return ReadOneResult.Failure(LocalAttachmentFailureReason.ReadFailed)

        val readLimit = minOf(AttachmentLimits.MAX_FILE_BYTES, budget.remainingBytes)
        val bytes = when (val result = readBoundedAttachmentBytes(input, readLimit, declaredSize)) {
            is BoundedAttachmentReadResult.Success -> result.bytes
            BoundedAttachmentReadResult.TooLarge -> {
                val reason = if (readLimit < AttachmentLimits.MAX_FILE_BYTES) {
                    LocalAttachmentFailureReason.TotalSizeLimit
                } else {
                    LocalAttachmentFailureReason.TooLarge
                }
                return ReadOneResult.Failure(reason)
            }
            BoundedAttachmentReadResult.ReadFailed ->
                return ReadOneResult.Failure(LocalAttachmentFailureReason.ReadFailed)
        }

        val mime = supportedMime(
            declaredMime = declaredMime,
            filename = displayName,
            bytes = bytes,
        ) ?: return ReadOneResult.Failure(LocalAttachmentFailureReason.UnsupportedType)

        return ReadOneResult.Success(
            createLocalDataUrlAttachment(
                id = OpenCodeIdGenerator.partId(),
                filename = displayName,
                mime = mime,
                bytes = bytes,
            ),
        )
    }

    private fun ContentResolver.openableMetadata(uri: Uri): ResolverCallResult<LocalAttachmentMetadata> {
        return try {
            query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ResolverCallResult.Success(
                        LocalAttachmentMetadata(
                            displayName = cursor.nullableDisplayName(),
                            declaredSize = cursor.nullableDeclaredSize(),
                        ),
                    )
                } else {
                    ResolverCallResult.Success(LocalAttachmentMetadata())
                }
            } ?: ResolverCallResult.Success(LocalAttachmentMetadata())
        } catch (_: SecurityException) {
            ResolverCallResult.Failure
        } catch (_: IllegalArgumentException) {
            ResolverCallResult.Failure
        } catch (_: ClassCastException) {
            ResolverCallResult.Failure
        } catch (_: SQLException) {
            ResolverCallResult.Failure
        }
    }

    private fun ContentResolver.controlledGetType(uri: Uri): ResolverCallResult<String?> = try {
        ResolverCallResult.Success(getType(uri))
    } catch (_: SecurityException) {
        ResolverCallResult.Failure
    } catch (_: IllegalArgumentException) {
        ResolverCallResult.Failure
    }

    private fun ContentResolver.controlledOpenInputStream(uri: Uri): ResolverCallResult<InputStream?> = try {
        ResolverCallResult.Success(openInputStream(uri))
    } catch (_: FileNotFoundException) {
        ResolverCallResult.Failure
    } catch (_: SecurityException) {
        ResolverCallResult.Failure
    } catch (_: IllegalArgumentException) {
        ResolverCallResult.Failure
    }

    private fun Cursor.nullableDisplayName(): String? {
        val index = getColumnIndex(OpenableColumns.DISPLAY_NAME)
        return if (index >= 0 && !isNull(index)) getString(index)?.takeIf { it.isNotBlank() } else null
    }

    private fun Cursor.nullableDeclaredSize(): Long? {
        val index = getColumnIndex(OpenableColumns.SIZE)
        return if (index >= 0 && !isNull(index)) getLong(index).takeIf { it >= 0L } else null
    }

    private fun supportedMime(declaredMime: String?, filename: String, bytes: ByteArray): String? {
        val lowerMime = declaredMime?.lowercase(Locale.US)?.substringBefore(';')?.trim()
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
        val extensionMime = extension
            .takeIf { it.isNotBlank() }
            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?.lowercase(Locale.US)

        val candidate = lowerMime?.takeIf(::isSupportedMime)
            ?: extensionMime?.takeIf(::isSupportedMime)
        if (candidate != null) return candidate

        if (extension in textExtensions || bytes.looksLikeText()) return "text/plain"
        return null
    }

    private fun isSupportedMime(mime: String): Boolean = when {
        mime.startsWith("image/") -> mime in supportedImageMimes
        mime.startsWith("text/") -> true
        mime == "application/pdf" -> true
        mime in supportedApplicationMimes -> true
        mime.endsWith("+json") || mime.endsWith("+xml") -> true
        else -> false
    }

    private fun ByteArray.looksLikeText(): Boolean {
        if (isEmpty()) return true
        val sampleSize = minOf(size, TextSampleBytes)
        var controlCount = 0
        for (index in 0 until sampleSize) {
            val value = this[index].toInt() and 0xFF
            if (value == 0) return false
            if (value < 0x09 || (value in 0x0E..0x1F)) controlCount += 1
        }
        return controlCount <= sampleSize / MaximumTextControlByteDivisor
    }
}

internal fun AttachmentBudgetFailure.toLocalAttachmentFailureReason(): LocalAttachmentFailureReason = when (this) {
    AttachmentBudgetFailure.CountLimit -> LocalAttachmentFailureReason.CountLimit
    AttachmentBudgetFailure.TotalSizeLimit -> LocalAttachmentFailureReason.TotalSizeLimit
    AttachmentBudgetFailure.TooLarge -> LocalAttachmentFailureReason.TooLarge
}

private sealed interface ReadOneResult {
    data class Success(val attachment: PromptAttachment) : ReadOneResult
    data class Failure(val reason: LocalAttachmentFailureReason) : ReadOneResult
}

private sealed interface ResolverCallResult<out T> {
    data class Success<T>(val value: T) : ResolverCallResult<T> {
        override fun toString(): String = "ResolverCallResult.Success(valuePresent=true)"
    }

    data object Failure : ResolverCallResult<Nothing>
}

private data class LocalAttachmentMetadata(
    val displayName: String? = null,
    val declaredSize: Long? = null,
) {
    override fun toString(): String =
        "LocalAttachmentMetadata(displayName=${if (displayName == null) "null" else "<redacted>"}, " +
            "declaredSize=$declaredSize)"
}

private const val TextSampleBytes = 4 * 1024
private const val MaximumTextControlByteDivisor = 20

private val supportedImageMimes = setOf(
    "image/png",
    "image/jpeg",
    "image/gif",
    "image/webp",
)

private val supportedApplicationMimes = setOf(
    "application/json",
    "application/ld+json",
    "application/toml",
    "application/x-toml",
    "application/x-yaml",
    "application/xml",
    "application/yaml",
)

private val textExtensions = setOf(
    "c",
    "cc",
    "cjs",
    "conf",
    "cpp",
    "css",
    "csv",
    "cts",
    "env",
    "go",
    "gql",
    "graphql",
    "h",
    "hh",
    "hpp",
    "htm",
    "html",
    "ini",
    "java",
    "js",
    "json",
    "jsx",
    "log",
    "md",
    "mdx",
    "mjs",
    "mts",
    "py",
    "rb",
    "rs",
    "sass",
    "scss",
    "sh",
    "sql",
    "toml",
    "ts",
    "tsx",
    "txt",
    "xml",
    "yaml",
    "yml",
    "zsh",
)
