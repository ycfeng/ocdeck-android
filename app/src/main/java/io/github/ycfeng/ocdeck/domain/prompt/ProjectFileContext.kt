package io.github.ycfeng.ocdeck.domain.prompt

object ProjectFileContextLimits {
    const val MAX_CONTEXT_COUNT = 10
}

data class ProjectFileContext(
    val id: String,
    val relativePath: String,
) {
    val filename: String
        get() = relativePath.substringAfterLast('/')

    override fun toString(): String =
        "ProjectFileContext(id=<redacted>, relativePath=<redacted>, filename=<redacted>)"
}
