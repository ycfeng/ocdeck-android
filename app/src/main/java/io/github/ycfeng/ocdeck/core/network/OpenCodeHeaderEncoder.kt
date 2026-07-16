package io.github.ycfeng.ocdeck.core.network

import java.nio.charset.StandardCharsets

internal object OpenCodeHeaderEncoder {
    fun encodeDirectory(directory: String): String = buildString {
        for (byte in directory.toByteArray(StandardCharsets.UTF_8)) {
            val value = byte.toInt() and 0xff
            val char = value.toChar()
            if (char.isEncodeURIComponentSafe()) {
                append(char)
            } else {
                append('%')
                append(value.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }

    private fun Char.isEncodeURIComponentSafe(): Boolean =
        this in 'A'..'Z' ||
            this in 'a'..'z' ||
            this in '0'..'9' ||
            this == '-' ||
            this == '_' ||
            this == '.' ||
            this == '!' ||
            this == '~' ||
            this == '*' ||
            this == '\'' ||
            this == '(' ||
            this == ')'
}
