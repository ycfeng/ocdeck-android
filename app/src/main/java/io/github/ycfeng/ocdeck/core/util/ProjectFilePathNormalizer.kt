package io.github.ycfeng.ocdeck.core.util

class ProjectFilePathNormalizer {
    fun normalize(
        path: String,
        allowEmpty: Boolean = false,
        backslashIsSeparator: Boolean = true,
    ): String {
        require('\u0000' !in path) { "Project file path contains a null character" }

        val slashNormalized = if (backslashIsSeparator) path.replace('\\', '/') else path
        require(!slashNormalized.startsWith('/')) { "Project file path must be relative" }
        require(!backslashIsSeparator || !windowsDrivePathRegex.containsMatchIn(slashNormalized)) {
            "Project file path must be relative"
        }

        val segments = slashNormalized.split('/')
            .filterNot { it.isEmpty() || it == "." }
        require(segments.none { it == ".." }) { "Project file path cannot escape the project root" }

        val normalized = segments.joinToString("/")
        require(allowEmpty || normalized.isNotEmpty()) { "Project file path cannot be empty" }
        return normalized
    }

    fun parent(path: String, backslashIsSeparator: Boolean = true): String =
        normalize(path, backslashIsSeparator = backslashIsSeparator)
            .substringBeforeLast('/', missingDelimiterValue = "")

    fun name(path: String, backslashIsSeparator: Boolean = true): String =
        normalize(path, backslashIsSeparator = backslashIsSeparator).substringAfterLast('/')

    fun usesWindowsSeparators(projectDirectory: String): Boolean =
        windowsProjectPathRegex.containsMatchIn(projectDirectory) ||
            projectDirectory.startsWith("//") ||
            projectDirectory.startsWith("\\\\")

    private companion object {
        val windowsDrivePathRegex = Regex("^[A-Za-z]:")
        val windowsProjectPathRegex = Regex("^[A-Za-z]:[\\\\/]")
    }
}
