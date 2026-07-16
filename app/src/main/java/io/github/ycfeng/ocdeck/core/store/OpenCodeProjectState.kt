package io.github.ycfeng.ocdeck.core.store

import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommand
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMcp
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodePathInfo
import io.github.ycfeng.ocdeck.domain.model.OpenCodeLsp
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodePlugin
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionStatus
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.ui.text.UiText

const val SessionListPageSize = 20
const val SessionListRawHeadroom = 50
const val InitialSessionListRawLimit = SessionListPageSize + SessionListRawHeadroom

enum class SessionListMoreState {
    Unknown,
    MayHaveMore,
    EndReached,
    Loading,
    Failed,
}

data class SessionListWindowState(
    val visibleRootLimit: Int = SessionListPageSize,
    val requestedRawLimit: Int = InitialSessionListRawLimit,
    val rawResultCount: Int = 0,
    val moreState: SessionListMoreState = SessionListMoreState.Unknown,
    val requestGeneration: Long = 0L,
    val loadedRootSessionIds: Set<String> = emptySet(),
) {
    override fun toString(): String =
        "SessionListWindowState(visibleRootLimit=$visibleRootLimit, requestedRawLimit=$requestedRawLimit, " +
            "rawResultCount=$rawResultCount, moreState=$moreState, requestGeneration=$requestGeneration, " +
            "loadedRootSessionCount=${loadedRootSessionIds.size})"
}

data class SessionListWindowLoadRequest(
    val requestedRawLimit: Int,
    val requestGeneration: Long,
    val expectedProjectRevision: Long,
)

enum class SessionListWindowRequestAction {
    ExpandedLocally,
    LoadRequired,
    Ignored,
}

class ProjectKey private constructor(
    val serverId: String,
    val normalizedDirectory: String,
    val normalizedWorkspace: String?,
    private val directoryComparisonKey: String,
    private val workspaceComparisonKey: String?,
) {
    override fun equals(other: Any?): Boolean = other is ProjectKey &&
        serverId == other.serverId &&
        directoryComparisonKey == other.directoryComparisonKey &&
        workspaceComparisonKey == other.workspaceComparisonKey

    override fun hashCode(): Int {
        var result = serverId.hashCode()
        result = 31 * result + directoryComparisonKey.hashCode()
        result = 31 * result + (workspaceComparisonKey?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ProjectKey(serverId=<redacted>, directory=<redacted>, " +
            "workspace=${redactedIfPresent(normalizedWorkspace)})"

    companion object {
        fun create(
            serverId: String,
            directory: String,
            workspace: String? = null,
            pathNormalizer: PathNormalizer,
        ): ProjectKey {
            val normalizedDirectory = pathNormalizer.normalize(directory)
            val normalizedWorkspace = workspace
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let(pathNormalizer::normalize)
            return ProjectKey(
                serverId = serverId,
                normalizedDirectory = normalizedDirectory,
                normalizedWorkspace = normalizedWorkspace,
                directoryComparisonKey = pathNormalizer.comparisonKey(normalizedDirectory),
                workspaceComparisonKey = normalizedWorkspace?.let(pathNormalizer::comparisonKey),
            )
        }
    }
}

data class OpenCodeProjectState(
    val serverId: String,
    val normalizedDirectory: String,
    val workspace: String? = null,
    val project: ProjectRef? = null,
    val pathInfo: OpenCodePathInfo? = null,
    val sessions: List<OpenCodeSession> = emptyList(),
    val sessionListWindow: SessionListWindowState = SessionListWindowState(),
    val messagesBySession: Map<String, List<OpenCodeMessage>> = emptyMap(),
    val partsByMessage: Map<String, List<OpenCodeMessagePart>> = emptyMap(),
    val permissionsBySession: Map<String, List<OpenCodePermissionRequest>> = emptyMap(),
    val questionsBySession: Map<String, List<OpenCodeQuestionRequest>> = emptyMap(),
    val statuses: Map<String, OpenCodeSessionStatus> = emptyMap(),
    val providerCount: Int = 0,
    val models: List<OpenCodeModel> = emptyList(),
    val agents: List<OpenCodeAgent> = emptyList(),
    val commands: List<OpenCodeCommand> = emptyList(),
    val promptCapabilities: PromptCapabilities = PromptCapabilities(),
    val mcps: List<OpenCodeMcp> = emptyList(),
    val lsps: List<OpenCodeLsp> = emptyList(),
    val plugins: List<OpenCodePlugin> = emptyList(),
    val commandCount: Int = 0,
    val permissionCount: Int = 0,
    val questionCount: Int = 0,
    val connectionStatus: SseConnectionStatus = SseConnectionStatus.Closed,
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val notifications: NotificationStore = NotificationStore(),
    val activeSessionId: String? = null,
    val projectDataRevision: Long = 0L,
    val messageDataRevision: Long = 0L,
    internal val removedSessionRevisions: Map<String, Long> = emptyMap(),
    internal val removedMessageRevisions: Map<String, Long> = emptyMap(),
    internal val removedPartRevisions: Map<String, Long> = emptyMap(),
) {
    override fun toString(): String =
        "OpenCodeProjectState(serverId=<redacted>, normalizedDirectory=<redacted>, " +
             "workspace=${redactedIfPresent(workspace)}, projectPresent=${project != null}, " +
            "pathInfoPresent=${pathInfo != null}, sessionCount=${sessions.size}, sessionListWindow=$sessionListWindow, " +
            "messageSessionCount=${messagesBySession.size}, partMessageCount=${partsByMessage.size}, " +
            "permissionSessionCount=${permissionsBySession.size}, questionSessionCount=${questionsBySession.size}, " +
            "statusCount=${statuses.size}, providerCount=$providerCount, modelCount=${models.size}, " +
            "agentCount=${agents.size}, commandListCount=${commands.size}, " +
            "promptCapabilitiesRevision=${promptCapabilities.revision}, mcpCount=${mcps.size}, " +
            "lspCount=${lsps.size}, pluginCount=${plugins.size}, commandCount=$commandCount, " +
            "permissionCount=$permissionCount, questionCount=$questionCount, " +
            "connectionPhase=${connectionStatus.summaryPhase()}, isLoading=$isLoading, " +
            "errorPresent=${error != null}, notificationCount=${notifications.items.size}, " +
            "activeSessionId=${redactedIfPresent(activeSessionId)}, projectDataRevision=$projectDataRevision, " +
            "messageDataRevision=$messageDataRevision, removedSessionCount=${removedSessionRevisions.size}, " +
            "removedMessageCount=${removedMessageRevisions.size}, removedPartCount=${removedPartRevisions.size})"
}

data class OpenCodeRuntimeState(
    val projects: Map<ProjectKey, OpenCodeProjectState> = emptyMap(),
    val globalConnectionStatuses: Map<String, SseConnectionStatus> = emptyMap(),
) {
    val globalConnectionStatus: SseConnectionStatus
        get() = globalConnectionStatuses.values.singleOrNull() ?: SseConnectionStatus.Closed

    override fun toString(): String =
        "OpenCodeRuntimeState(projectCount=${projects.size}, " +
            "globalConnectionStatusCount=${globalConnectionStatuses.size})"
}

private fun redactedIfPresent(value: Any?): String = if (value == null) "null" else "<redacted>"

private fun SseConnectionStatus.summaryPhase(): String = when (this) {
    SseConnectionStatus.Connecting -> "Connecting"
    SseConnectionStatus.Open -> "Open"
    is SseConnectionStatus.Retrying -> "Retrying"
    is SseConnectionStatus.Failed -> "Failed"
    SseConnectionStatus.Closed -> "Closed"
}
