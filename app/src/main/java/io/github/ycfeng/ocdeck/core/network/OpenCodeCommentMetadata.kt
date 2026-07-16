package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommentOrigin
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommentSelection
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageComment
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonElement?.toOpenCodeMessageComment(): OpenCodeMessageComment? {
    val value = (this as? JsonObject)?.get("opencodeComment") as? JsonObject ?: return null
    val path = value.string("path") ?: return null
    val comment = value.string("comment") ?: return null
    return OpenCodeMessageComment(
        path = path,
        selection = value["selection"].toCommentSelection(),
        comment = comment,
        preview = value.string("preview"),
        origin = when (value.string("origin")) {
            "file" -> OpenCodeCommentOrigin.File
            "review" -> OpenCodeCommentOrigin.Review
            else -> null
        },
    )
}

private fun JsonElement?.toCommentSelection(): OpenCodeCommentSelection? {
    val value = this as? JsonObject ?: return null
    return OpenCodeCommentSelection(
        startLine = value.int("startLine") ?: return null,
        startChar = value.int("startChar") ?: return null,
        endLine = value.int("endLine") ?: return null,
        endChar = value.int("endChar") ?: return null,
    )
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content

private fun JsonObject.int(name: String): Int? {
    val number = (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
    if (!number.isFinite() || number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
    return number.toInt()
}
