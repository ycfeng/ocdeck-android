package io.github.ycfeng.ocdeck.feature.session

import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommentSelection
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageComment
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import java.net.URI
import kotlin.math.max
import kotlin.math.min

private val CommentNotePattern = Regex(
    "^The user made the following comment regarding (this file|line (\\d+)|lines (\\d+) through (\\d+)) of (.+?): ([\\s\\S]+)$",
)

internal fun messageComments(parts: List<OpenCodeMessagePart>): List<OpenCodeMessageComment> =
    parts.mapNotNull(OpenCodeMessagePart::toMessageComment)

internal fun hasMessageCommentPart(parts: List<OpenCodeMessagePart>): Boolean =
    parts.any { it.toMessageComment() != null }

internal fun hasFileAttachmentPart(parts: List<OpenCodeMessagePart>): Boolean =
    parts.any(::isFileAttachmentPart)

internal fun hasProjectFileContextPart(parts: List<OpenCodeMessagePart>): Boolean =
    projectFileContextParts(parts).isNotEmpty()

internal fun projectFileContextParts(parts: List<OpenCodeMessagePart>): List<OpenCodeMessagePart> {
    val commentBackingIndexes = mutableSetOf<Int>()
    parts.forEachIndexed { commentIndex, part ->
        val comment = part.toMessageComment() ?: return@forEachIndexed
        val backingIndex = ((commentIndex + 1) until parts.size).firstOrNull { candidateIndex ->
            candidateIndex !in commentBackingIndexes && parts[candidateIndex].matchesCommentBackingFile(comment)
        }
        backingIndex?.let(commentBackingIndexes::add)
    }
    return parts.filterIndexed { index, part -> index !in commentBackingIndexes && isProjectFilePart(part) }
}

internal fun isFileAttachmentPart(part: OpenCodeMessagePart): Boolean =
    part.type.equals("file", ignoreCase = true) &&
        part.url?.startsWith("data:", ignoreCase = true) == true

internal fun isProjectFilePart(part: OpenCodeMessagePart): Boolean =
    part.type.equals("file", ignoreCase = true) &&
        part.url?.startsWith("file:", ignoreCase = true) == true

internal fun isImageAttachmentPart(part: OpenCodeMessagePart): Boolean {
    if (!isFileAttachmentPart(part)) return false
    if (part.mime?.trim()?.startsWith("image/", ignoreCase = true) == true) return true
    return part.url?.dataUrlMediaType()?.startsWith("image/", ignoreCase = true) == true
}

internal fun OpenCodeMessageComment.displayFilename(): String = path
    .trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .ifBlank { path }

internal fun OpenCodeMessagePart.projectContextDisplayFilename(): String = filename
    ?.takeIf(String::isNotBlank)
    ?: runCatching { URI(url).path }
        .getOrNull()
        ?.trimEnd('/', '\\')
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf(String::isNotBlank)
    ?: id

internal fun OpenCodeMessageComment.lineSuffix(): String {
    val range = selection ?: return ""
    val start = min(range.startLine, range.endLine)
    val end = max(range.startLine, range.endLine)
    return if (start == end) ":$start" else ":$start-$end"
}

internal fun OpenCodeMessagePart.toMessageComment(): OpenCodeMessageComment? {
    if (!type.equals("text", ignoreCase = true) || !synthetic) return null
    return opencodeComment ?: text?.parseCommentNote()
}

private fun OpenCodeMessagePart.matchesCommentBackingFile(comment: OpenCodeMessageComment): Boolean {
    if (!isProjectFilePart(this)) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val filePath = uri.path
        ?.replace('\\', '/')
        ?.trimEnd('/')
        ?: return false
    val commentPath = comment.path.replace('\\', '/').trim('/')
    if (commentPath.isEmpty() || !filePath.endsWith("/$commentPath")) return false

    val selection = comment.selection
    if (selection == null) return uri.rawQuery == null
    val query = uri.rawQuery
        ?.split('&')
        ?.mapNotNull { item ->
            val key = item.substringBefore('=', missingDelimiterValue = "")
            val value = item.substringAfter('=', missingDelimiterValue = "")
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
        ?.toMap()
        .orEmpty()
    val start = min(selection.startLine, selection.endLine).toString()
    val end = max(selection.startLine, selection.endLine).toString()
    return query["start"] == start && query["end"] == end
}

private fun String.parseCommentNote(): OpenCodeMessageComment? {
    val match = CommentNotePattern.matchEntire(this) ?: return null
    val start = match.groupValues[2].toIntOrNull() ?: match.groupValues[3].toIntOrNull()
    val end = match.groupValues[2].toIntOrNull() ?: match.groupValues[4].toIntOrNull()
    return OpenCodeMessageComment(
        path = match.groupValues[5],
        selection = if (start != null && end != null) {
            OpenCodeCommentSelection(
                startLine = start,
                startChar = 0,
                endLine = end,
                endChar = 0,
            )
        } else {
            null
        },
        comment = match.groupValues[6],
    )
}

private fun String.dataUrlMediaType(): String? {
    if (!startsWith("data:", ignoreCase = true)) return null
    val headerEnd = indexOf(',')
    if (headerEnd < 5) return null
    return substring(5, headerEnd)
        .substringBefore(';')
        .trim()
        .takeIf { it.isNotEmpty() }
}
