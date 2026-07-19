package io.github.ycfeng.ocdeck.core.navigation

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectDrawerModelTest {
    private val pathNormalizer = PathNormalizer()

    @Test
    fun prependsCurrentProjectWhenItIsMissingFromRecents() {
        val current = project("E:\\work\\current", "Current")
        val recents = listOf(
            project("E:/work/one", "One"),
            project("E:/work/two", "Two"),
        )

        val result = mergeProjectDrawerProjects(current, recents, pathNormalizer)

        assertEquals(
            listOf("E:/work/current", "E:/work/one", "E:/work/two"),
            result.map(ProjectRef::normalizedDirectory),
        )
    }

    @Test
    fun replacesWindowsPathAliasWithCurrentMetadataWithoutReordering() {
        val current = project("E:\\Work\\Current\\", "Current live")
        val recents = listOf(
            project("E:/work/one", "One"),
            project("e:/work/current", "Current stale"),
            project("E:/WORK/CURRENT/", "Current duplicate"),
            project("E:/work/two", "Two"),
        )

        val result = mergeProjectDrawerProjects(current, recents, pathNormalizer)

        assertEquals(
            listOf("E:/work/one", "E:/Work/Current", "E:/work/two"),
            result.map(ProjectRef::normalizedDirectory),
        )
        assertEquals("Current live", result[1].displayName)
    }

    @Test
    fun fallsBackToDirectoryNameForRootOnlyServerMetadata() {
        val result = mergeProjectDrawerProjects(
            currentProject = project("E:/work/current", "/"),
            recentProjects = emptyList(),
            pathNormalizer = pathNormalizer,
        )

        assertEquals("current", result.single().displayName)
    }

    @Test
    fun currentProjectInsertionKeepsDrawerProjectListBounded() {
        val current = project("/workspace/current", "Current")
        val recents = (0 until 20).map { index ->
            project("/workspace/project-$index", "Project $index")
        }

        val result = mergeProjectDrawerProjects(current, recents, pathNormalizer)

        assertEquals(20, result.size)
        assertEquals("/workspace/current", result.first().normalizedDirectory)
        assertEquals("/workspace/project-18", result.last().normalizedDirectory)
    }

    @Test
    fun reorderEnsuresOnlyAnActiveProjectMissingFromPersistentRecents() {
        val currentProject = project("C:/Work/Alpha", "Alpha")

        assertEquals(
            currentProject,
            projectToEnsureForDrawer(currentProject, emptyList(), pathNormalizer),
        )
        assertNull(
            projectToEnsureForDrawer(
                currentProject = currentProject,
                recentProjects = listOf(project("c:\\work\\alpha\\", "Stored Alpha")),
                pathNormalizer = pathNormalizer,
            ),
        )
    }

    @Test
    fun closesOnlyWhenSelectingTheAlreadyVisibleProjectHome() {
        val activeProjectHome = ActiveProjectDrawerRoute(
            serverId = "server",
            directory = "E:/Work/Current",
            sessionId = null,
        )

        assertEquals(
            ProjectDrawerNavigation.CloseDrawerOnly,
            resolveProjectDrawerNavigation(
                activeProject = activeProjectHome,
                targetServerId = "server",
                targetDirectory = "e:\\work\\current\\",
                pathNormalizer = pathNormalizer,
            ),
        )
    }

    @Test
    fun distinguishesSessionExitFromProjectToProjectNavigation() {
        val activeSession = ActiveProjectDrawerRoute(
            serverId = "server",
            directory = "E:/work/current",
            sessionId = "session",
        )

        assertEquals(
            ProjectDrawerNavigation.OpenProjectHomeFromSession,
            resolveProjectDrawerNavigation(
                activeProject = activeSession,
                targetServerId = "server",
                targetDirectory = "E:/work/current",
                pathNormalizer = pathNormalizer,
            ),
        )
        assertEquals(
            ProjectDrawerNavigation.OpenProjectHomeFromSession,
            resolveProjectDrawerNavigation(
                activeProject = activeSession,
                targetServerId = "other-server",
                targetDirectory = "E:/work/other",
                pathNormalizer = pathNormalizer,
            ),
        )
        assertEquals(
            ProjectDrawerNavigation.OpenProjectHome,
            resolveProjectDrawerNavigation(
                activeProject = activeSession.copy(sessionId = null),
                targetServerId = "server",
                targetDirectory = "E:/work/other",
                pathNormalizer = pathNormalizer,
            ),
        )
    }

    private fun project(directory: String, displayName: String) = ProjectRef(
        normalizedDirectory = directory,
        projectId = null,
        displayName = displayName,
        vcs = null,
        icon = null,
    )
}
