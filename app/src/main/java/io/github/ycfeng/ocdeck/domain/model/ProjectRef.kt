package io.github.ycfeng.ocdeck.domain.model

data class ProjectRef(
    val normalizedDirectory: String,
    val projectId: String?,
    val displayName: String,
    val vcs: String?,
    val icon: ProjectIcon?,
) {
    override fun toString(): String =
        "ProjectRef(normalizedDirectory=<redacted>, projectId=${redactedIfPresent(projectId)}, " +
            "displayName=<redacted>, vcsPresent=${vcs != null}, iconPresent=${icon != null})"
}

data class ProjectIcon(
    val color: String?,
    val url: String?,
) {
    override fun toString(): String =
        "ProjectIcon(colorPresent=${color != null}, url=${redactedIfPresent(url)})"
}

private fun redactedIfPresent(value: Any?): String = if (value == null) "null" else "<redacted>"
