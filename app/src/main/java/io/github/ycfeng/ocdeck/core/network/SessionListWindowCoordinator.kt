package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.ProjectKey
import io.github.ycfeng.ocdeck.core.store.SessionListWindowLoadRequest
import io.github.ycfeng.ocdeck.core.store.SessionListWindowRequestAction
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class LoadedSessionListWindow(
    val sessions: List<OpenCodeSession>,
    val requestedRawLimit: Int,
    val rawResultCount: Int,
    val requestGeneration: Long,
    val transportIdentity: ServerTransportIdentity,
) {
    override fun toString(): String =
        "LoadedSessionListWindow(sessionCount=${sessions.size}, requestedRawLimit=$requestedRawLimit, " +
            "rawResultCount=$rawResultCount, requestGeneration=$requestGeneration, " +
            "transportIdentity=$transportIdentity)"
}

interface SessionListWindowLoader {
    suspend fun loadSessionListWindow(
        serverId: String,
        directory: String,
        workspace: String?,
        request: SessionListWindowLoadRequest,
    ): Result<LoadedSessionListWindow>

    suspend fun isSessionListTransportCurrent(
        serverId: String,
        transportIdentity: ServerTransportIdentity,
    ): Boolean
}

class SessionListWindowCoordinator(
    private val loader: SessionListWindowLoader,
    private val store: InMemoryOpenCodeStore,
    private val pathNormalizer: PathNormalizer,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val flights = mutableMapOf<ProjectKey, Job>()

    fun loadMore(serverId: String, directory: String, workspace: String? = null) {
        val key = ProjectKey.create(serverId, directory, workspace, pathNormalizer)
        if (store.requestMoreSessions(serverId, directory, workspace) == SessionListWindowRequestAction.LoadRequired) {
            ensureFlight(key)
        }
    }

    fun retry(serverId: String, directory: String, workspace: String? = null) {
        val key = ProjectKey.create(serverId, directory, workspace, pathNormalizer)
        if (store.retrySessionListWindow(serverId, directory, workspace)) ensureFlight(key)
    }

    internal fun activeFlightCount(): Int = synchronized(lock) { flights.size }

    private fun ensureFlight(key: ProjectKey) {
        var jobToStart: Job? = null
        synchronized(lock) {
            if (flights[key]?.isCompleted == false) return
            lateinit var flight: Job
            flight = scope.launch(start = CoroutineStart.LAZY) { runFlight(key, flight) }
            flights[key] = flight
            jobToStart = flight
        }
        jobToStart?.start()
    }

    private suspend fun runFlight(key: ProjectKey, flight: Job) {
        try {
            while (true) {
                val request = store.sessionListWindowLoadRequest(
                    serverId = key.serverId,
                    directory = key.normalizedDirectory,
                    workspace = key.normalizedWorkspace,
                ) ?: return
                val result = loader.loadSessionListWindow(
                    serverId = key.serverId,
                    directory = key.normalizedDirectory,
                    workspace = key.normalizedWorkspace,
                    request = request,
                )
                val failure = result.exceptionOrNull()
                if (failure != null) {
                    if (failure is CancellationException) throw failure
                    store.failSessionListWindow(
                        serverId = key.serverId,
                        directory = key.normalizedDirectory,
                        requestGeneration = request.requestGeneration,
                        workspace = key.normalizedWorkspace,
                    )
                    continue
                }
                val loaded = result.getOrThrow()
                if (!loader.isSessionListTransportCurrent(key.serverId, loaded.transportIdentity)) continue
                store.applySessionListWindow(
                    serverId = key.serverId,
                    directory = key.normalizedDirectory,
                    sessions = loaded.sessions,
                    requestedRawLimit = loaded.requestedRawLimit,
                    rawResultCount = loaded.rawResultCount,
                    requestGeneration = loaded.requestGeneration,
                    expectedProjectRevision = request.expectedProjectRevision,
                    workspace = key.normalizedWorkspace,
                )
            }
        } finally {
            val restart = synchronized(lock) {
                if (flights[key] === flight) flights.remove(key)
                store.hasPendingSessionListWindowLoad(
                    serverId = key.serverId,
                    directory = key.normalizedDirectory,
                    workspace = key.normalizedWorkspace,
                )
            }
            if (restart) ensureFlight(key)
        }
    }
}
