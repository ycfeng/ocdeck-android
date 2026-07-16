package io.github.ycfeng.ocdeck.core.util

class PathNormalizer {
    fun normalize(path: String): String {
        val slashNormalized = path.trim().replace('\\', '/')
        if (slashNormalized.isEmpty()) return slashNormalized
        if (slashNormalized == "/") return slashNormalized
        if (windowsDriveRootRegex.matches(slashNormalized)) return slashNormalized.uppercase()

        return slashNormalized.trimEnd('/').ifEmpty { "/" }
    }

    fun comparisonKey(path: String): String {
        val normalized = normalize(path)
        return if (windowsDrivePathRegex.matches(normalized)) normalized.lowercase() else normalized
    }

    fun areSame(left: String, right: String): Boolean = comparisonKey(left) == comparisonKey(right)

    fun dedupe(paths: Iterable<String>): List<String> {
        val seen = linkedSetOf<String>()
        val result = mutableListOf<String>()
        paths.forEach { path ->
            val normalized = normalize(path)
            if (seen.add(comparisonKey(normalized))) {
                result += normalized
            }
        }
        return result
    }

    private companion object {
        val windowsDriveRootRegex = Regex("^[A-Za-z]:/$")
        val windowsDrivePathRegex = Regex("^[A-Za-z]:/.*")
    }
}
