package io.github.ycfeng.ocdeck.domain.prompt

import java.util.Base64

data class PromptAttachment(
    val id: String,
    val filename: String,
    val mime: String,
    val dataUrl: String,
    /** Actual raw payload bytes. Local data URL attachments always provide this value. */
    val sizeBytes: Long? = null,
) {
    val isImage: Boolean
        get() = mime.startsWith("image/", ignoreCase = true)

    override fun toString(): String =
        "PromptAttachment(id=<redacted>, filename=<redacted>, mimePresent=${mime.isNotEmpty()}, " +
            "dataUrl=<redacted>, sizeBytes=$sizeBytes, isImage=$isImage)"
}

internal fun createLocalDataUrlAttachment(
    id: String,
    filename: String,
    mime: String,
    bytes: ByteArray,
): PromptAttachment = PromptAttachment(
    id = id,
    filename = filename,
    mime = mime,
    dataUrl = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}",
    sizeBytes = bytes.size.toLong(),
)
