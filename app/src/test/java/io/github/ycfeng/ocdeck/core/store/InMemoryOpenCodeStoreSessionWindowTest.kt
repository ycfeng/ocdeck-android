package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryOpenCodeStoreSessionWindowTest {
    private val store = InMemoryOpenCodeStore(PathNormalizer())

    @Test
    fun exactRawLimitMayHaveMoreAndLessThanLimitEndsBeforeArchiveFiltering() {
        store.applyProjectSnapshot(
            snapshot(
                sessions = listOf(
                    session("root-b", updatedAt = 5L),
                    session("root-c", updatedAt = 5L),
                    session("archived", updatedAt = 9L, archivedAt = 10L),
                    session("child", updatedAt = 8L, parentId = "root-c"),
                    session("root-a", updatedAt = 1L),
                ),
                requestedRawLimit = 70,
                rawResultCount = 70,
            ),
        )

        var state = store.currentProject(serverId, directory)
        assertEquals(SessionListMoreState.MayHaveMore, state.sessionListWindow.moreState)
        assertEquals(70, state.sessionListWindow.rawResultCount)
        assertEquals(listOf("child", "root-c", "root-b", "root-a"), state.sessions.map { it.id })
        assertFalse(state.sessions.any { it.id == "archived" })

        store.applyProjectSnapshot(
            snapshot(
                sessions = listOf(session("root-z")),
                requestedRawLimit = 70,
                rawResultCount = 69,
            ),
        )

        state = store.currentProject(serverId, directory)
        assertEquals(SessionListMoreState.EndReached, state.sessionListWindow.moreState)
        assertEquals(69, state.sessionListWindow.rawResultCount)
    }

    @Test
    fun replacingPrefixPreservesSupplementalSessionsAndDoesNotResurrectRemovedSession() {
        store.applyProjectSnapshot(
            snapshot(
                sessions = listOf(session("root-1"), session("root-2")),
                rawResultCount = 70,
            ),
        )
        store.upsertSession(serverId, directory, session("outside-window", updatedAt = 1L))
        store.upsertSession(serverId, directory, session("child", parentId = "root-1", updatedAt = 2L))

        store.applyProjectSnapshot(
            snapshot(
                sessions = listOf(session("root-1", updatedAt = 5L), session("root-3", updatedAt = 4L)),
                rawResultCount = 70,
            ),
        )

        var ids = store.currentProject(serverId, directory).sessions.map { it.id }.toSet()
        assertEquals(setOf("root-1", "root-3", "outside-window", "child"), ids)

        store.removeSession(serverId, directory, "root-1")
        store.applyProjectSnapshot(
            snapshot(
                sessions = listOf(session("root-1", updatedAt = 6L), session("root-3", updatedAt = 4L)),
                rawResultCount = 70,
            ),
        )

        ids = store.currentProject(serverId, directory).sessions.map { it.id }.toSet()
        assertFalse("root-1" in ids)
        assertTrue("root-3" in ids)
        assertTrue("child" in ids)
    }

    @Test
    fun localExpansionAndNetworkTargetAreSharedButProjectAndWorkspaceRemainIsolated() {
        store.applyProjectSnapshot(
            snapshot(
                sessions = (1..45).map { session("root-$it", updatedAt = it.toLong()) },
                rawResultCount = 70,
            ),
        )

        assertEquals(
            SessionListWindowRequestAction.ExpandedLocally,
            store.requestMoreSessions(serverId, directory),
        )
        var window = store.captureSessionListWindow(serverId, directory)
        assertEquals(40, window.visibleRootLimit)
        assertEquals(70, window.requestedRawLimit)

        assertEquals(
            SessionListWindowRequestAction.LoadRequired,
            store.requestMoreSessions(serverId, directory),
        )
        window = store.captureSessionListWindow(serverId, directory)
        assertEquals(60, window.visibleRootLimit)
        assertEquals(110, window.requestedRawLimit)
        assertEquals(SessionListMoreState.Loading, window.moreState)

        val otherProject = store.captureSessionListWindow(serverId, otherDirectory)
        val otherWorkspace = store.captureSessionListWindow(serverId, directory, workspace = "workspace-a")
        assertEquals(SessionListWindowState(), otherProject)
        assertEquals(SessionListWindowState(), otherWorkspace)
    }

    @Test
    fun realtimeSessionsWinByIdWhenWindowResponseStartedAtOlderRevision() {
        store.applyProjectSnapshot(
            snapshot(
                sessions = (1..25).map { session("root-$it") },
                rawResultCount = 70,
            ),
        )
        store.requestMoreSessions(serverId, directory)
        val request = checkNotNull(store.sessionListWindowLoadRequest(serverId, directory))
        store.upsertSession(serverId, directory, session("root-1", title = "live-title", updatedAt = 100L))
        store.upsertSession(serverId, directory, session("live-new", updatedAt = 99L))

        assertTrue(
            store.applySessionListWindow(
                serverId = serverId,
                directory = directory,
                sessions = (1..40).map { session("root-$it", title = "snapshot-$it") },
                requestedRawLimit = request.requestedRawLimit,
                rawResultCount = request.requestedRawLimit,
                requestGeneration = request.requestGeneration,
                expectedProjectRevision = request.expectedProjectRevision,
            ),
        )

        val state = store.currentProject(serverId, directory)
        assertEquals("live-title", state.sessions.first { it.id == "root-1" }.title)
        assertTrue(state.sessions.any { it.id == "live-new" })
    }

    private fun snapshot(
        sessions: List<OpenCodeSession>,
        requestedRawLimit: Int = 70,
        rawResultCount: Int,
    ) = OpenCodeProjectSnapshot(
        serverId = serverId,
        normalizedDirectory = directory,
        pathInfo = null,
        sessions = sessions,
        sessionWindowRequestedRawLimit = requestedRawLimit,
        sessionWindowRawResultCount = rawResultCount,
        statuses = emptyMap(),
        providerCount = 0,
        models = emptyList(),
        agents = emptyList(),
        commands = emptyList(),
        promptCapabilities = PromptCapabilities(isLoaded = true),
        mcps = emptyList(),
        lsps = emptyList(),
        plugins = emptyList(),
        commandCount = 0,
        permissionCount = 0,
        questionCount = 0,
    )

    private fun session(
        id: String,
        title: String = id,
        updatedAt: Long = 1L,
        parentId: String? = null,
        archivedAt: Long? = null,
    ) = OpenCodeSession(
        id = id,
        title = title,
        normalizedDirectory = directory,
        path = null,
        parentId = parentId,
        agent = null,
        modelLabel = null,
        updatedAt = updatedAt,
        archivedAt = archivedAt,
    )

    private companion object {
        const val serverId = "server"
        const val directory = "E:/work/app"
        const val otherDirectory = "E:/work/other"
    }
}
