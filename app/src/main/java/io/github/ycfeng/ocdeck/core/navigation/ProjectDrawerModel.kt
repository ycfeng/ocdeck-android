package io.github.ycfeng.ocdeck.core.navigation

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.ProjectRef

internal enum class ProjectDrawerNavigation {
    CloseDrawerOnly,
    OpenProjectHome,
}

internal fun resolveProjectDrawerNavigation(
    activeProject: ActiveProjectDrawerRoute?,
    targetServerId: String,
    targetDirectory: String,
    pathNormalizer: PathNormalizer,
): ProjectDrawerNavigation {
    val alreadyOnProjectHome = activeProject?.let { active ->
        active.serverId == targetServerId &&
            active.sessionId == null &&
            pathNormalizer.areSame(active.directory, targetDirectory)
    } == true
    return if (alreadyOnProjectHome) {
        ProjectDrawerNavigation.CloseDrawerOnly
    } else {
        ProjectDrawerNavigation.OpenProjectHome
    }
}

internal fun mergeProjectDrawerProjects(
    currentProject: ProjectRef,
    recentProjects: List<ProjectRef>,
    pathNormalizer: PathNormalizer,
): List<ProjectRef> {
    val normalizedCurrent = currentProject.normalizedForDrawer(pathNormalizer)
    val currentKey = pathNormalizer.comparisonKey(normalizedCurrent.normalizedDirectory)
    val seen = linkedSetOf<String>()
    var includesCurrent = false
    val merged = buildList {
        recentProjects.forEach { recentProject ->
            val normalizedRecent = recentProject.normalizedForDrawer(pathNormalizer)
            val recentKey = pathNormalizer.comparisonKey(normalizedRecent.normalizedDirectory)
            val project = if (recentKey == currentKey) normalizedCurrent else normalizedRecent
            if (seen.add(recentKey)) {
                add(project)
                includesCurrent = includesCurrent || recentKey == currentKey
            }
        }
    }.toMutableList()

    if (!includesCurrent) {
        merged.add(0, normalizedCurrent)
    }
    return merged
}

private fun ProjectRef.normalizedForDrawer(pathNormalizer: PathNormalizer): ProjectRef {
    val normalizedDirectory = pathNormalizer.normalize(normalizedDirectory)
    val normalizedDisplayName = displayName
        .trim()
        .takeIf { it.trim('/', '\\').isNotEmpty() }
        ?: normalizedDirectory.substringAfterLast('/').ifBlank { normalizedDirectory }
    return copy(
        normalizedDirectory = normalizedDirectory,
        displayName = normalizedDisplayName,
    )
}
