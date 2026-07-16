package io.github.ycfeng.ocdeck.core.util

import java.net.URI

class ProjectFileUrlBuilder(
    private val pathNormalizer: PathNormalizer = PathNormalizer(),
    private val projectFilePathNormalizer: ProjectFilePathNormalizer = ProjectFilePathNormalizer(),
) {
    fun build(projectDirectory: String, relativePath: String): String {
        val root = pathNormalizer.normalize(projectDirectory)
        require(root.isNotBlank()) { "Project directory cannot be empty" }
        val windowsSeparators = projectFilePathNormalizer.usesWindowsSeparators(root)
        val relative = projectFilePathNormalizer.normalize(
            relativePath,
            backslashIsSeparator = windowsSeparators,
        )
        val relativeSegments = relative.split('/').map(::encodePathSegment)

        return when {
            root.startsWith("//") -> buildUncUrl(root, relativeSegments)
            WindowsDrivePath.matches(root) -> {
                val rootSegments = root.split('/').mapIndexed { index, segment ->
                    encodePathSegment(segment, preserveColon = index == 0)
                }
                "file:///${(rootSegments + relativeSegments).joinToString("/")}"
            }
            root.startsWith('/') -> {
                val rootSegments = root.removePrefix("/")
                    .split('/')
                    .filter(String::isNotEmpty)
                    .map(::encodePathSegment)
                "file:///${(rootSegments + relativeSegments).joinToString("/")}"
            }
            else -> throw IllegalArgumentException("Project directory must be absolute")
        }
    }

    fun relativePath(projectDirectory: String, url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("file", ignoreCase = true) || uri.isOpaque) return null
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) return null

        val root = pathNormalizer.normalize(projectDirectory)
        if (root.isBlank()) return null
        val windowsSeparators = projectFilePathNormalizer.usesWindowsSeparators(root)
        val authority = uri.authority.orEmpty()
        val decodedPath = uri.path ?: return null
        val absolute = when {
            authority.isNotEmpty() && !authority.equals("localhost", ignoreCase = true) -> "//$authority$decodedPath"
            WindowsDriveUriPath.matches(decodedPath) -> decodedPath.removePrefix("/")
            else -> decodedPath
        }
        val normalizedAbsolute = if (windowsSeparators) {
            pathNormalizer.normalize(absolute)
        } else {
            absolute.trimEnd('/').ifEmpty { "/" }
        }
        val comparisonRoot = root.comparisonValue(windowsSeparators)
        val comparisonAbsolute = normalizedAbsolute.comparisonValue(windowsSeparators)
        val relative = when {
            root == "/" && comparisonAbsolute.startsWith("/") -> normalizedAbsolute.removePrefix("/")
            comparisonAbsolute.startsWith("$comparisonRoot/") -> normalizedAbsolute.substring(root.length + 1)
            else -> return null
        }
        projectFilePathNormalizer.normalize(
            relative,
            backslashIsSeparator = windowsSeparators,
        )
    }.getOrNull()

    private fun buildUncUrl(root: String, relativeSegments: List<String>): String {
        val rootSegments = root.removePrefix("//")
            .split('/')
            .filter(String::isNotEmpty)
        require(rootSegments.size >= 2) { "UNC project directory must include a server and share" }
        val authority = encodePathSegment(rootSegments.first())
        val pathSegments = rootSegments.drop(1).map(::encodePathSegment) + relativeSegments
        return "file://$authority/${pathSegments.joinToString("/")}"
    }

    private fun encodePathSegment(value: String, preserveColon: Boolean = false): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            val character = unsigned.toChar()
            if (character.isUriSegmentSafe() || preserveColon && character == ':') {
                append(character)
            } else {
                append('%')
                append(HexDigits[unsigned ushr 4])
                append(HexDigits[unsigned and 0x0f])
            }
        }
    }

    private fun Char.isUriSegmentSafe(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this in "-_.!~*'()"

    private fun String.comparisonValue(caseInsensitive: Boolean): String =
        if (caseInsensitive) lowercase() else this

    private companion object {
        val WindowsDrivePath = Regex("^[A-Za-z]:/.*")
        val WindowsDriveUriPath = Regex("^/[A-Za-z]:/.*")
        const val HexDigits = "0123456789ABCDEF"
    }
}
