package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.ProjectKey
import io.github.ycfeng.ocdeck.core.store.SessionListWindowState
import io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageBundle
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class LoadedProjectSnapshot(
    val snapshot: OpenCodeProjectSnapshot,
    val transportIdentity: ServerTransportIdentity,
    val activeSessionMessages: LoadedActiveSessionMessages? = null,
) {
    override fun toString(): String =
        "LoadedProjectSnapshot(snapshotPresent=true, transportIdentity=$transportIdentity, " +
            "activeSessionMessagesPresent=${activeSessionMessages != null})"
}

data class ActiveSessionMessagesRequest(
    val sessionId: String,
    val requestGeneration: Long,
    val expectedRevision: Long,
) {
    override fun toString(): String =
        "ActiveSessionMessagesRequest(sessionId=<redacted>, requestGeneration=$requestGeneration, " +
            "expectedRevision=$expectedRevision)"
}

data class LoadedActiveSessionMessages(
    val sessionId: String,
    val requestGeneration: Long,
    val expectedRevision: Long,
    val bundle: OpenCodeMessageBundle,
    val nextCursor: String? = null,
) {
    override fun toString(): String =
        "LoadedActiveSessionMessages(sessionId=<redacted>, requestGeneration=$requestGeneration, " +
            "expectedRevision=$expectedRevision, " +
            "messageCount=${bundle.messages.size}, partCount=${bundle.parts.size}, " +
            "nextCursorPresent=${nextCursor != null})"
}

interface ProjectSnapshotLoader {
    suspend fun loadProjectSnapshot(
        serverId: String,
        directory: String,
        workspace: String?,
        sessionWindow: SessionListWindowState,
        activeSessionMessages: ActiveSessionMessagesRequest?,
    ): Result<LoadedProjectSnapshot>
}

data class ProjectSnapshotToken(
    val key: ProjectKey,
    val source: ProjectSnapshotSource,
    val generation: Long,
    val recoveryId: Long,
    val sourceId: Long,
    val calibrationId: Long,
    val transportIdentity: ServerTransportIdentity,
) {
    override fun toString(): String =
        "ProjectSnapshotToken(key=$key, source=$source, generation=$generation, recoveryId=$recoveryId, " +
            "sourceId=$sourceId, calibrationId=$calibrationId, transportIdentity=$transportIdentity)"
}

enum class ProjectSnapshotSource {
    Project,
    Global,
}

sealed interface ProjectSnapshotOutcome {
    data class Success(val loaded: LoadedProjectSnapshot) : ProjectSnapshotOutcome {
        override fun toString(): String = "ProjectSnapshotOutcome.Success(loaded=$loaded)"
    }

    data class Failure(val failure: OpenCodeFailure) : ProjectSnapshotOutcome
}

class ProjectSnapshotRequestHandle internal constructor(
    private val cancelAction: () -> Unit,
) {
    private val released = AtomicBoolean()

    fun cancel() {
        if (released.compareAndSet(false, true)) cancelAction()
    }
}

class ProjectSnapshotCoordinator(
    private val loader: ProjectSnapshotLoader,
    private val store: InMemoryOpenCodeStore,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val callbackIds = AtomicLong()
    private val flights = mutableMapOf<ProjectKey, SnapshotFlight>()

    fun request(
        token: ProjectSnapshotToken,
        apply: (InMemoryOpenCodeStore, ProjectSnapshotOutcome) -> Unit,
    ): ProjectSnapshotRequestHandle {
        val callbackId = callbackIds.incrementAndGet()
        var jobToStart: Job? = null
        var jobToCancel: Job? = null

        synchronized(lock) {
            val existing = flights[token.key]
            if (existing != null && existing.token == token) {
                existing.callbacks[callbackId] = apply
            } else {
                jobToCancel = existing?.job
                lateinit var flight: SnapshotFlight
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    runFlight(flight)
                }
                flight = SnapshotFlight(
                    token = token,
                    job = job,
                    callbacks = linkedMapOf(callbackId to apply),
                )
                flights[token.key] = flight
                jobToStart = job
            }
        }

        jobToCancel?.cancel()
        jobToStart?.start()
        return ProjectSnapshotRequestHandle {
            cancelCallback(token, callbackId)
        }
    }

    internal fun activeFlightCount(): Int = synchronized(lock) { flights.size }

    private suspend fun runFlight(flight: SnapshotFlight) {
        val outcome = try {
            val project = store.currentProject(
                serverId = flight.token.key.serverId,
                directory = flight.token.key.normalizedDirectory,
                workspace = flight.token.key.normalizedWorkspace,
            )
            val activeSessionMessages = project.activeSessionId?.let { sessionId ->
                val request = store.beginMessageFirstPageRequest(
                    serverId = flight.token.key.serverId,
                    directory = flight.token.key.normalizedDirectory,
                    sessionId = sessionId,
                    workspace = flight.token.key.normalizedWorkspace,
                )
                ActiveSessionMessagesRequest(
                    sessionId = sessionId,
                    requestGeneration = request.generation,
                    expectedRevision = request.expectedRevision,
                )
            }
            val result = loader.loadProjectSnapshot(
                serverId = flight.token.key.serverId,
                directory = flight.token.key.normalizedDirectory,
                workspace = flight.token.key.normalizedWorkspace,
                sessionWindow = project.sessionListWindow,
                activeSessionMessages = activeSessionMessages,
            )
            result.fold(
                onSuccess = ProjectSnapshotOutcome::Success,
                onFailure = { throwable ->
                    ProjectSnapshotOutcome.Failure(OpenCodeFailureClassifier.classify(throwable))
                },
            )
        } catch (throwable: Throwable) {
            ProjectSnapshotOutcome.Failure(OpenCodeFailureClassifier.classify(throwable))
        }

        val callbacks = synchronized(lock) {
            val current = flights[flight.token.key]
            if (current !== flight) {
                emptyList()
            } else {
                flights.remove(flight.token.key)
                flight.callbacks.values.toList()
            }
        }
        callbacks.forEach { callback -> callback(store, outcome) }
    }

    private fun cancelCallback(token: ProjectSnapshotToken, callbackId: Long) {
        val jobToCancel = synchronized(lock) {
            val flight = flights[token.key]
            if (flight?.token != token) return@synchronized null
            flight.callbacks.remove(callbackId)
            if (flight.callbacks.isEmpty()) {
                flights.remove(token.key)
                flight.job
            } else {
                null
            }
        }
        jobToCancel?.cancel()
    }
}

private class SnapshotFlight(
    val token: ProjectSnapshotToken,
    val job: Job,
    val callbacks: MutableMap<Long, (InMemoryOpenCodeStore, ProjectSnapshotOutcome) -> Unit>,
)
