package io.github.ycfeng.ocdeck.core.util

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class SafeExternalHttpUrl(
    val value: String,
    val usesLoopbackHost: Boolean,
) {
    override fun toString(): String =
        "SafeExternalHttpUrl(value=<redacted>, usesLoopbackHost=$usesLoopbackHost)"
}

internal fun String.toSafeExternalHttpUrlOrNull(): SafeExternalHttpUrl? {
    val parsed = trim().toHttpUrlOrNull() ?: return null
    if (parsed.scheme != "http" && parsed.scheme != "https") return null
    if (parsed.host.isBlank() || parsed.encodedUsername.isNotEmpty() || parsed.encodedPassword.isNotEmpty()) {
        return null
    }
    return SafeExternalHttpUrl(
        value = parsed.toString(),
        usesLoopbackHost = parsed.host.isSyntacticLoopbackHost(),
    )
}

private fun String.isSyntacticLoopbackHost(): Boolean {
    val normalized = lowercase().trimEnd('.')
    if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized == "::1") return true
    val ipv4Parts = normalized.split('.')
    return ipv4Parts.size == 4 &&
        ipv4Parts.all { part -> part.toIntOrNull() in 0..255 } &&
        ipv4Parts.firstOrNull() == "127"
}
