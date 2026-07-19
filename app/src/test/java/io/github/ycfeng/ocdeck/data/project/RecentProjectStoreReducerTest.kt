package io.github.ycfeng.ocdeck.data.project

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RecentProjectStoreReducerTest {
    private val pathNormalizer = PathNormalizer()

    @Test
    fun legacyBareArrayWithoutSortOrderKeepsArrayOrder() {
        val records = Json.decodeFromString<List<RecentProjectRecord>>(
            """[
                {"serverId":"server-a","normalizedDirectory":"/workspace/beta","displayName":"Beta"},
                {"serverId":"server-a","normalizedDirectory":"/workspace/alpha","displayName":"Alpha"}
            ]""".trimIndent(),
        )

        assertEquals(listOf(null, null), records.map { it.sortOrder })
        assertEquals(
            listOf("/workspace/beta", "/workspace/alpha"),
            recentProjectsForServer(records, "server-a", pathNormalizer).map { it.normalizedDirectory },
        )
    }

    @Test
    fun projectionSortsByNumericSortOrder() {
        val records = listOf(
            record("server-a", "/workspace/gamma", sortOrder = 2),
            record("server-a", "/workspace/alpha", sortOrder = 0),
            record("server-a", "/workspace/beta", sortOrder = 1),
        )

        assertEquals(
            listOf("/workspace/alpha", "/workspace/beta", "/workspace/gamma"),
            recentProjectsForServer(records, "server-a", pathNormalizer).map { it.normalizedDirectory },
        )
    }

    @Test
    fun projectionNormalizesWindowsAliasesAndPreservesMetadataPerServer() {
        val records = listOf(
            RecentProjectRecord("server-a", "C:\\Work\\Alpha\\", "project-a", "Alpha", "git"),
            RecentProjectRecord("server-a", "c:/work/alpha", "stale-id", "Stale", null),
            RecentProjectRecord("server-b", "C:/Work/Alpha", null, "Other Server", null),
        )

        val serverA = recentProjectsForServer(records, "server-a", pathNormalizer)
        val serverB = recentProjectsForServer(records, "server-b", pathNormalizer)

        assertEquals(1, serverA.size)
        assertEquals("C:/Work/Alpha", serverA.single().normalizedDirectory)
        assertEquals("project-a", serverA.single().projectId)
        assertEquals("Alpha", serverA.single().displayName)
        assertEquals("git", serverA.single().vcs)
        assertEquals("Other Server", serverB.single().displayName)
    }

    @Test
    fun firstAddOrUpsertInsertsAtTopAndRenumbersContinuously() {
        val records = listOf(
            record("server-a", "/workspace/alpha", sortOrder = 0),
            record("server-a", "/workspace/beta", sortOrder = 1),
        )

        val next = upsertRecentProjectRecord(
            records = records,
            serverId = "server-a",
            project = project("/workspace/gamma"),
            pathNormalizer = pathNormalizer,
        )

        assertServerOrder(next, "/workspace/gamma", "/workspace/alpha", "/workspace/beta")
    }

    @Test
    fun existingAddAndMetadataUpsertDoNotMoveProject() {
        val records = listOf(
            record("server-a", "/workspace/alpha", sortOrder = 0),
            record("server-a", "/workspace/beta", sortOrder = 1),
        )

        val afterAdd = upsertRecentProjectRecord(
            records = records,
            serverId = "server-a",
            project = recentProjectsForServer(records, "server-a", pathNormalizer).last(),
            pathNormalizer = pathNormalizer,
        )
        val afterUpsert = upsertRecentProjectRecord(
            records = afterAdd,
            serverId = "server-a",
            project = project("/workspace/beta").copy(projectId = "updated-id", displayName = "Updated Beta"),
            pathNormalizer = pathNormalizer,
        )

        assertServerOrder(afterUpsert, "/workspace/alpha", "/workspace/beta")
        val beta = recentProjectsForServer(afterUpsert, "server-a", pathNormalizer).last()
        assertEquals("updated-id", beta.projectId)
        assertEquals("Updated Beta", beta.displayName)
    }

    @Test
    fun reorderUsesSubmittedOrderAndCreatesOnlyExplicitlyEnsuredProject() {
        val records = listOf(
            record("server-a", "/workspace/alpha", sortOrder = 0),
            record("server-a", "/workspace/beta", sortOrder = 1).copy(
                projectId = "persisted-beta-id",
                displayName = "Persisted Beta",
            ),
        )

        val next = reorderRecentProjectRecords(
            records = records,
            serverId = "server-a",
            projects = listOf(
                project("/workspace/gamma").copy(projectId = "gamma-id", displayName = "Gamma from reorder"),
                project("/workspace/beta").copy(projectId = "stale-beta-id", displayName = "Stale Beta"),
            ),
            projectToEnsure = project("/workspace/gamma").copy(
                projectId = "gamma-id",
                displayName = "Gamma from reorder",
            ),
            pathNormalizer = pathNormalizer,
        )

        assertServerOrder(next, "/workspace/gamma", "/workspace/alpha", "/workspace/beta")
        val gamma = recentProjectsForServer(next, "server-a", pathNormalizer).first()
        assertEquals("gamma-id", gamma.projectId)
        assertEquals("Gamma from reorder", gamma.displayName)
        val beta = recentProjectsForServer(next, "server-a", pathNormalizer)[2]
        assertEquals("persisted-beta-id", beta.projectId)
        assertEquals("Persisted Beta", beta.displayName)
    }

    @Test
    fun reorderKeepsOnlyFirstSubmittedAlias() {
        val alpha = project("C:\\Work\\Alpha\\").copy(projectId = "first-id", displayName = "First Alpha")
        val next = reorderRecentProjectRecords(
            records = emptyList(),
            serverId = "server-a",
            projects = listOf(
                alpha,
                project("c:/work/alpha").copy(projectId = "second-id", displayName = "Second Alpha"),
                project("/workspace/beta"),
            ),
            projectToEnsure = alpha,
            pathNormalizer = pathNormalizer,
        )

        assertServerOrder(next, "C:/Work/Alpha")
        val savedAlpha = recentProjectsForServer(next, "server-a", pathNormalizer).first()
        assertEquals("first-id", savedAlpha.projectId)
        assertEquals("First Alpha", savedAlpha.displayName)
    }

    @Test
    fun reorderMergesOmittedCurrentProjectsUsingCurrentAnchors() {
        val records = listOf(
            record("server-a", "/workspace/alpha", sortOrder = 0),
            record("server-a", "/workspace/beta", sortOrder = 1),
            record("server-a", "/workspace/gamma", sortOrder = 2),
        )

        val next = reorderRecentProjectRecords(
            records = records,
            serverId = "server-a",
            projects = listOf(project("/workspace/beta")),
            pathNormalizer = pathNormalizer,
        )

        assertServerOrder(next, "/workspace/alpha", "/workspace/beta", "/workspace/gamma")
    }

    @Test
    fun reorderDoesNotRecreateAProjectDeletedFromCurrentRecords() {
        val records = listOf(record("server-a", "/workspace/alpha", sortOrder = 0))

        val next = reorderRecentProjectRecords(
            records = records,
            serverId = "server-a",
            projects = listOf(project("/workspace/deleted"), project("/workspace/alpha")),
            pathNormalizer = pathNormalizer,
        )

        assertServerOrder(next, "/workspace/alpha")
    }

    @Test
    fun staleReorderPreservesConcurrentNewProjectAtTwentyItemLimit() {
        val records = listOf(record("server-a", "/workspace/new", sortOrder = 0)) +
            (0 until 19).map { index ->
                record("server-a", "/workspace/project-$index", sortOrder = index + 1)
            }

        val next = reorderRecentProjectRecords(
            records = records,
            serverId = "server-a",
            projects = (0 until 20).map { index -> project("/workspace/project-$index") },
            pathNormalizer = pathNormalizer,
        )

        val directories = recentProjectsForServer(next, "server-a", pathNormalizer)
            .map { it.normalizedDirectory }
        assertEquals(20, directories.size)
        assertTrue("/workspace/new" in directories)
        assertTrue("/workspace/project-19" !in directories)
    }

    @Test
    fun ensuredProjectAndStaleDrawerReorderPreserveConcurrentNewProjectAtLimit() {
        val records = listOf(record("server-a", "/workspace/new", sortOrder = 0)) +
            (0 until 19).map { index ->
                record("server-a", "/workspace/project-$index", sortOrder = index + 1)
            }
        val activeProject = project("/workspace/active")

        val next = reorderRecentProjectRecords(
            records = records,
            serverId = "server-a",
            projects = listOf(activeProject) +
                (0 until 19).map { index -> project("/workspace/project-$index") },
            projectToEnsure = activeProject,
            pathNormalizer = pathNormalizer,
        )

        val directories = recentProjectsForServer(next, "server-a", pathNormalizer)
            .map { it.normalizedDirectory }
        assertEquals(20, directories.size)
        assertEquals("/workspace/active", directories.first())
        assertTrue("/workspace/new" in directories)
        assertTrue("/workspace/project-18" !in directories)
    }

    @Test
    fun writesDoNotChangeOtherServers() {
        val otherServer = record("server-b", "C:\\Private\\Shared\\", sortOrder = null)
        val records = listOf(
            otherServer,
            record("server-a", "/workspace/alpha", sortOrder = 0),
        )

        val next = reorderRecentProjectRecords(
            records = records,
            serverId = "server-a",
            projects = listOf(project("/workspace/beta")),
            pathNormalizer = pathNormalizer,
        )

        assertEquals(listOf(otherServer), next.filter { it.serverId == "server-b" })
        assertServerOrder(next, "/workspace/alpha")
    }

    @Test
    fun writesKeepAtMostTwentyProjectsPerServer() {
        val otherServer = record("server-b", "/workspace/shared", sortOrder = null)
        val records = (0 until 25).fold(listOf(otherServer)) { current, index ->
            upsertRecentProjectRecord(
                records = current,
                serverId = "server-a",
                project = project("/workspace/project-$index"),
                pathNormalizer = pathNormalizer,
            )
        }

        val serverA = recentProjectsForServer(records, "server-a", pathNormalizer)
        val serverB = recentProjectsForServer(records, "server-b", pathNormalizer)

        assertEquals(20, serverA.size)
        assertEquals("/workspace/project-24", serverA.first().normalizedDirectory)
        assertEquals("/workspace/project-5", serverA.last().normalizedDirectory)
        assertEquals("/workspace/shared", serverB.single().normalizedDirectory)
        assertNull(serverB.single().projectId)
        assertEquals((0 until 20).toList(), records.filter { it.serverId == "server-a" }.map { it.sortOrder })
    }

    @Test
    fun readFailureClassificationIsSafeAndDoesNotSwallowCancellationOrJvmError() {
        val failures = mutableListOf<RecentProjectFailure>()
        val reporter = RecentProjectFailureReporter { failures += it }

        handleRecentProjectReadFailure(IOException("synthetic I/O detail"), reporter)
        handleRecentProjectReadFailure(SerializationException("synthetic JSON detail"), reporter)

        assertEquals(
            listOf(RecentProjectFailure.ReadIo, RecentProjectFailure.ReadSerialization),
            failures,
        )
        assertTrue(failures.none { it.name.contains("synthetic") })

        val cancellation = CancellationException("synthetic cancellation detail")
        assertSame(
            cancellation,
            assertThrows(CancellationException::class.java) {
                handleRecentProjectReadFailure(cancellation, reporter)
            },
        )
        val error = AssertionError("synthetic JVM detail")
        assertSame(
            error,
            assertThrows(AssertionError::class.java) {
                handleRecentProjectReadFailure(error, reporter)
            },
        )
    }

    private fun project(directory: String) = ProjectRef(
        normalizedDirectory = directory,
        projectId = "id-${directory.substringAfterLast('/')}",
        displayName = directory.substringAfterLast('/'),
        vcs = "git",
        icon = null,
    )

    private fun record(
        serverId: String,
        directory: String,
        sortOrder: Int?,
    ) = RecentProjectRecord(
        serverId = serverId,
        normalizedDirectory = directory,
        projectId = null,
        displayName = directory.substringAfterLast('/'),
        vcs = null,
        sortOrder = sortOrder,
    )

    private fun assertServerOrder(records: List<RecentProjectRecord>, vararg directories: String) {
        val serverRecords = records.filter { it.serverId == "server-a" }
        assertEquals(directories.toList(), serverRecords.map { it.normalizedDirectory })
        assertEquals(directories.indices.toList(), serverRecords.map { it.sortOrder })
    }
}
