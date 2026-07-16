package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.SessionListMoreState
import io.github.ycfeng.ocdeck.core.store.SessionListWindowLoadRequest
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionListWindowCoordinatorTest {
    private val pathNormalizer = PathNormalizer()

    @Test
    fun rapidClicksBeforeDispatchMergeIntoLargestTarget() = runTest {
        val store = seededStore(rootCount = 25)
        val gate = CompletableDeferred<Result<LoadedSessionListWindow>>()
        val loader = RecordingWindowLoader { request, _ -> gate.await().withRequest(request) }
        val coordinator = SessionListWindowCoordinator(loader, store, pathNormalizer, backgroundScope)

        coordinator.loadMore(serverId, directory)
        coordinator.loadMore(serverId, directory)

        var window = store.captureSessionListWindow(serverId, directory)
        assertEquals(60, window.visibleRootLimit)
        assertEquals(110, window.requestedRawLimit)
        assertEquals(SessionListMoreState.Loading, window.moreState)

        runCurrent()
        assertEquals(listOf(110), loader.requests.map { it.requestedRawLimit })
        gate.complete(Result.success(loaded((1..55).map { session("root-$it") }, rawCount = 55)))
        runCurrent()

        window = store.captureSessionListWindow(serverId, directory)
        assertEquals(SessionListMoreState.EndReached, window.moreState)
        assertEquals(55, store.currentProject(serverId, directory).sessions.count { it.parentId == null })
        assertEquals(0, coordinator.activeFlightCount())
    }

    @Test
    fun failurePreservesTargetAndRetryUsesSameRequestedLimit() = runTest {
        val store = seededStore(rootCount = 25)
        val first = CompletableDeferred<Result<LoadedSessionListWindow>>()
        val second = CompletableDeferred<Result<LoadedSessionListWindow>>()
        val calls = ArrayDeque(listOf(first, second))
        val loader = RecordingWindowLoader { request, _ -> calls.removeFirst().await().withRequest(request) }
        val coordinator = SessionListWindowCoordinator(loader, store, pathNormalizer, backgroundScope)

        coordinator.loadMore(serverId, directory)
        runCurrent()
        first.complete(Result.failure(IllegalStateException("failed")))
        runCurrent()

        var window = store.captureSessionListWindow(serverId, directory)
        assertEquals(SessionListMoreState.Failed, window.moreState)
        assertEquals(40, window.visibleRootLimit)
        assertEquals(90, window.requestedRawLimit)
        assertEquals(25, store.currentProject(serverId, directory).sessions.size)

        coordinator.retry(serverId, directory)
        runCurrent()
        second.complete(Result.success(loaded((1..30).map { session("retry-$it") }, rawCount = 30)))
        runCurrent()

        window = store.captureSessionListWindow(serverId, directory)
        assertEquals(SessionListMoreState.EndReached, window.moreState)
        assertEquals(40, window.visibleRootLimit)
        assertEquals(listOf(90, 90), loader.requests.map { it.requestedRawLimit })
        assertEquals(2, loader.requests.map { it.requestGeneration }.distinct().size)
    }

    @Test
    fun inFlightOldGenerationCannotOverwriteLargerTarget() = runTest {
        val store = seededStore(rootCount = 25)
        val first = CompletableDeferred<Result<LoadedSessionListWindow>>()
        val second = CompletableDeferred<Result<LoadedSessionListWindow>>()
        val calls = ArrayDeque(listOf(first, second))
        val loader = RecordingWindowLoader { request, _ -> calls.removeFirst().await().withRequest(request) }
        val coordinator = SessionListWindowCoordinator(loader, store, pathNormalizer, backgroundScope)

        coordinator.loadMore(serverId, directory)
        runCurrent()
        coordinator.loadMore(serverId, directory)
        first.complete(Result.success(loaded((1..40).map { session("stale-$it") }, rawCount = 90)))
        runCurrent()

        assertEquals(listOf(90, 110), loader.requests.map { it.requestedRawLimit })
        assertTrue(store.currentProject(serverId, directory).sessions.none { it.id.startsWith("stale-") })

        second.complete(Result.success(loaded((1..55).map { session("current-$it") }, rawCount = 55)))
        runCurrent()

        val state = store.currentProject(serverId, directory)
        assertEquals(SessionListMoreState.EndReached, state.sessionListWindow.moreState)
        assertTrue(state.sessions.all { it.id.startsWith("current-") })
    }

    @Test
    fun staleTransportResultIsNotApplied() = runTest {
        val store = seededStore(rootCount = 25)
        val oldIdentity = testTransport(configEpoch = 1L)
        val newIdentity = testTransport(configEpoch = 2L)
        val loader = object : SessionListWindowLoader {
            var calls = 0

            override suspend fun loadSessionListWindow(
                serverId: String,
                directory: String,
                workspace: String?,
                request: SessionListWindowLoadRequest,
            ): Result<LoadedSessionListWindow> {
                calls += 1
                val identity = if (calls == 1) oldIdentity else newIdentity
                val prefix = if (calls == 1) "stale" else "current"
                return Result.success(
                    LoadedSessionListWindow(
                        sessions = (1..30).map { session("$prefix-$it") },
                        requestedRawLimit = request.requestedRawLimit,
                        rawResultCount = 30,
                        requestGeneration = request.requestGeneration,
                        transportIdentity = identity,
                    ),
                )
            }

            override suspend fun isSessionListTransportCurrent(
                serverId: String,
                transportIdentity: ServerTransportIdentity,
            ): Boolean = transportIdentity == newIdentity
        }
        val coordinator = SessionListWindowCoordinator(loader, store, pathNormalizer, backgroundScope)

        coordinator.loadMore(serverId, directory)
        runCurrent()

        val ids = store.currentProject(serverId, directory).sessions.map { it.id }
        assertEquals(2, loader.calls)
        assertTrue(ids.all { it.startsWith("current-") })
    }

    private fun seededStore(rootCount: Int): InMemoryOpenCodeStore = InMemoryOpenCodeStore(pathNormalizer).also { store ->
        store.applyProjectSnapshot(
            OpenCodeProjectSnapshot(
                serverId = serverId,
                normalizedDirectory = directory,
                pathInfo = null,
                sessions = (1..rootCount).map { session("seed-$it") },
                sessionWindowRequestedRawLimit = 70,
                sessionWindowRawResultCount = 70,
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
            ),
        )
    }

    private fun loaded(sessions: List<OpenCodeSession>, rawCount: Int) = LoadedSessionListWindow(
        sessions = sessions,
        requestedRawLimit = 0,
        rawResultCount = rawCount,
        requestGeneration = 0L,
        transportIdentity = testTransport(),
    )

    private fun Result<LoadedSessionListWindow>.withRequest(
        request: SessionListWindowLoadRequest,
    ): Result<LoadedSessionListWindow> = map { loaded ->
        loaded.copy(
            requestedRawLimit = request.requestedRawLimit,
            requestGeneration = request.requestGeneration,
        )
    }

    private fun session(id: String) = OpenCodeSession(
        id = id,
        title = id,
        normalizedDirectory = directory,
        path = null,
        parentId = null,
        agent = null,
        modelLabel = null,
        updatedAt = 1L,
        archivedAt = null,
    )

    private class RecordingWindowLoader(
        private val handler: suspend (SessionListWindowLoadRequest, Int) -> Result<LoadedSessionListWindow>,
    ) : SessionListWindowLoader {
        val requests = mutableListOf<SessionListWindowLoadRequest>()

        override suspend fun loadSessionListWindow(
            serverId: String,
            directory: String,
            workspace: String?,
            request: SessionListWindowLoadRequest,
        ): Result<LoadedSessionListWindow> {
            requests += request
            return handler(request, requests.size)
        }

        override suspend fun isSessionListTransportCurrent(
            serverId: String,
            transportIdentity: ServerTransportIdentity,
        ): Boolean = true
    }

    private companion object {
        const val serverId = "server"
        const val directory = "E:/work/app"
    }
}
