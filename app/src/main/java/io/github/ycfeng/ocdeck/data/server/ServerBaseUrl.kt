package io.github.ycfeng.ocdeck.data.server

import java.net.InetAddress
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class ServerBaseUrlFailure {
    Invalid,
    UserInfo,
    Query,
    Fragment,
}

class ServerBaseUrlValidationException(
    val reason: ServerBaseUrlFailure,
) : IllegalArgumentException(
    when (reason) {
        ServerBaseUrlFailure.Invalid -> "Server base URL is invalid"
        ServerBaseUrlFailure.UserInfo -> "Server base URL must not contain user info"
        ServerBaseUrlFailure.Query -> "Server base URL must not contain a query"
        ServerBaseUrlFailure.Fragment -> "Server base URL must not contain a fragment"
    },
)

internal fun normalizeServerBaseUrl(value: String): String {
    val candidate = value.trim()
    val uri = try {
        URI(candidate)
    } catch (_: Exception) {
        throw ServerBaseUrlValidationException(ServerBaseUrlFailure.Invalid)
    }
    if (
        (
            !uri.scheme.equals("http", ignoreCase = true) &&
                !uri.scheme.equals("https", ignoreCase = true)
            ) ||
        uri.rawAuthority.isNullOrBlank()
    ) {
        throw ServerBaseUrlValidationException(ServerBaseUrlFailure.Invalid)
    }
    if (uri.rawUserInfo != null) throw ServerBaseUrlValidationException(ServerBaseUrlFailure.UserInfo)
    if (uri.rawQuery != null) throw ServerBaseUrlValidationException(ServerBaseUrlFailure.Query)
    if (uri.rawFragment != null) throw ServerBaseUrlValidationException(ServerBaseUrlFailure.Fragment)

    val url = candidate.toHttpUrlOrNull()
        ?: throw ServerBaseUrlValidationException(ServerBaseUrlFailure.Invalid)
    if (url.host.isBlank()) throw ServerBaseUrlValidationException(ServerBaseUrlFailure.Invalid)
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
        throw ServerBaseUrlValidationException(ServerBaseUrlFailure.UserInfo)
    }
    if (url.query != null) throw ServerBaseUrlValidationException(ServerBaseUrlFailure.Query)
    if (url.fragment != null) throw ServerBaseUrlValidationException(ServerBaseUrlFailure.Fragment)
    return url.toString().trimEnd('/')
}

internal fun safeServerBaseUrlForDisplay(value: String): String? = try {
    normalizeServerBaseUrl(value)
} catch (_: ServerBaseUrlValidationException) {
    null
}

internal fun isNonLoopbackHttpServerBaseUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.scheme == "http" && !isLoopbackHost(url.host)
}

private fun isLoopbackHost(host: String): Boolean {
    val normalizedHost = host.trimEnd('.').lowercase()
    if (normalizedHost == "localhost" || normalizedHost.endsWith(".localhost")) return true

    val ipv4Parts = normalizedHost.split('.')
    if (ipv4Parts.size == 4) {
        val octets = ipv4Parts.map { it.toIntOrNull() ?: return false }
        return octets.all { it in 0..255 } && octets.first() == 127
    }

    // A colon-bearing HttpUrl host is an already validated numeric IPv6 literal, so this cannot perform DNS lookup.
    return ':' in normalizedHost && runCatching {
        InetAddress.getByName(normalizedHost).isLoopbackAddress
    }.getOrDefault(false)
}
