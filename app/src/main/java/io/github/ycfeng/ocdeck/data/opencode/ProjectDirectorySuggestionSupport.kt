package io.github.ycfeng.ocdeck.data.opencode

import io.github.ycfeng.ocdeck.core.util.PathNormalizer

internal data class DirectoryBrowseRequest(
    val directory: String,
    val fragment: String,
) {
    override fun toString(): String =
        "DirectoryBrowseRequest(directory=<redacted>, fragmentPresent=${fragment.isNotEmpty()})"
}

internal fun buildDirectoryBrowseRequest(input: String, pathNormalizer: PathNormalizer): DirectoryBrowseRequest? {
    val value = input.trim().replace('\\', '/')
    if (value.isBlank() || !value.isAbsolutePathInput()) return null

    val rootAdjusted = if (windowsDriveBareRegex.matches(value)) "${value.uppercase()}/" else value
    if (rootAdjusted == "/") return DirectoryBrowseRequest(directory = "/", fragment = "")
    if (windowsDriveRootRegex.matches(rootAdjusted)) {
        return DirectoryBrowseRequest(directory = rootAdjusted.uppercase(), fragment = "")
    }

    val isDirectoryInput = rootAdjusted.endsWith('/')
    val withoutTrailingSlash = rootAdjusted.trimEnd('/')
    if (isDirectoryInput) {
        return DirectoryBrowseRequest(
            directory = normalizeFileApiDirectory(withoutTrailingSlash, pathNormalizer),
            fragment = "",
        )
    }

    val separatorIndex = withoutTrailingSlash.lastIndexOf('/')
    if (separatorIndex < 0) return null

    val parent = when {
        separatorIndex == 0 -> "/"
        separatorIndex == 2 && withoutTrailingSlash.getOrNull(1) == ':' -> withoutTrailingSlash.substring(0, separatorIndex + 1)
        else -> withoutTrailingSlash.substring(0, separatorIndex)
    }
    val fragment = withoutTrailingSlash.substring(separatorIndex + 1)

    return DirectoryBrowseRequest(
        directory = normalizeFileApiDirectory(parent, pathNormalizer),
        fragment = fragment,
    )
}

internal fun parentDirectoryOf(directory: String, pathNormalizer: PathNormalizer): String? {
    val normalized = pathNormalizer.normalize(directory)
    if (normalized == "/") return "/"
    if (windowsDriveRootRegex.matches(normalized)) return normalized

    val separatorIndex = normalized.lastIndexOf('/')
    return when {
        separatorIndex < 0 -> null
        separatorIndex == 0 -> "/"
        separatorIndex == 2 && normalized.getOrNull(1) == ':' -> normalized.substring(0, separatorIndex + 1)
        else -> normalized.substring(0, separatorIndex)
    }
}

internal fun joinDirectory(parent: String, child: String, pathNormalizer: PathNormalizer): String {
    val base = normalizeFileApiDirectory(parent, pathNormalizer)
    val relative = child.replace('\\', '/').trim('/')
    if (relative.isBlank()) return base
    val joined = when {
        base == "/" -> "/$relative"
        windowsDriveRootRegex.matches(base) -> "$base$relative"
        else -> "$base/$relative"
    }
    return pathNormalizer.normalize(joined)
}

internal fun displayNameFromDirectory(directory: String): String {
    val normalized = directory.replace('\\', '/').trimEnd('/')
    return normalized.substringAfterLast('/').ifBlank { directory }
}

private fun String.isAbsolutePathInput(): Boolean = startsWith('/') || windowsAbsolutePathRegex.matches(this)

private fun normalizeFileApiDirectory(directory: String, pathNormalizer: PathNormalizer): String = when {
    directory == "/" -> "/"
    windowsDriveBareRegex.matches(directory) -> "${directory.uppercase()}/"
    windowsDriveRootRegex.matches(directory) -> directory.uppercase()
    else -> pathNormalizer.normalize(directory)
}

private val windowsAbsolutePathRegex = Regex("^[A-Za-z]:($|/.*)")
private val windowsDriveBareRegex = Regex("^[A-Za-z]:$")
private val windowsDriveRootRegex = Regex("^[A-Za-z]:/$")
