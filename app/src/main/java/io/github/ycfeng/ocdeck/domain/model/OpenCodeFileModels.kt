package io.github.ycfeng.ocdeck.domain.model

enum class OpenCodeFileType {
    File,
    Directory,
}

data class OpenCodeFileEntry(
    val name: String,
    val path: String,
    val type: OpenCodeFileType,
    val ignored: Boolean,
) {
    override fun toString(): String =
        "OpenCodeFileEntry(name=<redacted>, path=<redacted>, type=$type, ignored=$ignored)"
}

sealed interface OpenCodeFileContent {
    val path: String

    data class Text(
        override val path: String,
        val text: String,
    ) : OpenCodeFileContent {
        override fun toString(): String =
            "OpenCodeFileContent.Text(path=<redacted>, textLength=${text.length})"
    }

    data class Binary(
        override val path: String,
        val base64: String,
        val mimeType: String?,
    ) : OpenCodeFileContent {
        override fun toString(): String =
            "OpenCodeFileContent.Binary(path=<redacted>, base64=<redacted>, " +
                "base64Length=${base64.length}, mimeTypePresent=${mimeType != null})"
    }

    data class TooLarge(
        override val path: String,
    ) : OpenCodeFileContent {
        override fun toString(): String = "OpenCodeFileContent.TooLarge(path=<redacted>)"
    }
}
