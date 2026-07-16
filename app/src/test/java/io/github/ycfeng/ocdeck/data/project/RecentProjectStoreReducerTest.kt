package io.github.ycfeng.ocdeck.data.project

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
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
    fun upsertKeepsTwentyPerServerAndDoesNotChangeOtherServers() {
        val otherServer = RecentProjectRecord("server-b", "/workspace/shared", null, "Shared", null)
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
}
