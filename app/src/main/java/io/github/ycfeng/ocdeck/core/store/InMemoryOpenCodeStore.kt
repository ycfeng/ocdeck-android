package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageBundle
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionStatus
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.ui.text.UiText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class ProjectEventReduction(
    val before: OpenCodeProjectState,
    val after: OpenCodeProjectState,
    val event: JsonObject,
) {
    override fun toString(): String =
        "ProjectEventReduction(beforeRevision=${before.projectDataRevision}, " +
            "afterRevision=${after.projectDataRevision}, event=<redacted>)"
}

internal enum class OptimisticMessageRemovalResult {
    Removed,
    Confirmed,
    Missing,
}

internal enum class MessagePageApplyResult {
    Applied,
    Stale,
    CursorCycle,
}

class InMemoryOpenCodeStore(
    private val pathNormalizer: PathNormalizer,
    private val eventReducer: OpenCodeEventReducer = OpenCodeEventReducer(),
) {
    private val state = MutableStateFlow(OpenCodeRuntimeState())
    private val messageFirstPageGeneration = AtomicLong()

    fun observeProject(serverId: String, directory: String, workspace: String? = null): Flow<OpenCodeProjectState> {
        val key = key(serverId, directory, workspace)
        return state.map { it.projects[key] ?: emptyProject(key) }
    }

    fun observeGlobalConnection(serverId: String): Flow<SseConnectionStatus> =
        state.map { it.globalConnectionStatuses[serverId] ?: SseConnectionStatus.Closed }

    fun captureProjectDataRevision(serverId: String, directory: String, workspace: String? = null): Long {
        val key = key(serverId, directory, workspace)
        return state.value.projects[key]?.projectDataRevision ?: 0L
    }

    fun captureMessageDataRevision(serverId: String, directory: String, workspace: String? = null): Long {
        val key = key(serverId, directory, workspace)
        return state.value.projects[key]?.messageDataRevision ?: 0L
    }

    internal fun beginMessageFirstPageRequest(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): MessageFirstPageRequest {
        val key = key(serverId, directory, workspace)
        val generation = messageFirstPageGeneration.incrementAndGet()
        var expectedRevision = 0L
        state.update { runtime ->
            val project = runtime.project(key)
            expectedRevision = project.messageDataRevision
            val currentGeneration = project.messageFirstPageRequestGenerations[sessionId] ?: 0L
            if (currentGeneration >= generation) {
                runtime
            } else {
                val next = project.copy(
                    messageFirstPageRequestGenerations = project.messageFirstPageRequestGenerations +
                        (sessionId to generation),
                )
                runtime.copy(projects = runtime.projects + (key to next))
            }
        }
        return MessageFirstPageRequest(generation, expectedRevision)
    }

    fun captureSessionListWindow(
        serverId: String,
        directory: String,
        workspace: String? = null,
    ): SessionListWindowState {
        val key = key(serverId, directory, workspace)
        return state.value.projects[key]?.sessionListWindow ?: SessionListWindowState()
    }

    fun requestMoreSessions(
        serverId: String,
        directory: String,
        workspace: String? = null,
    ): SessionListWindowRequestAction {
        val key = key(serverId, directory, workspace)
        var action = SessionListWindowRequestAction.Ignored
        state.update { runtime ->
            val project = runtime.project(key)
            val window = project.sessionListWindow
            val target = window.visibleRootLimit.saturatedPlus(SessionListPageSize)
            val loadedRootCount = project.sessions.count { it.parentId.isNullOrBlank() }
            val nextWindow = when {
                loadedRootCount >= target -> {
                    action = SessionListWindowRequestAction.ExpandedLocally
                    window.copy(
                        visibleRootLimit = target,
                        moreState = if (window.moreState == SessionListMoreState.Failed) {
                            SessionListMoreState.MayHaveMore
                        } else {
                            window.moreState
                        },
                    )
                }
                window.moreState == SessionListMoreState.EndReached -> {
                    action = SessionListWindowRequestAction.Ignored
                    window
                }
                else -> {
                    action = SessionListWindowRequestAction.LoadRequired
                    window.copy(
                        visibleRootLimit = target,
                        requestedRawLimit = maxOf(
                            window.requestedRawLimit,
                            target.saturatedPlus(SessionListRawHeadroom),
                        ),
                        moreState = SessionListMoreState.Loading,
                        requestGeneration = window.requestGeneration + 1L,
                    )
                }
            }
            if (nextWindow == window) {
                runtime
            } else {
                runtime.copy(projects = runtime.projects + (key to project.copy(sessionListWindow = nextWindow)))
            }
        }
        return action
    }

    fun retrySessionListWindow(
        serverId: String,
        directory: String,
        workspace: String? = null,
    ): Boolean {
        val key = key(serverId, directory, workspace)
        var requested = false
        state.update { runtime ->
            val project = runtime.project(key)
            val window = project.sessionListWindow
            if (window.moreState != SessionListMoreState.Failed) {
                runtime
            } else {
                requested = true
                runtime.copy(
                    projects = runtime.projects + (
                        key to project.copy(
                            sessionListWindow = window.copy(
                                moreState = SessionListMoreState.Loading,
                                requestGeneration = window.requestGeneration + 1L,
                            ),
                        )
                        ),
                )
            }
        }
        return requested
    }

    internal fun sessionListWindowLoadRequest(
        serverId: String,
        directory: String,
        workspace: String? = null,
    ): SessionListWindowLoadRequest? {
        val project = currentProject(serverId, directory, workspace)
        val window = project.sessionListWindow
        return if (window.moreState == SessionListMoreState.Loading) {
            SessionListWindowLoadRequest(
                requestedRawLimit = window.requestedRawLimit,
                requestGeneration = window.requestGeneration,
                expectedProjectRevision = project.projectDataRevision,
            )
        } else {
            null
        }
    }

    internal fun hasPendingSessionListWindowLoad(
        serverId: String,
        directory: String,
        workspace: String? = null,
    ): Boolean = currentProject(serverId, directory, workspace).sessionListWindow.moreState == SessionListMoreState.Loading

    internal fun applySessionListWindow(
        serverId: String,
        directory: String,
        sessions: List<OpenCodeSession>,
        requestedRawLimit: Int,
        rawResultCount: Int,
        requestGeneration: Long,
        expectedProjectRevision: Long,
        workspace: String? = null,
    ): Boolean {
        val key = key(serverId, directory, workspace)
        var applied = false
        state.update { runtime ->
            val project = runtime.project(key)
            val window = project.sessionListWindow
            if (
                window.moreState != SessionListMoreState.Loading ||
                window.requestGeneration != requestGeneration ||
                window.requestedRawLimit != requestedRawLimit
            ) {
                runtime
            } else {
                applied = true
                val next = project.applySessionWindow(
                    rawSessions = sessions,
                    requestedRawLimit = requestedRawLimit,
                    rawResultCount = rawResultCount,
                    requestGeneration = requestGeneration,
                    preserveCurrentById = project.projectDataRevision != expectedProjectRevision,
                ).copy(projectDataRevision = project.projectDataRevision + 1L)
                runtime.copy(projects = runtime.projects + (key to next))
            }
        }
        return applied
    }

    internal fun failSessionListWindow(
        serverId: String,
        directory: String,
        requestGeneration: Long,
        workspace: String? = null,
    ): Boolean {
        val key = key(serverId, directory, workspace)
        var applied = false
        state.update { runtime ->
            val project = runtime.project(key)
            val window = project.sessionListWindow
            if (window.moreState != SessionListMoreState.Loading || window.requestGeneration != requestGeneration) {
                runtime
            } else {
                applied = true
                runtime.copy(
                    projects = runtime.projects + (
                        key to project.copy(sessionListWindow = window.copy(moreState = SessionListMoreState.Failed))
                        ),
                )
            }
        }
        return applied
    }

    fun setProjectLoading(
        serverId: String,
        directory: String,
        isLoading: Boolean,
        error: UiText? = null,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            runtime.copy(projects = runtime.projects + (key to runtime.project(key).copy(isLoading = isLoading, error = error)))
        }
    }

    fun applyProjectSnapshot(snapshot: OpenCodeProjectSnapshot) {
        val key = key(snapshot.serverId, snapshot.normalizedDirectory, snapshot.workspace)
        state.update { runtime ->
            val current = runtime.project(key)
            runtime.copy(projects = runtime.projects + (key to current.applySnapshot(snapshot)))
        }
    }

    fun applyProjectSnapshotIfRevision(snapshot: OpenCodeProjectSnapshot, expectedRevision: Long): Boolean {
        val key = key(snapshot.serverId, snapshot.normalizedDirectory, snapshot.workspace)
        var applied = false
        state.update { runtime ->
            val current = runtime.project(key)
            if (current.projectDataRevision != expectedRevision) {
                applied = false
                runtime
            } else {
                applied = true
                runtime.copy(projects = runtime.projects + (key to current.applySnapshot(snapshot)))
            }
        }
        return applied
    }

    fun updateProject(serverId: String, directory: String, project: ProjectRef, workspace: String? = null) {
        val key = key(serverId, directory, workspace)
        val normalizedProject = project.copy(normalizedDirectory = pathNormalizer.normalize(project.normalizedDirectory))
        state.update { runtime ->
            val current = runtime.project(key)
            val next = current.copy(project = normalizedProject, error = null).withDataRevisionsFrom(current)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    fun putMessages(
        serverId: String,
        directory: String,
        sessionId: String,
        bundle: OpenCodeMessageBundle,
        expectedRevision: Long,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            if (project.removedSessionRevisions[sessionId]?.let { it > expectedRevision } == true) {
                return@update runtime
            }
            val next = if (project.messageDataRevision == expectedRevision) {
                project.replaceMessages(sessionId, bundle)
            } else {
                project.mergeMessages(sessionId, bundle, expectedRevision)
            }.copy(error = null).withDataRevisionsFrom(project)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    internal fun putMessagePage(
        serverId: String,
        directory: String,
        sessionId: String,
        page: OpenCodeMessagePage,
        before: String?,
        expectedRevision: Long,
        firstPageRequestGeneration: Long? = null,
        workspace: String? = null,
    ): MessagePageApplyResult {
        val key = key(serverId, directory, workspace)
        var result = MessagePageApplyResult.Stale
        state.update { runtime ->
            result = MessagePageApplyResult.Stale
            val project = runtime.project(key)
            if (
                before == null &&
                (
                    firstPageRequestGeneration == null ||
                        project.messageFirstPageRequestGenerations[sessionId] != firstPageRequestGeneration
                    )
            ) {
                return@update runtime
            }
            if (project.removedSessionRevisions[sessionId]?.let { it > expectedRevision } == true) {
                return@update runtime
            }
            val currentHistory = project.messageHistoryBySession[sessionId]
            if (before != null) {
                if (currentHistory?.nextCursor != before) return@update runtime
                if (
                    page.nextCursor == before ||
                    page.nextCursor?.let { it in currentHistory.consumedCursors } == true
                ) {
                    result = MessagePageApplyResult.CursorCycle
                    return@update runtime
                }
            }
            val messagesUpdated = when {
                before == null &&
                    currentHistory == null &&
                    project.messageDataRevision == expectedRevision ->
                    project.replaceMessages(sessionId, page.bundle)
                before == null &&
                    currentHistory != null &&
                    project.messageDataRevision == expectedRevision &&
                    (page.bundle.messages.isNotEmpty() || page.nextCursor == null) ->
                    project.reconcileFirstMessagePage(sessionId, page.bundle)
                else -> project.mergeMessages(sessionId, page.bundle, expectedRevision)
            }
            val nextHistory = if (before == null) {
                val latestPageMessageIds = page.bundle.messages.mapTo(mutableSetOf(), OpenCodeMessage::id)
                when {
                    currentHistory == null -> MessageHistoryState(
                        nextCursor = page.nextCursor,
                        latestPageMessageIds = latestPageMessageIds,
                    )
                    latestPageMessageIds.any { it in currentHistory.latestPageMessageIds } -> currentHistory.copy(
                        latestPageMessageIds = latestPageMessageIds,
                    )
                    else -> MessageHistoryState(
                        nextCursor = page.nextCursor,
                        latestPageMessageIds = latestPageMessageIds,
                    )
                }
            } else {
                checkNotNull(currentHistory).copy(
                    nextCursor = page.nextCursor,
                    consumedCursors = currentHistory.consumedCursors + before,
                )
            }
            val next = messagesUpdated.copy(
                messageHistoryBySession = messagesUpdated.messageHistoryBySession +
                    (sessionId to nextHistory),
                messageFirstPageRequestGenerations = if (before == null) {
                    messagesUpdated.messageFirstPageRequestGenerations - sessionId
                } else {
                    messagesUpdated.messageFirstPageRequestGenerations
                },
                error = null,
            ).withDataRevisionsFrom(project)
            result = MessagePageApplyResult.Applied
            runtime.copy(projects = runtime.projects + (key to next))
        }
        return result
    }

    fun upsertSession(serverId: String, directory: String, session: OpenCodeSession, workspace: String? = null) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val sessions = if (session.archivedAt == null) {
                (project.sessions.filterNot { it.id == session.id } + session).sortedForDisplay()
            } else {
                project.sessions.filterNot { it.id == session.id }
            }
            val next = project.copy(sessions = sessions).withDataRevisionsFrom(project)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    fun removeSession(serverId: String, directory: String, sessionId: String, workspace: String? = null) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val messageIds = project.messagesBySession[sessionId].orEmpty().map { it.id }.toSet()
            val reduced = project.copy(
                sessions = project.sessions.filterNot { it.id == sessionId },
                activeSessionId = if (project.activeSessionId == sessionId) null else project.activeSessionId,
                messagesBySession = project.messagesBySession - sessionId,
                partsByMessage = project.partsByMessage - messageIds,
                messageHistoryBySession = project.messageHistoryBySession - sessionId,
                messageFirstPageRequestGenerations = project.messageFirstPageRequestGenerations - sessionId,
                permissionsBySession = project.permissionsBySession - sessionId,
                questionsBySession = project.questionsBySession - sessionId,
                statuses = project.statuses - sessionId,
                permissionCount = (project.permissionsBySession - sessionId).totalPermissionCount(),
                questionCount = (project.questionsBySession - sessionId).totalQuestionCount(),
            )
            val next = reduced.withForcedRemoval(project, MessageRemoval.Session(sessionId))
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    fun upsertMessage(serverId: String, directory: String, message: OpenCodeMessage, workspace: String? = null) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val next = project.upsertMessage(message).withDataRevisionsFrom(project)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    internal fun upsertOptimisticMessageIfPromptCapabilitiesRevision(
        serverId: String,
        directory: String,
        message: OpenCodeMessage,
        expectedCapabilitiesRevision: Long,
        workspace: String? = null,
    ): Boolean {
        val key = key(serverId, directory, workspace)
        var inserted = false
        state.update { runtime ->
            val project = runtime.project(key)
            if (
                !project.promptCapabilities.isLoaded ||
                project.promptCapabilities.revision != expectedCapabilitiesRevision
            ) {
                inserted = false
                runtime
            } else {
                inserted = true
                val next = project.upsertMessage(message).withDataRevisionsFrom(project)
                runtime.copy(projects = runtime.projects + (key to next))
            }
        }
        return inserted
    }

    fun moveSessionMessages(
        serverId: String,
        directory: String,
        fromSessionId: String,
        toSessionId: String,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val moved = project.messagesBySession[fromSessionId].orEmpty().map { it.copy(sessionId = toSessionId) }
            val movedMessageIds = moved.map { it.id }.toSet()
            val nextMessages = project.messagesBySession - fromSessionId +
                (toSessionId to (project.messagesBySession[toSessionId].orEmpty() + moved)
                    .distinctBy(OpenCodeMessage::id)
                    .sortedForTimeline())
            val nextParts = project.partsByMessage.mapValues { (messageId, parts) ->
                if (messageId in movedMessageIds) parts.map { it.copy(sessionId = toSessionId) } else parts
            }
            val next = project.copy(messagesBySession = nextMessages, partsByMessage = nextParts)
                .withDataRevisionsFrom(project)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    fun moveMessage(
        serverId: String,
        directory: String,
        fromSessionId: String,
        toSessionId: String,
        messageId: String,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val sourceMessages = project.messagesBySession[fromSessionId].orEmpty()
            val sourceMessage = sourceMessages.firstOrNull { it.id == messageId } ?: return@update runtime
            val movedParts = project.partsByMessage[messageId].orEmpty().map { it.copy(sessionId = toSessionId) }
            val movedMessage = sourceMessage.copy(
                sessionId = toSessionId,
                parts = sourceMessage.parts.map { it.copy(sessionId = toSessionId) },
            )
            val remainingSource = sourceMessages.filterNot { it.id == messageId }
            val existingTarget = project.messagesBySession[toSessionId].orEmpty().firstOrNull { it.id == messageId }
            val targetMessage = existingTarget?.takeUnless { it.isOptimistic } ?: movedMessage
            val targetMessages = (project.messagesBySession[toSessionId].orEmpty().filterNot { it.id == messageId } + targetMessage)
                .sortedForTimeline()
            var nextMessages = project.messagesBySession + (toSessionId to targetMessages)
            nextMessages = if (remainingSource.isEmpty()) {
                nextMessages - fromSessionId
            } else {
                nextMessages + (fromSessionId to remainingSource)
            }
            val nextParts = if (movedParts.isEmpty()) {
                project.partsByMessage
            } else {
                project.partsByMessage + (messageId to movedParts)
            }
            val next = project.copy(messagesBySession = nextMessages, partsByMessage = nextParts)
                .withDataRevisionsFrom(project)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    fun removeMessage(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val messages = project.messagesBySession[sessionId].orEmpty().filterNot { it.id == messageId }
            val reduced = project.copy(
                messagesBySession = project.messagesBySession + (sessionId to messages),
                partsByMessage = project.partsByMessage - messageId,
            )
            val next = reduced.withForcedRemoval(project, MessageRemoval.Message(messageId))
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    internal fun removeMessageIfOptimistic(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        workspace: String? = null,
    ): OptimisticMessageRemovalResult {
        val key = key(serverId, directory, workspace)
        var result = OptimisticMessageRemovalResult.Missing
        state.update { runtime ->
            val project = runtime.project(key)
            val message = project.messagesBySession[sessionId].orEmpty().firstOrNull { it.id == messageId }
            when {
                message == null -> {
                    result = OptimisticMessageRemovalResult.Missing
                    runtime
                }
                !message.isOptimistic -> {
                    result = OptimisticMessageRemovalResult.Confirmed
                    runtime
                }
                else -> {
                    result = OptimisticMessageRemovalResult.Removed
                    val messages = project.messagesBySession[sessionId].orEmpty().filterNot { it.id == messageId }
                    val reduced = project.copy(
                        messagesBySession = project.messagesBySession + (sessionId to messages),
                        partsByMessage = project.partsByMessage - messageId,
                    )
                    val next = reduced.withForcedRemoval(project, MessageRemoval.Message(messageId))
                    runtime.copy(projects = runtime.projects + (key to next))
                }
            }
        }
        return result
    }

    fun markMessageAccepted(
        serverId: String,
        directory: String,
        sessionId: String,
        messageId: String,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val messages = project.messagesBySession[sessionId].orEmpty().map { message ->
                if (message.id == messageId) message.copy(isOptimistic = false) else message
            }
            val next = project.copy(messagesBySession = project.messagesBySession + (sessionId to messages))
                .withDataRevisionsFrom(project)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    fun setSessionStatus(
        serverId: String,
        directory: String,
        sessionId: String,
        status: OpenCodeSessionStatus,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val next = project.copy(statuses = project.statuses + (sessionId to status)).withDataRevisionsFrom(project)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    fun removeQuestionRequest(
        serverId: String,
        directory: String,
        sessionId: String,
        requestId: String,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val nextQuestions = project.questionsBySession.removeQuestion(sessionId, requestId)
            val next = project.copy(
                questionsBySession = nextQuestions,
                questionCount = nextQuestions.totalQuestionCount(),
            ).withDataRevisionsFrom(project)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    fun removePermissionRequest(
        serverId: String,
        directory: String,
        sessionId: String,
        requestId: String,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            val nextPermissions = project.permissionsBySession.removePermission(sessionId, requestId)
            val next = project.copy(
                permissionsBySession = nextPermissions,
                permissionCount = nextPermissions.totalPermissionCount(),
            ).withDataRevisionsFrom(project)
            runtime.copy(projects = runtime.projects + (key to next))
        }
    }

    fun setProjectConnectionStatus(
        serverId: String,
        directory: String,
        status: SseConnectionStatus,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            runtime.copy(projects = runtime.projects + (key to project.copy(connectionStatus = status)))
        }
    }

    fun setGlobalConnectionStatus(serverId: String, status: SseConnectionStatus) {
        state.update { runtime ->
            runtime.copy(globalConnectionStatuses = runtime.globalConnectionStatuses + (serverId to status))
        }
    }

    fun setActiveSession(serverId: String, directory: String, sessionId: String?, workspace: String? = null) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            runtime.copy(projects = runtime.projects + (key to project.copy(activeSessionId = sessionId)))
        }
    }

    fun markSessionNotificationsViewed(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            runtime.copy(
                projects = runtime.projects + (
                    key to project.copy(notifications = project.notifications.markSessionViewed(sessionId))
                    ),
            )
        }
    }

    fun markProjectNotificationsViewed(serverId: String, directory: String, workspace: String? = null) {
        val key = key(serverId, directory, workspace)
        state.update { runtime ->
            val project = runtime.project(key)
            runtime.copy(
                projects = runtime.projects + (
                    key to project.copy(notifications = project.notifications.markProjectViewed(key.normalizedDirectory))
                    ),
            )
        }
    }

    fun reduceProjectEvent(
        serverId: String,
        directory: String,
        event: JsonObject,
        workspace: String? = null,
    ): ProjectEventReduction {
        val key = key(serverId, directory, workspace)
        var reduction: ProjectEventReduction? = null
        state.update { runtime ->
            val project = runtime.project(key)
            val reduced = eventReducer.reduce(project, event)
            val nextProject = event.messageRemoval()
                ?.let { reduced.withForcedRemoval(project, it) }
                ?: reduced.withDataRevisionsFrom(project)
            reduction = ProjectEventReduction(project, nextProject, event)
            runtime.copy(projects = runtime.projects + (key to nextProject))
        }
        return checkNotNull(reduction)
    }

    internal fun currentProject(serverId: String, directory: String, workspace: String? = null): OpenCodeProjectState {
        val projectKey = key(serverId, directory, workspace)
        return state.value.projects[projectKey] ?: emptyProject(projectKey)
    }

    internal fun currentGlobalConnectionStatus(serverId: String): SseConnectionStatus =
        state.value.globalConnectionStatuses[serverId] ?: SseConnectionStatus.Closed

    private fun key(serverId: String, directory: String, workspace: String? = null): ProjectKey = ProjectKey.create(
        serverId = serverId,
        directory = directory,
        workspace = workspace,
        pathNormalizer = pathNormalizer,
    )

    private fun OpenCodeRuntimeState.project(key: ProjectKey): OpenCodeProjectState = projects[key] ?: emptyProject(key)

    private fun emptyProject(key: ProjectKey): OpenCodeProjectState = OpenCodeProjectState(
        serverId = key.serverId,
        normalizedDirectory = key.normalizedDirectory,
        workspace = key.normalizedWorkspace,
    )
}

private fun OpenCodeProjectState.upsertMessage(message: OpenCodeMessage): OpenCodeProjectState {
    val nextParts = if (message.parts.isEmpty()) {
        partsByMessage
    } else {
        partsByMessage + (message.id to message.parts)
    }
    return copy(
        messagesBySession = messagesBySession.upsert(message),
        partsByMessage = nextParts,
    )
}

private fun OpenCodeProjectState.applySnapshot(snapshot: OpenCodeProjectSnapshot): OpenCodeProjectState {
    val permissionsBySession = snapshot.permissions.groupPermissionsBySession()
    val questionsBySession = snapshot.questions.groupQuestionsBySession()
    val capabilities = snapshot.promptCapabilities.copy(
        models = snapshot.promptCapabilities.models.map { it.copy(variants = it.variants.toList()) },
        agents = snapshot.promptCapabilities.agents.toList(),
        commands = snapshot.promptCapabilities.commands.toList(),
        serverDefaultModels = snapshot.promptCapabilities.serverDefaultModels.toList(),
    )
    val windowed = if (
        snapshot.sessionWindowRequestGeneration == sessionListWindow.requestGeneration &&
        snapshot.sessionWindowRequestedRawLimit >= sessionListWindow.requestedRawLimit
    ) {
        applySessionWindow(
            rawSessions = snapshot.sessions,
            requestedRawLimit = snapshot.sessionWindowRequestedRawLimit,
            rawResultCount = snapshot.sessionWindowRawResultCount,
            requestGeneration = snapshot.sessionWindowRequestGeneration,
            preserveCurrentById = false,
        )
    } else {
        this
    }
    return windowed.copy(
        project = snapshot.project ?: project,
        pathInfo = snapshot.pathInfo,
        permissionsBySession = permissionsBySession,
        questionsBySession = questionsBySession,
        statuses = snapshot.statuses,
        providerCount = snapshot.providerCount,
        models = capabilities.models,
        agents = capabilities.agents,
        commands = capabilities.commands,
        promptCapabilities = capabilities,
        mcps = snapshot.mcps,
        lsps = snapshot.lsps,
        plugins = snapshot.plugins,
        commandCount = snapshot.commandCount,
        permissionCount = permissionsBySession.totalPermissionCount(),
        questionCount = questionsBySession.totalQuestionCount(),
        isLoading = false,
        error = null,
        projectDataRevision = projectDataRevision + 1,
    )
}

private fun OpenCodeProjectState.applySessionWindow(
    rawSessions: List<OpenCodeSession>,
    requestedRawLimit: Int,
    rawResultCount: Int,
    requestGeneration: Long,
    preserveCurrentById: Boolean,
): OpenCodeProjectState {
    val incomingRootIds = rawSessions
        .asSequence()
        .filter { it.parentId.isNullOrBlank() }
        .map(OpenCodeSession::id)
        .toSet()
    val currentById = if (preserveCurrentById) sessions.associateBy(OpenCodeSession::id) else emptyMap()
    val incoming = rawSessions
        .visibleForDisplay()
        .filterNot { it.id in removedSessionRevisions }
        .map { session -> currentById[session.id] ?: session }
        .distinctBy(OpenCodeSession::id)
    val incomingIds = incoming.mapTo(mutableSetOf(), OpenCodeSession::id)
    val retainedSupplemental = sessions.filterNot { session ->
        session.id in sessionListWindow.loadedRootSessionIds ||
            session.id in incomingRootIds ||
            session.id in incomingIds
    }
    val nextSessions = (retainedSupplemental + incoming)
        .distinctBy(OpenCodeSession::id)
        .sortedForDisplay()
    return copy(
        sessions = nextSessions,
        sessionListWindow = sessionListWindow.copy(
            requestedRawLimit = requestedRawLimit,
            rawResultCount = rawResultCount,
            moreState = if (rawResultCount < requestedRawLimit) {
                SessionListMoreState.EndReached
            } else {
                SessionListMoreState.MayHaveMore
            },
            requestGeneration = requestGeneration,
            loadedRootSessionIds = incomingRootIds,
        ),
    )
}

private fun OpenCodeProjectState.withDataRevisionsFrom(before: OpenCodeProjectState): OpenCodeProjectState {
    val messageChanged = !hasSameMessageData(before)
    val nextMessageRevision = if (messageChanged) before.messageDataRevision + 1 else before.messageDataRevision
    if (!messageChanged) {
        return copy(
            projectDataRevision = if (hasSameProjectData(before)) before.projectDataRevision else before.projectDataRevision + 1,
            messageDataRevision = before.messageDataRevision,
            removedSessionRevisions = before.removedSessionRevisions,
            removedMessageRevisions = before.removedMessageRevisions,
            removedPartRevisions = before.removedPartRevisions,
        )
    }

    val beforeMessageIds = before.messagesBySession.values.flatten().mapTo(mutableSetOf(), OpenCodeMessage::id)
    val nextMessageIds = messagesBySession.values.flatten().mapTo(mutableSetOf(), OpenCodeMessage::id)
    val removedMessages = before.removedMessageRevisions.toMutableMap().apply {
        nextMessageIds.forEach(::remove)
        (beforeMessageIds - nextMessageIds).forEach { messageId -> put(messageId, nextMessageRevision) }
    }
    val beforePartIds = before.partsByMessage.flatMap { (messageId, parts) ->
        parts.map { part -> messagePartKey(messageId, part.id) }
    }.toSet()
    val nextPartIds = partsByMessage.flatMap { (messageId, parts) ->
        parts.map { part -> messagePartKey(messageId, part.id) }
    }.toSet()
    val removedParts = before.removedPartRevisions.toMutableMap().apply {
        nextPartIds.forEach(::remove)
        (beforePartIds - nextPartIds).forEach { partKey -> put(partKey, nextMessageRevision) }
    }
    val removedSessions = before.removedSessionRevisions.toMutableMap().apply {
        messagesBySession
            .filterValues { messages -> messages.isNotEmpty() }
            .keys
            .forEach(::remove)
    }
    return copy(
        projectDataRevision = if (hasSameProjectData(before)) before.projectDataRevision else before.projectDataRevision + 1,
        messageDataRevision = nextMessageRevision,
        removedSessionRevisions = removedSessions,
        removedMessageRevisions = removedMessages,
        removedPartRevisions = removedParts,
    )
}

private fun OpenCodeProjectState.withForcedRemoval(
    before: OpenCodeProjectState,
    removal: MessageRemoval,
): OpenCodeProjectState {
    val revised = withDataRevisionsFrom(before)
    val removalRevision = if (revised.messageDataRevision == before.messageDataRevision) {
        before.messageDataRevision + 1
    } else {
        revised.messageDataRevision
    }
    return when (removal) {
        is MessageRemoval.Session -> revised.copy(
            messageDataRevision = removalRevision,
            removedSessionRevisions = revised.removedSessionRevisions + (removal.sessionId to removalRevision),
        )
        is MessageRemoval.Message -> revised.copy(
            messageDataRevision = removalRevision,
            removedMessageRevisions = revised.removedMessageRevisions + (removal.messageId to removalRevision),
        )
        is MessageRemoval.Part -> revised.copy(
            messageDataRevision = removalRevision,
            removedPartRevisions = revised.removedPartRevisions +
                (messagePartKey(removal.messageId, removal.partId) to removalRevision),
        )
    }
}

private fun OpenCodeProjectState.hasSameProjectData(other: OpenCodeProjectState): Boolean =
    project == other.project &&
        pathInfo == other.pathInfo &&
        sessions == other.sessions &&
        permissionsBySession == other.permissionsBySession &&
        questionsBySession == other.questionsBySession &&
        statuses == other.statuses &&
        providerCount == other.providerCount &&
        models == other.models &&
        agents == other.agents &&
        commands == other.commands &&
        promptCapabilities == other.promptCapabilities &&
        mcps == other.mcps &&
        lsps == other.lsps &&
        plugins == other.plugins &&
        commandCount == other.commandCount &&
        permissionCount == other.permissionCount &&
        questionCount == other.questionCount

private fun OpenCodeProjectState.hasSameMessageData(other: OpenCodeProjectState): Boolean =
    messagesBySession == other.messagesBySession && partsByMessage == other.partsByMessage

private fun OpenCodeProjectState.replaceMessages(
    sessionId: String,
    bundle: OpenCodeMessageBundle,
): OpenCodeProjectState {
    val currentMessages = messagesBySession[sessionId].orEmpty()
    val currentMessageIds = currentMessages.map(OpenCodeMessage::id).toSet()
    val incomingMessageIds = bundle.messages.map(OpenCodeMessage::id).toSet()
    val optimisticById = currentMessages.filter(OpenCodeMessage::isOptimistic).associateBy(OpenCodeMessage::id)
    val incomingParts = bundle.parts.groupBy(OpenCodeMessagePart::messageId)
    val nextMessages = buildList {
        bundle.messages.forEach { historical ->
            add(optimisticById[historical.id]?.withHistoricalFallback(historical) ?: historical)
        }
        addAll(optimisticById.values.filterNot { it.id in incomingMessageIds })
    }.sortedForTimeline()
    val optimisticParts = optimisticById.mapValues { (messageId, message) ->
        val liveParts = partsByMessage[messageId].orEmpty().ifEmpty { message.parts }
        mergeParts(incomingParts[messageId].orEmpty(), liveParts)
    }
    val nextParts = (partsByMessage - (currentMessageIds + incomingMessageIds)) + incomingParts + optimisticParts
    return copy(
        messagesBySession = messagesBySession + (sessionId to nextMessages),
        partsByMessage = nextParts,
    )
}

private fun OpenCodeProjectState.mergeMessages(
    sessionId: String,
    bundle: OpenCodeMessageBundle,
    expectedRevision: Long,
): OpenCodeProjectState {
    val historicalById = bundle.messages
        .filterNot { message -> removedMessageRevisions[message.id]?.let { it > expectedRevision } == true }
        .associateBy(OpenCodeMessage::id)
    val liveById = messagesBySession[sessionId].orEmpty().associateBy(OpenCodeMessage::id)
    val messageIds = linkedSetOf<String>().apply {
        addAll(historicalById.keys)
        addAll(liveById.keys)
    }
    val mergedMessages = messageIds.mapNotNull { messageId ->
        val historical = historicalById[messageId]
        val live = liveById[messageId]
        when {
            historical != null && live != null -> live.withHistoricalFallback(historical)
            live != null -> live
            else -> historical
        }
    }

    var mergedParts = partsByMessage
    bundle.parts
        .filterNot { part ->
            removedMessageRevisions[part.messageId]?.let { it > expectedRevision } == true ||
                removedPartRevisions[messagePartKey(part.messageId, part.id)]?.let { it > expectedRevision } == true
        }
        .groupBy(OpenCodeMessagePart::messageId)
        .forEach { (messageId, historicalParts) ->
            mergedParts = mergedParts + (messageId to mergeParts(historicalParts, partsByMessage[messageId].orEmpty()))
        }
    val messagesWithRenderedText = mergedMessages.map { message ->
        if (message.text.isNotEmpty()) message else message.copy(text = mergedParts[message.id].orEmpty().renderText())
    }.sortedForTimeline()
    return copy(
        messagesBySession = messagesBySession + (sessionId to messagesWithRenderedText),
        partsByMessage = mergedParts,
    )
}

private fun OpenCodeProjectState.reconcileFirstMessagePage(
    sessionId: String,
    bundle: OpenCodeMessageBundle,
): OpenCodeProjectState {
    val currentMessages = messagesBySession[sessionId].orEmpty()
    val incomingMessageIds = bundle.messages.mapTo(mutableSetOf(), OpenCodeMessage::id)
    val optimisticById = currentMessages.filter(OpenCodeMessage::isOptimistic).associateBy(OpenCodeMessage::id)
    val oldestIncoming = bundle.messages.minWithOrNull(OpenCodeMessageTimelineComparator)
    val preservedMessages = currentMessages.filter { current ->
        current.id !in incomingMessageIds &&
            (
                current.isOptimistic ||
                    oldestIncoming?.let { OpenCodeMessageTimelineComparator.compare(current, it) < 0 } == true
                )
    }
    val nextMessages = buildList {
        bundle.messages.forEach { historical ->
            add(optimisticById[historical.id]?.withHistoricalFallback(historical) ?: historical)
        }
        addAll(preservedMessages)
    }.sortedForTimeline()

    val incomingParts = bundle.parts.groupBy(OpenCodeMessagePart::messageId)
    val optimisticParts = optimisticById
        .filterKeys { it in incomingMessageIds }
        .mapValues { (messageId, message) ->
            val liveParts = partsByMessage[messageId].orEmpty().ifEmpty { message.parts }
            mergeParts(incomingParts[messageId].orEmpty(), liveParts)
        }
    val currentMessageIds = currentMessages.mapTo(mutableSetOf(), OpenCodeMessage::id)
    val preservedMessageIds = preservedMessages.mapTo(mutableSetOf(), OpenCodeMessage::id)
    val replacedMessageIds = (currentMessageIds - preservedMessageIds) + incomingMessageIds
    val nextParts = (partsByMessage - replacedMessageIds) + incomingParts + optimisticParts
    return copy(
        messagesBySession = messagesBySession + (sessionId to nextMessages),
        partsByMessage = nextParts,
    )
}

private fun OpenCodeMessage.withHistoricalFallback(historical: OpenCodeMessage): OpenCodeMessage = copy(
    role = role.ifBlank { historical.role },
    parts = if (parts.isEmpty()) historical.parts else mergeParts(historical.parts, parts),
    parentId = parentId ?: historical.parentId,
    agent = agent ?: historical.agent,
    modelLabel = modelLabel ?: historical.modelLabel,
    modelProviderId = modelProviderId ?: historical.modelProviderId,
    modelId = modelId ?: historical.modelId,
    modelVariant = modelVariant ?: historical.modelVariant,
    createdAt = createdAt ?: historical.createdAt,
    completedAt = completedAt ?: historical.completedAt,
    tokens = tokens ?: historical.tokens,
    error = error ?: historical.error,
)

private fun mergeParts(
    historicalParts: List<OpenCodeMessagePart>,
    liveParts: List<OpenCodeMessagePart>,
): List<OpenCodeMessagePart> {
    val historicalIds = historicalParts.mapTo(mutableSetOf(), OpenCodeMessagePart::id)
    val liveById = liveParts.associateBy(OpenCodeMessagePart::id)
    return historicalParts.map { historical ->
        liveById[historical.id]?.withHistoricalFallback(historical) ?: historical
    } + liveParts.filterNot { it.id in historicalIds }
}

private fun OpenCodeMessagePart.withHistoricalFallback(historical: OpenCodeMessagePart): OpenCodeMessagePart = copy(
    type = type.takeUnless { it == "unknown" || it.isBlank() } ?: historical.type,
    text = text ?: historical.text,
    filename = filename ?: historical.filename,
    mime = mime ?: historical.mime,
    url = url ?: historical.url,
    tool = tool ?: historical.tool,
    stateStatus = stateStatus ?: historical.stateStatus,
    toolInput = toolInput.ifEmpty { historical.toolInput },
    toolInputJson = toolInputJson ?: historical.toolInputJson,
    toolMetadata = toolMetadata.ifEmpty { historical.toolMetadata },
    toolMetadataJson = toolMetadataJson ?: historical.toolMetadataJson,
    toolOutput = toolOutput ?: historical.toolOutput,
    toolError = toolError ?: historical.toolError,
    opencodeComment = opencodeComment ?: historical.opencodeComment,
)

private fun List<OpenCodeMessagePart>.renderText(): String = filter { it.type == "text" && !it.synthetic }
    .mapNotNull(OpenCodeMessagePart::text)
    .joinToString("\n")

private sealed interface MessageRemoval {
    data class Session(val sessionId: String) : MessageRemoval {
        override fun toString(): String = "MessageRemoval.Session(sessionId=<redacted>)"
    }

    data class Message(val messageId: String) : MessageRemoval {
        override fun toString(): String = "MessageRemoval.Message(messageId=<redacted>)"
    }

    data class Part(val messageId: String, val partId: String) : MessageRemoval {
        override fun toString(): String =
            "MessageRemoval.Part(messageId=<redacted>, partId=<redacted>)"
    }
}

private fun JsonObject.messageRemoval(): MessageRemoval? {
    val wrappedPayload = this["payload"] as? JsonObject ?: this
    val payload = if (wrappedPayload.stringValue("type") == "sync") {
        wrappedPayload["syncEvent"] as? JsonObject ?: wrappedPayload
    } else {
        wrappedPayload
    }
    val type = (payload.stringValue("type") ?: stringValue("type"))?.lowercase(Locale.US) ?: return null
    val properties = payload["properties"] as? JsonObject
        ?: payload["data"] as? JsonObject
        ?: payload
    return when {
        type.contains("session") && type.contains("deleted") -> {
            (properties.stringValue("sessionID") ?: properties.stringValue("id"))?.let(MessageRemoval::Session)
        }
        type.contains("message") && type.contains("removed") && !type.contains("part") -> {
            (properties.stringValue("messageID") ?: properties.stringValue("id"))?.let(MessageRemoval::Message)
        }
        type.contains("part") && type.contains("removed") -> {
            val messageId = properties.stringValue("messageID") ?: return null
            val partId = properties.stringValue("partID") ?: properties.stringValue("id") ?: return null
            MessageRemoval.Part(messageId, partId)
        }
        else -> null
    }
}

private fun JsonObject.stringValue(name: String): String? = runCatching {
    this[name]?.jsonPrimitive?.contentOrNull
}.getOrNull()

private fun messagePartKey(messageId: String, partId: String): String = "$messageId\u0000$partId"

private fun List<OpenCodeSession>.sortedForDisplay(): List<OpenCodeSession> = sortedWith(
    compareByDescending<OpenCodeSession> { it.updatedAt ?: 0L }.thenByDescending { it.id },
)

private fun List<OpenCodeSession>.visibleForDisplay(): List<OpenCodeSession> = filter { it.archivedAt == null }

private fun Int.saturatedPlus(value: Int): Int = if (this > Int.MAX_VALUE - value) Int.MAX_VALUE else this + value

private fun List<OpenCodePermissionRequest>.groupPermissionsBySession(): Map<String, List<OpenCodePermissionRequest>> = groupBy { it.sessionId }
    .mapValues { (_, requests) -> requests.sortedBy { it.id } }

private fun List<OpenCodeQuestionRequest>.groupQuestionsBySession(): Map<String, List<OpenCodeQuestionRequest>> = groupBy { it.sessionId }
    .mapValues { (_, requests) -> requests.sortedBy { it.id } }

private fun Map<String, List<OpenCodePermissionRequest>>.removePermission(
    sessionId: String,
    requestId: String,
): Map<String, List<OpenCodePermissionRequest>> {
    val nextRequests = get(sessionId).orEmpty().filterNot { it.id == requestId }
    return if (nextRequests.isEmpty()) this - sessionId else this + (sessionId to nextRequests)
}

private fun Map<String, List<OpenCodeQuestionRequest>>.removeQuestion(
    sessionId: String,
    requestId: String,
): Map<String, List<OpenCodeQuestionRequest>> {
    val nextRequests = get(sessionId).orEmpty().filterNot { it.id == requestId }
    return if (nextRequests.isEmpty()) this - sessionId else this + (sessionId to nextRequests)
}

private fun Map<String, List<OpenCodePermissionRequest>>.totalPermissionCount(): Int = values.sumOf { it.size }

private fun Map<String, List<OpenCodeQuestionRequest>>.totalQuestionCount(): Int = values.sumOf { it.size }

private fun Map<String, List<OpenCodeMessage>>.upsert(message: OpenCodeMessage): Map<String, List<OpenCodeMessage>> {
    val messages = get(message.sessionId).orEmpty().filterNot { it.id == message.id } + message
    return this + (message.sessionId to messages.sortedForTimeline())
}
