package io.github.ycfeng.ocdeck.feature.session

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.navigation.SessionVisibilityLease
import io.github.ycfeng.ocdeck.core.navigation.SessionVisibilityRegistry
import io.github.ycfeng.ocdeck.core.network.GlobalConnectionLease
import io.github.ycfeng.ocdeck.core.network.OpenCodeEventClient
import io.github.ycfeng.ocdeck.core.network.ProjectConnectionLease
import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.MessagePageApplyResult
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.core.util.LatestRequestGate
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFilePathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFileUrlBuilder
import io.github.ycfeng.ocdeck.data.opencode.OpenCodeRepository
import io.github.ycfeng.ocdeck.data.project.RecentProjectRecorder
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerRepository
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommand
import io.github.ycfeng.ocdeck.domain.model.OpenCodeDiffFile
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMcp
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSessionRevert
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.domain.prompt.AttachmentBudget
import io.github.ycfeng.ocdeck.domain.prompt.AttachmentLimits
import io.github.ycfeng.ocdeck.domain.prompt.OpenCodePromptSender
import io.github.ycfeng.ocdeck.domain.prompt.OpenCodeIdGenerator
import io.github.ycfeng.ocdeck.domain.prompt.PromptAgentUnavailableException
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachment
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachmentCountLimitException
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachmentFileTooLargeException
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachmentMalformedException
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachmentMetadataInvalidException
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachmentTotalSizeLimitException
import io.github.ycfeng.ocdeck.domain.prompt.PromptCapabilitiesNotLoadedException
import io.github.ycfeng.ocdeck.domain.prompt.PromptCapabilitiesStaleException
import io.github.ycfeng.ocdeck.domain.prompt.PromptModelSelectionRequiredException
import io.github.ycfeng.ocdeck.domain.prompt.PromptModelUnavailableException
import io.github.ycfeng.ocdeck.domain.prompt.PromptOperationInProgressException
import io.github.ycfeng.ocdeck.domain.prompt.PromptProjectContextCountLimitException
import io.github.ycfeng.ocdeck.domain.prompt.PromptProjectContextInvalidException
import io.github.ycfeng.ocdeck.domain.prompt.PromptSendAction
import io.github.ycfeng.ocdeck.domain.prompt.PromptSendInput
import io.github.ycfeng.ocdeck.domain.prompt.PromptSendOutcomeUncertainException
import io.github.ycfeng.ocdeck.domain.prompt.PromptSendStateMachine
import io.github.ycfeng.ocdeck.domain.prompt.PromptVariantUnavailableException
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContextLimits
import io.github.ycfeng.ocdeck.domain.prompt.SessionOperationCoordinator
import io.github.ycfeng.ocdeck.feature.composer.findModel
import io.github.ycfeng.ocdeck.feature.composer.resolveComposerAgent
import io.github.ycfeng.ocdeck.feature.composer.selectPromptModel
import io.github.ycfeng.ocdeck.feature.composer.selectPromptVariant
import io.github.ycfeng.ocdeck.feature.composer.toComposerAgentOrNull
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SessionDetailViewModel(
    private val serverId: String,
    directory: String,
    initialSessionId: String,
    private val repository: OpenCodeRepository,
    private val serverRepository: ServerRepository,
    private val store: InMemoryOpenCodeStore,
    private val eventClient: OpenCodeEventClient,
    private val recentProjectRecorder: RecentProjectRecorder,
    private val promptSender: OpenCodePromptSender,
    private val sessionOperationCoordinator: SessionOperationCoordinator,
    sessionVisibilityRegistry: SessionVisibilityRegistry,
    pathNormalizer: PathNormalizer,
    private val projectFilePathNormalizer: ProjectFilePathNormalizer,
    initialAgentId: String? = null,
    initialModelSelection: PromptModelSelection? = null,
    private val stateMachine: PromptSendStateMachine = PromptSendStateMachine(),
) : ViewModel() {
    private val normalizedDirectory = pathNormalizer.normalize(directory)
    private val projectFileUrlBuilder = ProjectFileUrlBuilder(pathNormalizer, projectFilePathNormalizer)
    private val localState = MutableStateFlow(
        SessionLocalState(
            currentSessionId = initialSessionId,
            selectedAgentOverride = initialAgentId
                .takeIf { initialSessionId == OpenCodePromptSender.NEW_SESSION_ID }
                .toComposerAgentOrNull(),
            selectedModel = initialModelSelection.takeIf {
                initialSessionId == OpenCodePromptSender.NEW_SESSION_ID
            },
            selectedVariant = initialModelSelection
                ?.variant
                ?.takeIf { initialSessionId == OpenCodePromptSender.NEW_SESSION_ID },
        ),
    )
    private val refreshGate = LatestRequestGate()
    private lateinit var projectConnectionLease: ProjectConnectionLease
    private lateinit var globalConnectionLease: GlobalConnectionLease
    private var olderMessagesJob: Job? = null
    private var loadAllOlderMessagesRequested = false
    private val loadAllOlderMessagesCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val sessionVisibilityLease: SessionVisibilityLease = sessionVisibilityRegistry.createLease(
        serverId = serverId,
        directory = normalizedDirectory,
        sessionId = initialSessionId.takeUnless { it == OpenCodePromptSender.NEW_SESSION_ID },
    )

    val uiState: StateFlow<SessionDetailUiState> = combine(
        store.observeProject(serverId, normalizedDirectory),
        localState,
        serverRepository.observeServers(),
    ) { project, local, servers ->
        project.toUiState(
            local = local,
            serverConfig = servers.firstOrNull { it.id == serverId },
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OpenCodeProjectState(serverId, normalizedDirectory).toUiState(
                local = localState.value,
                serverConfig = null,
            ),
        )

    init {
        recentProjectRecorder.recordAdd(serverId, normalizedDirectory)
        projectConnectionLease = eventClient.connectProject(serverId, normalizedDirectory)
        globalConnectionLease = eventClient.connectGlobal(serverId)
        refresh()
    }

    fun onComposerChanged(value: String) {
        localState.update { it.copy(composer = value, error = null) }
    }

    fun onDestinationVisibilityChanged(visible: Boolean) {
        sessionVisibilityLease.setDestinationVisible(visible)
    }

    internal fun beginReadingAttachments(): AttachmentReadStartResult {
        while (true) {
            val local = localState.value
            if (local.isReadingAttachments) return AttachmentReadStartResult(started = false)
            val budget = AttachmentBudget.from(local.attachments)
            val failure = when {
                budget.remainingCount == 0 -> LocalAttachmentFailureReason.CountLimit
                budget.hasUnknownSize || budget.totalSizeBytes > AttachmentLimits.MAX_TOTAL_BYTES ->
                    LocalAttachmentFailureReason.TotalSizeLimit
                else -> null
            }
            if (failure != null) {
                return AttachmentReadStartResult(started = false, failureReason = failure)
            }
            if (localState.compareAndSet(local, local.copy(isReadingAttachments = true, error = null))) {
                return AttachmentReadStartResult(started = true)
            }
        }
    }

    internal fun attachmentBudgetForRead(): AttachmentBudget? {
        val local = localState.value
        return if (local.isReadingAttachments) AttachmentBudget.from(local.attachments) else null
    }

    internal fun finishReadingAttachments() {
        localState.update { it.copy(isReadingAttachments = false) }
    }

    internal fun addAttachments(attachments: List<PromptAttachment>): LocalAttachmentFailureReason? {
        if (attachments.isEmpty()) return null
        while (true) {
            val local = localState.value
            val admission = AttachmentBudget.from(local.attachments).admit(attachments)
            val next = if (admission.accepted.isEmpty()) {
                local
            } else {
                local.copy(
                    attachments = local.attachments + admission.accepted,
                    error = null,
                )
            }
            if (localState.compareAndSet(local, next)) {
                return admission.failure?.toLocalAttachmentFailureReason()
            }
        }
    }

    fun removeAttachment(attachmentId: String) {
        localState.update { local ->
            local.copy(attachments = local.attachments.filterNot { it.id == attachmentId })
        }
    }

    fun setProjectFileContexts(relativePaths: List<String>) {
        val windowsSeparators = projectFilePathNormalizer.usesWindowsSeparators(normalizedDirectory)
        val normalizedPaths = try {
            val seen = mutableSetOf<String>()
            relativePaths.mapNotNull { path ->
                val normalized = projectFilePathNormalizer.normalize(
                    path,
                    backslashIsSeparator = windowsSeparators,
                )
                val comparisonPath = if (windowsSeparators) normalized.lowercase() else normalized
                normalized.takeIf { seen.add(comparisonPath) }
            }
        } catch (_: IllegalArgumentException) {
            localState.update { it.copy(error = UiText.Resource(R.string.project_file_context_invalid)) }
            return
        }
        if (normalizedPaths.size > ProjectFileContextLimits.MAX_CONTEXT_COUNT) {
            localState.update {
                it.copy(
                    error = UiText.Resource(
                        R.string.project_file_context_count_limit,
                        listOf(ProjectFileContextLimits.MAX_CONTEXT_COUNT),
                    ),
                )
            }
            return
        }
        localState.update { local ->
            val existing = local.projectFileContexts.associateBy { context ->
                if (windowsSeparators) context.relativePath.lowercase() else context.relativePath
            }
            local.copy(
                projectFileContexts = normalizedPaths.map { path ->
                    val key = if (windowsSeparators) path.lowercase() else path
                    existing[key] ?: ProjectFileContext(OpenCodeIdGenerator.partId(), path)
                },
                error = null,
            )
        }
    }

    fun removeProjectFileContext(contextId: String) {
        localState.update { local ->
            local.copy(projectFileContexts = local.projectFileContexts.filterNot { it.id == contextId })
        }
    }

    fun showAttachmentError(error: UiText) {
        localState.update { it.copy(error = error) }
    }

    fun selectAgent(agentId: String) {
        val agent = agentId.toComposerAgentOrNull() ?: return
        if (uiState.value.agents.none { it.id == agent }) return
        localState.update { it.copy(selectedAgentOverride = agent) }
    }

    fun selectModel(model: OpenCodeModel) {
        val selection = localState.value.selectedModel.selectPromptModel(model)
        localState.update {
            it.copy(
                selectedModel = selection,
                selectedVariant = selection.variant,
            )
        }
        saveModelPreference(selection)
    }

    fun selectVariant(variant: String?) {
        val models = uiState.value.models
        var nextSelection: PromptModelSelection? = null
        localState.update { local ->
            nextSelection = local.selectedModel?.selectPromptVariant(variant, models)
            local.copy(
                selectedVariant = nextSelection?.variant,
                selectedModel = nextSelection,
            )
        }
        nextSelection?.let(::saveModelPreference)
    }

    fun applySlashCommand(command: OpenCodeCommand) {
        localState.update { it.copy(composer = "/${command.name} ", error = null) }
    }

    fun applyMention(agent: OpenCodeAgent) {
        localState.update { it.copy(composer = replaceActiveMention(it.composer, agent.id), error = null) }
    }

    fun refresh() {
        val requestId = refreshGate.begin()
        viewModelScope.launch { refreshAll(requestId) }
        refreshServerHealth(requestId)
    }

    fun loadOlderMessages() {
        startOlderMessagesLoad(loadAll = false)
    }

    fun loadAllOlderMessages(onComplete: (Boolean) -> Unit = {}) {
        startOlderMessagesLoad(loadAll = true, onComplete = onComplete)
    }

    private fun startOlderMessagesLoad(
        loadAll: Boolean,
        onComplete: ((Boolean) -> Unit)? = null,
    ) {
        val sessionId = localState.value.currentSessionId
        val history = store.currentProject(serverId, normalizedDirectory)
            .messageHistoryBySession[sessionId]
        if (sessionId == OpenCodePromptSender.NEW_SESSION_ID) {
            onComplete?.invoke(true)
            return
        }
        if (history == null) {
            onComplete?.invoke(false)
            return
        }
        if (!history.hasOlderMessages) {
            onComplete?.invoke(true)
            return
        }
        if (loadAll) {
            loadAllOlderMessagesRequested = true
            onComplete?.let(loadAllOlderMessagesCallbacks::add)
        }
        if (olderMessagesJob?.isActive == true) return

        localState.update { it.copy(isLoadingOlderMessages = true, error = null) }
        olderMessagesJob = viewModelScope.launch {
            var succeeded = true
            try {
                do {
                    val currentHistory = store.currentProject(serverId, normalizedDirectory)
                        .messageHistoryBySession[sessionId]
                    if (currentHistory == null) {
                        succeeded = false
                        break
                    }
                    val before = currentHistory.nextCursor ?: break
                    val expectedRevision = store.captureMessageDataRevision(serverId, normalizedDirectory)
                    val result = repository.loadMessagePage(
                        serverId = serverId,
                        directory = normalizedDirectory,
                        sessionId = sessionId,
                        before = before,
                    )
                    if (localState.value.currentSessionId != sessionId) {
                        succeeded = false
                        break
                    }
                    result.onSuccess { page ->
                        when (
                            store.putMessagePage(
                                serverId = serverId,
                                directory = normalizedDirectory,
                                sessionId = sessionId,
                                page = page,
                                before = before,
                                firstPageRequestGeneration = null,
                                expectedRevision = expectedRevision,
                            )
                        ) {
                            MessagePageApplyResult.Applied,
                            MessagePageApplyResult.Stale,
                            -> Unit
                            MessagePageApplyResult.CursorCycle -> {
                                localState.update {
                                    it.copy(error = UiText.Resource(R.string.session_load_messages_failed))
                                }
                                succeeded = false
                            }
                        }
                    }.onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                        localState.update {
                            it.copy(error = throwable.toErrorUiText(R.string.session_load_messages_failed))
                        }
                        succeeded = false
                    }
                } while (succeeded && loadAllOlderMessagesRequested)
            } finally {
                val finalHistory = store.currentProject(serverId, normalizedDirectory)
                    .messageHistoryBySession[sessionId]
                val completedAll = succeeded && finalHistory != null && !finalHistory.hasOlderMessages
                val callbacks = loadAllOlderMessagesCallbacks.toList()
                loadAllOlderMessagesCallbacks.clear()
                loadAllOlderMessagesRequested = false
                olderMessagesJob = null
                localState.update { it.copy(isLoadingOlderMessages = false) }
                callbacks.forEach { callback -> callback(completedAll) }
            }
        }
    }

    fun selectTab(tab: SessionDetailTab) {
        if (tab == SessionDetailTab.Changes && localState.value.currentSessionId == OpenCodePromptSender.NEW_SESSION_ID) return
        localState.update { it.copy(activeTab = tab) }
        if (tab == SessionDetailTab.Changes) refreshReview()
    }

    fun selectReviewMode(mode: ReviewMode) {
        val nextMode = mode.takeIf { it in uiState.value.availableReviewModes } ?: ReviewMode.Turn
        localState.update {
            it.copy(
                reviewMode = nextMode,
                reviewDiffs = emptyList(),
                reviewError = null,
                loadedReviewKey = null,
            )
        }
        viewModelScope.launch { loadReview(force = true, modeOverride = nextMode) }
    }

    fun refreshReview() {
        viewModelScope.launch { loadReview(force = true) }
    }

    fun toggleMcp(mcp: OpenCodeMcp) {
        if (mcp.status.equals("needs_auth", ignoreCase = true)) {
            localState.update { it.copy(error = UiText.Resource(R.string.project_error_mcp_needs_auth, listOf(mcp.name))) }
            return
        }
        viewModelScope.launch {
            localState.update { it.copy(mcpActionName = mcp.name, error = null) }
            val result = if (mcp.status.equals("connected", ignoreCase = true)) {
                repository.disconnectMcp(serverId, normalizedDirectory, mcp.name)
            } else {
                repository.connectMcp(serverId, normalizedDirectory, mcp.name)
            }
            result.onSuccess { refreshAll(refreshGate.begin()) }
                .onFailure { throwable -> localState.update { it.copy(error = throwable.toErrorUiText(R.string.project_error_mcp_action_failed)) } }
            localState.update { it.copy(mcpActionName = null) }
        }
    }

    fun submit(onSessionMaterialized: (String) -> Unit) {
        if (localState.value.isReadingAttachments || localState.value.isSending) return
        val state = uiState.value
        if (
            state.isChildSession ||
            state.pendingQuestion != null ||
            state.pendingPermission != null ||
            state.isReadingAttachments ||
            state.isRevertActionInProgress ||
            state.isRevertBranchCommitting
        ) return
        val action = stateMachine.evaluate(
            PromptSendInput(
                text = state.composer,
                hasAttachments = state.attachments.isNotEmpty(),
                hasContext = state.projectFileContexts.isNotEmpty(),
                isWorking = state.isWorking,
            ),
        )
        when (action) {
            PromptSendAction.Disabled -> return
            PromptSendAction.Stop -> stop()
            PromptSendAction.SendPrompt -> {
                val agent = state.selectedAgent
                if (agent == null) {
                    localState.update { it.copy(error = UiText.Resource(R.string.prompt_error_agent_unavailable)) }
                    return
                }
                send(state, agent, onSessionMaterialized)
            }
        }
    }

    fun resetToMessage(message: OpenCodeMessage) {
        val state = uiState.value
        if (
            state.currentSessionId == OpenCodePromptSender.NEW_SESSION_ID ||
            state.isChildSession ||
            state.pendingQuestion != null ||
            state.pendingPermission != null ||
            state.isSending ||
            state.isRevertActionInProgress ||
            state.isRevertBranchCommitting ||
            !message.role.equals("user", ignoreCase = true) ||
            message.isOptimistic
        ) return

        val previousDraft = SessionRevertDraft(state.composer, state.attachments, state.projectFileContexts)
        val targetDraft = message.toSessionRevertDraft(normalizedDirectory, projectFileUrlBuilder)
        targetDraft.failure?.let { failure ->
            localState.update { it.copy(error = failure.toUiText()) }
            return
        }
        if (!targetDraft.hasRecoverableContent) {
            localState.update { it.copy(error = UiText.Resource(R.string.session_reset_failed)) }
            return
        }
        localState.update {
            it.copy(
                composer = targetDraft.text,
                attachments = targetDraft.attachments,
                projectFileContexts = targetDraft.projectFileContexts,
                isRevertActionInProgress = true,
                revertBranchStartMessageId = null,
                error = null,
            )
        }
        viewModelScope.launch {
            sessionOperationCoordinator.revert(
                serverId = serverId,
                directory = normalizedDirectory,
                sessionId = state.currentSessionId,
                messageId = message.id,
                abortFirst = state.isWorking,
            ).onSuccess { session ->
                store.upsertSession(serverId, normalizedDirectory, session)
                localState.update { it.copy(isRevertActionInProgress = false) }
            }.onFailure { throwable ->
                localState.update {
                    it.copy(
                        composer = previousDraft.text,
                        attachments = previousDraft.attachments,
                        projectFileContexts = previousDraft.projectFileContexts,
                        isRevertActionInProgress = false,
                        error = throwable.toPromptUiText(R.string.session_reset_failed),
                    )
                }
            }
        }
    }

    fun restoreRevertedThrough(messageId: String) {
        val state = uiState.value
        val messageIndex = state.revertedUserMessages.indexOfFirst { it.id == messageId }
        if (messageIndex < 0) return
        restoreRevertedMessages(state.revertedUserMessages.getOrNull(messageIndex + 1))
    }

    fun restoreAllRevertedMessages() {
        restoreRevertedMessages(nextBoundary = null)
    }

    fun renameSession(title: String) {
        val state = uiState.value
        val sessionId = state.currentSessionId
        if (sessionId == OpenCodePromptSender.NEW_SESSION_ID || state.isChildSession) return
        viewModelScope.launch {
            localState.update { it.copy(isSessionActionInProgress = true, error = null) }
            repository.renameSession(
                serverId = serverId,
                directory = normalizedDirectory,
                sessionId = sessionId,
                title = title,
            ).onSuccess { session ->
                store.upsertSession(serverId, normalizedDirectory, session)
                localState.update { it.copy(isSessionActionInProgress = false) }
            }.onFailure { throwable ->
                localState.update {
                    it.copy(
                        isSessionActionInProgress = false,
                        error = throwable.toErrorUiText(R.string.session_rename_failed),
                    )
                }
            }
        }
    }

    fun archiveSession(onArchived: () -> Unit) {
        archiveSession(uiState.value.currentSessionId, onArchived)
    }

    fun archiveSession(sessionId: String, onArchived: () -> Unit) {
        if (sessionId == OpenCodePromptSender.NEW_SESSION_ID || sessionId.isBlank() || isChildSessionId(sessionId)) return
        viewModelScope.launch {
            localState.update { it.copy(isSessionActionInProgress = true, error = null) }
            repository.archiveSession(
                serverId = serverId,
                directory = normalizedDirectory,
                sessionId = sessionId,
            ).onSuccess {
                store.removeSession(serverId, normalizedDirectory, sessionId)
                if (localState.value.currentSessionId == sessionId) {
                    sessionVisibilityLease.updateSession(null)
                }
                localState.update { local ->
                    if (local.currentSessionId == sessionId) {
                        local.copy(
                            currentSessionId = OpenCodePromptSender.NEW_SESSION_ID,
                            activeTab = SessionDetailTab.Session,
                            isSessionActionInProgress = false,
                            reviewDiffs = emptyList(),
                            reviewError = null,
                            loadedReviewKey = null,
                        )
                    } else {
                        local.copy(isSessionActionInProgress = false)
                    }
                }
                onArchived()
            }.onFailure { throwable ->
                localState.update {
                    it.copy(
                        isSessionActionInProgress = false,
                        error = throwable.toErrorUiText(R.string.session_archive_failed),
                    )
                }
            }
        }
    }

    fun deleteSession(onDeleted: () -> Unit) {
        val state = uiState.value
        val sessionId = state.currentSessionId
        if (sessionId == OpenCodePromptSender.NEW_SESSION_ID || state.isChildSession) return
        viewModelScope.launch {
            localState.update { it.copy(isSessionActionInProgress = true, error = null) }
            repository.deleteSession(
                serverId = serverId,
                directory = normalizedDirectory,
                sessionId = sessionId,
            ).onSuccess {
                store.removeSession(serverId, normalizedDirectory, sessionId)
                sessionVisibilityLease.updateSession(null)
                localState.update {
                    it.copy(
                        currentSessionId = OpenCodePromptSender.NEW_SESSION_ID,
                        activeTab = SessionDetailTab.Session,
                        isSessionActionInProgress = false,
                        reviewDiffs = emptyList(),
                        reviewError = null,
                        loadedReviewKey = null,
                    )
                }
                onDeleted()
            }.onFailure { throwable ->
                localState.update {
                    it.copy(
                        isSessionActionInProgress = false,
                        error = throwable.toErrorUiText(R.string.session_delete_failed),
                    )
                }
            }
        }
    }

    fun updateProjectName(name: String, onSuccess: () -> Unit) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            localState.update { it.copy(projectNameError = UiText.Resource(R.string.project_name_empty_error)) }
            return
        }
        val projectId = uiState.value.project.project?.projectId
        if (projectId.isNullOrBlank()) {
            localState.update { it.copy(projectNameError = UiText.Resource(R.string.project_name_missing_id_error)) }
            return
        }
        if (uiState.value.isUpdatingProjectName) return

        viewModelScope.launch {
            localState.update { it.copy(isUpdatingProjectName = true, projectNameError = null) }
            repository.updateProjectName(
                serverId = serverId,
                directory = normalizedDirectory,
                projectId = projectId,
                name = trimmedName,
            ).onSuccess { project ->
                store.updateProject(serverId, normalizedDirectory, project)
                recentProjectRecorder.recordUpsert(serverId, project)
                localState.update { it.copy(isUpdatingProjectName = false, projectNameError = null) }
                onSuccess()
            }.onFailure { throwable ->
                localState.update {
                    it.copy(
                        isUpdatingProjectName = false,
                        projectNameError = throwable.toErrorUiText(R.string.project_name_save_failed),
                    )
                }
            }
        }
    }

    fun clearProjectNameError() {
        localState.update { it.copy(projectNameError = null) }
    }

    fun replyQuestion(request: OpenCodeQuestionRequest, answers: List<List<String>>) {
        if (localState.value.isQuestionActionInProgress) return
        viewModelScope.launch {
            localState.update { it.copy(isQuestionActionInProgress = true, error = null) }
            repository.replyQuestion(
                serverId = serverId,
                directory = normalizedDirectory,
                requestId = request.id,
                answers = answers,
            ).onSuccess {
                store.removeQuestionRequest(serverId, normalizedDirectory, request.sessionId, request.id)
                localState.update { it.copy(isQuestionActionInProgress = false) }
            }.onFailure { throwable ->
                localState.update {
                    it.copy(
                        isQuestionActionInProgress = false,
                        error = throwable.toErrorUiText(R.string.question_submit_failed),
                    )
                }
            }
        }
    }

    fun rejectQuestion(request: OpenCodeQuestionRequest) {
        if (localState.value.isQuestionActionInProgress) return
        viewModelScope.launch {
            localState.update { it.copy(isQuestionActionInProgress = true, error = null) }
            repository.rejectQuestion(
                serverId = serverId,
                directory = normalizedDirectory,
                requestId = request.id,
            ).onSuccess {
                store.removeQuestionRequest(serverId, normalizedDirectory, request.sessionId, request.id)
                localState.update { it.copy(isQuestionActionInProgress = false) }
            }.onFailure { throwable ->
                localState.update {
                    it.copy(
                        isQuestionActionInProgress = false,
                        error = throwable.toErrorUiText(R.string.question_ignore_failed),
                    )
                }
            }
        }
    }

    fun respondPermission(request: OpenCodePermissionRequest, response: String) {
        if (localState.value.isPermissionActionInProgress) return
        viewModelScope.launch {
            localState.update { it.copy(isPermissionActionInProgress = true, error = null) }
            repository.respondPermission(
                serverId = serverId,
                directory = normalizedDirectory,
                sessionId = request.sessionId,
                permissionId = request.id,
                response = response,
            ).onSuccess {
                store.removePermissionRequest(serverId, normalizedDirectory, request.sessionId, request.id)
                localState.update { it.copy(isPermissionActionInProgress = false) }
            }.onFailure { throwable ->
                localState.update {
                    it.copy(
                        isPermissionActionInProgress = false,
                        error = throwable.toErrorUiText(R.string.permission_action_failed),
                    )
                }
            }
        }
    }

    private fun send(state: SessionDetailUiState, agent: String, onSessionMaterialized: (String) -> Unit) {
        if (!beginPromptOperation()) return
        val sentDraft = SessionRevertDraft(state.composer, state.attachments, state.projectFileContexts)
        viewModelScope.launch {
            var optimisticMessageId: String? = null
            var materializedSessionId: String? = null
            try {
                val result = promptSender.sendPrompt(
                    serverId = serverId,
                    directory = normalizedDirectory,
                    sessionId = state.currentSessionId.takeUnless { it == OpenCodePromptSender.NEW_SESSION_ID },
                    text = sentDraft.text,
                    attachments = sentDraft.attachments,
                    projectFileContexts = sentDraft.projectFileContexts,
                    agent = agent,
                    model = state.selectedModel,
                    variant = state.selectedVariant,
                    capabilities = state.project.promptCapabilities,
                    onOptimisticMessageCreated = { messageId ->
                        optimisticMessageId = messageId
                        if (state.revert != null) {
                            localState.update { it.copy(revertBranchStartMessageId = messageId) }
                        }
                    },
                    onSessionMaterialized = { newSessionId ->
                        updateVisibleSession(newSessionId)
                        localState.update { it.copy(currentSessionId = newSessionId) }
                        materializedSessionId = newSessionId
                    },
                )
                result.onSuccess { sendResult ->
                    updateVisibleSession(sendResult.sessionId)
                    localState.update { local ->
                        val draftUnchanged = local.composer == sentDraft.text &&
                            local.attachments == sentDraft.attachments &&
                            local.projectFileContexts == sentDraft.projectFileContexts
                        local.copy(
                            currentSessionId = sendResult.sessionId,
                            composer = if (draftUnchanged) "" else local.composer,
                            attachments = if (draftUnchanged) emptyList() else local.attachments,
                            projectFileContexts = if (draftUnchanged) emptyList() else local.projectFileContexts,
                            isSending = false,
                        )
                    }
                    materializedSessionId?.let(onSessionMaterialized)
                }.onFailure { throwable ->
                    localState.update {
                        it.copy(
                            isSending = false,
                            revertBranchStartMessageId = if (it.revertBranchStartMessageId == optimisticMessageId) {
                                null
                            } else {
                                it.revertBranchStartMessageId
                            },
                            error = throwable.toPromptUiText(R.string.session_send_failed),
                        )
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                localState.update { it.copy(isSending = false) }
                throw cancelled
            }
        }
    }

    private fun beginPromptOperation(): Boolean {
        while (true) {
            val local = localState.value
            if (local.isSending) return false
            if (localState.compareAndSet(local, local.copy(isSending = true, error = null))) return true
        }
    }

    private fun restoreRevertedMessages(nextBoundary: OpenCodeMessage?) {
        val state = uiState.value
        if (
            state.currentSessionId == OpenCodePromptSender.NEW_SESSION_ID ||
            state.revert == null ||
            state.revertedUserMessages.isEmpty() ||
            state.isWorking ||
            state.isSending ||
            state.isRevertActionInProgress ||
            state.isRevertBranchCommitting
        ) return

        val previousDraft = SessionRevertDraft(state.composer, state.attachments, state.projectFileContexts)
        val nextDraft = nextBoundary?.toSessionRevertDraft(normalizedDirectory, projectFileUrlBuilder)
            ?: SessionRevertDraft("", emptyList())
        nextDraft.failure?.let { failure ->
            localState.update { it.copy(error = failure.toUiText()) }
            return
        }
        if (nextBoundary != null && !nextDraft.hasRecoverableContent) {
            localState.update { it.copy(error = UiText.Resource(R.string.session_restore_failed)) }
            return
        }
        localState.update { it.copy(isRevertActionInProgress = true, error = null) }
        viewModelScope.launch {
            val result = if (nextBoundary == null) {
                sessionOperationCoordinator.unrevert(
                    serverId = serverId,
                    directory = normalizedDirectory,
                    sessionId = state.currentSessionId,
                )
            } else {
                sessionOperationCoordinator.revert(
                    serverId = serverId,
                    directory = normalizedDirectory,
                    sessionId = state.currentSessionId,
                    messageId = nextBoundary.id,
                )
            }
            result.onSuccess { session ->
                store.upsertSession(serverId, normalizedDirectory, session)
                localState.update {
                    it.copy(
                        composer = nextDraft.text,
                        attachments = nextDraft.attachments,
                        projectFileContexts = nextDraft.projectFileContexts,
                        isRevertActionInProgress = false,
                        revertBranchStartMessageId = null,
                    )
                }
            }.onFailure { throwable ->
                localState.update {
                    it.copy(
                        composer = previousDraft.text,
                        attachments = previousDraft.attachments,
                        projectFileContexts = previousDraft.projectFileContexts,
                        isRevertActionInProgress = false,
                        error = throwable.toPromptUiText(R.string.session_restore_failed),
                    )
                }
            }
        }
    }

    private fun updateVisibleSession(sessionId: String) {
        sessionVisibilityLease.updateSession(
            sessionId.takeUnless { it == OpenCodePromptSender.NEW_SESSION_ID },
        )
    }

    private fun stop() {
        val sessionId = uiState.value.currentSessionId
        if (sessionId == OpenCodePromptSender.NEW_SESSION_ID) return
        if (!beginPromptOperation()) return
        viewModelScope.launch {
            promptSender.stop(serverId, normalizedDirectory, sessionId)
                .onSuccess { localState.update { it.copy(isSending = false) } }
                .onFailure { throwable -> localState.update { it.copy(isSending = false, error = throwable.toPromptUiText(R.string.session_stop_failed)) } }
        }
    }

    private suspend fun refreshAll(requestId: Long) {
        if (!refreshGate.isCurrent(requestId)) return
        store.setProjectLoading(serverId, normalizedDirectory, isLoading = true)
        var parentSessionIdToLoad: String? = null
        var sessionsForModelResolution: List<OpenCodeSession> = emptyList()
        var capabilitiesForModelResolution: PromptCapabilities? = null
        var messagesForModelResolution: List<OpenCodeMessage> = emptyList()
        val expectedProjectRevision = store.captureProjectDataRevision(serverId, normalizedDirectory)
        val sessionWindow = store.captureSessionListWindow(serverId, normalizedDirectory)
        val projectResult = repository.loadProject(
            serverId = serverId,
            directory = normalizedDirectory,
            sessionWindow = sessionWindow,
        )
        if (!refreshGate.isCurrent(requestId)) return
        projectResult.onSuccess { snapshot ->
            val applied = store.applyProjectSnapshotIfRevision(snapshot, expectedProjectRevision)
            val currentProject = store.currentProject(serverId, normalizedDirectory)
            sessionsForModelResolution = currentProject.sessions
            capabilitiesForModelResolution = currentProject.promptCapabilities
            if (!applied) store.setProjectLoading(serverId, normalizedDirectory, isLoading = false)
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            store.setProjectLoading(
                serverId,
                normalizedDirectory,
                isLoading = false,
                error = throwable.toErrorUiText(R.string.project_snapshot_load_failed),
            )
        }

        val sessionId = localState.value.currentSessionId
        if (sessionId != OpenCodePromptSender.NEW_SESSION_ID) {
            val knownProject = store.currentProject(serverId, normalizedDirectory)
            val knownSession = knownProject.sessions.firstOrNull { it.id == sessionId }
            val needsMetadata = knownSession == null || knownSession.parentId
                ?.takeIf { it.isNotBlank() }
                ?.let { parentId -> knownProject.sessions.none { it.id == parentId } } == true
            if (needsMetadata) {
                val metadataResult = repository.loadSessionMetadataChain(
                    serverId = serverId,
                    directory = normalizedDirectory,
                    sessionId = sessionId,
                )
                if (!refreshGate.isCurrent(requestId)) return
                metadataResult.onSuccess { sessions ->
                    sessions.forEach { session -> store.upsertSession(serverId, normalizedDirectory, session) }
                }.onFailure { throwable ->
                    if (throwable is CancellationException) throw throwable
                    localState.update { it.copy(error = throwable.toErrorUiText(R.string.session_load_metadata_failed)) }
                }
            }
            val projectWithMetadata = store.currentProject(serverId, normalizedDirectory)
            sessionsForModelResolution = projectWithMetadata.sessions
            capabilitiesForModelResolution = capabilitiesForModelResolution ?: projectWithMetadata.promptCapabilities
            parentSessionIdToLoad = projectWithMetadata.sessions.firstOrNull { it.id == sessionId }
                ?.parentId
                ?.takeIf { it.isNotBlank() }
            val messageRequest = store.beginMessageFirstPageRequest(serverId, normalizedDirectory, sessionId)
            val messageResult = repository.loadMessagePage(serverId, normalizedDirectory, sessionId)
            if (!refreshGate.isCurrent(requestId)) return
            messageResult.onSuccess {
                store.putMessagePage(
                    serverId = serverId,
                    directory = normalizedDirectory,
                    sessionId = sessionId,
                    page = it,
                    before = null,
                    firstPageRequestGeneration = messageRequest.generation,
                    expectedRevision = messageRequest.expectedRevision,
                )
                messagesForModelResolution = store.currentProject(serverId, normalizedDirectory)
                    .messagesBySession[sessionId]
                    .orEmpty()
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                localState.update { it.copy(error = throwable.toErrorUiText(R.string.session_load_messages_failed)) }
            }
            parentSessionIdToLoad
                ?.takeIf { it != sessionId }
                ?.let { parentSessionId ->
                    val parentMessageRequest = store.beginMessageFirstPageRequest(
                        serverId,
                        normalizedDirectory,
                        parentSessionId,
                    )
                    val parentMessageResult = repository.loadMessagePage(
                        serverId,
                        normalizedDirectory,
                        parentSessionId,
                    )
                    if (!refreshGate.isCurrent(requestId)) return
                    parentMessageResult.onSuccess {
                        store.putMessagePage(
                            serverId,
                            normalizedDirectory,
                            parentSessionId,
                            page = it,
                            before = null,
                            firstPageRequestGeneration = parentMessageRequest.generation,
                            expectedRevision = parentMessageRequest.expectedRevision,
                        )
                    }.onFailure { throwable ->
                        if (throwable is CancellationException) throw throwable
                    }
                }
        }
        if (!refreshGate.isCurrent(requestId)) return
        capabilitiesForModelResolution?.let { capabilities ->
            restoreInitialModelSelection(
                sessionsForModelResolution,
                capabilities,
                sessionId,
                messagesForModelResolution,
                requestId,
            )
        }
        if (refreshGate.isCurrent(requestId) && localState.value.activeTab == SessionDetailTab.Changes) {
            loadReview(force = true, refreshRequestId = requestId)
        }
    }

    private fun isChildSessionId(sessionId: String): Boolean = uiState.value.project.sessions
        .firstOrNull { it.id == sessionId }
        ?.parentId
        ?.isNotBlank() == true

    private suspend fun loadReview(
        force: Boolean = false,
        modeOverride: ReviewMode? = null,
        refreshRequestId: Long? = null,
    ) {
        if (refreshRequestId != null && !refreshGate.isCurrent(refreshRequestId)) return
        val state = uiState.value
        val sessionId = state.currentSessionId
        if (sessionId == OpenCodePromptSender.NEW_SESSION_ID) return

        val mode = modeOverride
            ?.takeIf { it in state.availableReviewModes }
            ?: state.reviewMode
        val key = ReviewLoadKey(sessionId = sessionId, mode = mode, isGitProject = state.isGitProject)
        if (!force && localState.value.loadedReviewKey == key) return

        localState.update { it.copy(isReviewLoading = true, reviewError = null) }
        val result = when (mode) {
            ReviewMode.Git -> repository.loadVcsDiff(serverId, normalizedDirectory, mode = "git")
            ReviewMode.Turn -> repository.loadSessionDiff(serverId, normalizedDirectory, sessionId)
        }
        if (refreshRequestId != null && !refreshGate.isCurrent(refreshRequestId)) return
        result.onSuccess { diffs ->
            localState.update {
                it.copy(
                    reviewDiffs = diffs,
                    isReviewLoading = false,
                    reviewError = null,
                    loadedReviewKey = key,
                )
            }
        }.onFailure { throwable ->
            localState.update {
                it.copy(
                    reviewDiffs = emptyList(),
                    isReviewLoading = false,
                    reviewError = throwable.toErrorUiText(R.string.review_load_failed),
                    loadedReviewKey = null,
                )
            }
        }
    }

    private fun refreshServerHealth(requestId: Long) {
        viewModelScope.launch {
            val server = try {
                serverRepository.getServer(serverId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            server?.let {
                serverRepository.checkHealth(it).onSuccess { health ->
                    if (refreshGate.isCurrent(requestId)) {
                        localState.update { it.copy(serverVersion = health.version) }
                    }
                }
            }
        }
    }

    private suspend fun restoreInitialModelSelection(
        sessions: List<OpenCodeSession>,
        capabilities: PromptCapabilities,
        sessionId: String,
        messages: List<OpenCodeMessage>,
        refreshRequestId: Long,
    ) {
        if (!refreshGate.isCurrent(refreshRequestId)) return
        val local = localState.value
        if (local.modelPreferenceRestored || local.currentSessionId != sessionId) return
        val preference = serverRepository.getComposerModelPreference(serverId)
        if (!refreshGate.isCurrent(refreshRequestId)) return
        val selection = resolveInitialPromptModelSelection(
            initialSelection = local.selectedModel,
            session = sessions.firstOrNull { it.id == sessionId },
            messages = messages,
            preference = preference,
            capabilities = capabilities,
        )
        localState.update { current ->
            if (current.modelPreferenceRestored || current.currentSessionId != sessionId) {
                current
            } else if (current.selectedModel != local.selectedModel && current.selectedModel != null) {
                current.copy(modelPreferenceRestored = true)
            } else {
                current.copy(
                    selectedModel = selection,
                    selectedVariant = selection?.variant,
                    modelPreferenceRestored = true,
                )
            }
        }
    }

    private fun saveModelPreference(selection: PromptModelSelection) {
        viewModelScope.launch {
            serverRepository.saveComposerModelPreference(serverId, selection)
        }
    }

    private fun OpenCodeProjectState.toUiState(local: SessionLocalState, serverConfig: ServerConfig?): SessionDetailUiState {
        val status = statuses[local.currentSessionId]?.type ?: "idle"
        val currentSession = sessions.firstOrNull { it.id == local.currentSessionId }
        val parentSessionId = currentSession?.parentId?.takeIf { it.isNotBlank() }
        val isChildSession = parentSessionId != null
        val parentSession = parentSessionId?.let { parentId -> sessions.firstOrNull { it.id == parentId } }
        val childTitle = currentSession?.takeIf { isChildSession }?.let { session ->
            childTaskDescription(
                childSessionId = session.id,
                parentSessionId = parentSessionId.orEmpty(),
                messagesBySession = messagesBySession,
                partsByMessage = partsByMessage,
            ) ?: session.title.withoutSubagentSuffix().ifBlank { session.id }
        }
        val pendingQuestion = findPendingQuestion(
            currentSessionId = local.currentSessionId,
            sessions = sessions,
            questionsBySession = questionsBySession,
        )
        val pendingPermission = findPendingPermission(
            currentSessionId = local.currentSessionId,
            sessions = sessions,
            permissionsBySession = permissionsBySession,
        )
        val baseWorking = status != "idle" && status != "done" && status != "complete"
        val isWorking = baseWorking || pendingQuestion != null || pendingPermission != null
        val activeBranchStartMessageId = local.revertBranchStartMessageId.takeIf { currentSession?.revert != null }
        val isRevertBranchCommitting = currentSession?.revert != null && activeBranchStartMessageId != null
        val action = if (
            !isChildSession &&
            pendingQuestion == null &&
            pendingPermission == null &&
            !local.isReadingAttachments &&
            !local.isRevertActionInProgress &&
            !isRevertBranchCommitting
        ) {
            stateMachine.evaluate(
                PromptSendInput(
                    text = local.composer,
                    hasAttachments = local.attachments.isNotEmpty(),
                    hasContext = local.projectFileContexts.isNotEmpty(),
                    isWorking = isWorking,
                ),
            )
        } else {
            PromptSendAction.Disabled
        }
        val selectedModelDefinition = local.selectedModel?.findModel(models)
        val selectedVariant = selectedModelDefinition?.let { model ->
            local.selectedVariant?.takeIf { variant -> variant in model.variants }
        }
        val selectedModel = local.selectedModel?.takeIf { selectedModelDefinition != null }?.copy(variant = selectedVariant)
        val currentMessages = messagesBySession[local.currentSessionId].orEmpty().map { message ->
            message.copy(parts = partsByMessage[message.id].orEmpty())
        }
        val messageProjection = projectSessionMessages(
            messages = currentMessages,
            revert = currentSession?.revert,
            branchStartMessageId = activeBranchStartMessageId,
        )
        val isGitProject = project?.vcs.equals("git", ignoreCase = true)
        val availableReviewModes = if (isGitProject) {
            listOf(ReviewMode.Git, ReviewMode.Turn)
        } else {
            listOf(ReviewMode.Turn)
        }
        val reviewMode = local.reviewMode.takeIf { it in availableReviewModes } ?: availableReviewModes.first()
        return SessionDetailUiState(
            serverId = serverId,
            directory = normalizedDirectory,
            project = this,
            serverConfig = serverConfig,
            serverVersion = local.serverVersion,
            mcpActionName = local.mcpActionName,
            currentSessionId = local.currentSessionId,
            activeTab = local.activeTab,
            title = currentSession?.title ?: if (local.currentSessionId == OpenCodePromptSender.NEW_SESSION_ID) "" else local.currentSessionId,
            isChildSession = isChildSession,
            parentSessionId = parentSessionId,
            parentTitle = parentSession?.title,
            childTitle = childTitle,
            messages = currentMessages,
            visibleMessages = messageProjection.visibleMessages,
            revertedUserMessages = messageProjection.revertedUserMessages,
            revert = currentSession?.revert,
            agents = agents,
            models = models,
            commands = commands,
            composer = local.composer,
            attachments = local.attachments,
            projectFileContexts = local.projectFileContexts,
            selectedAgent = resolveComposerAgent(
                localOverride = local.selectedAgentOverride,
                sessionAgent = currentSession?.agent,
                messages = currentMessages,
                agents = agents,
            ),
            selectedModel = selectedModel,
            selectedModelLabel = selectedModel?.let { selection ->
                models.firstOrNull { it.providerId == selection.providerId && it.modelId == selection.modelId }?.name
                    ?: selection.modelId
            },
            selectedVariant = selectedVariant,
            contextUsage = currentSession?.let { buildSessionContextUsage(it, currentMessages, models) },
            pendingQuestion = pendingQuestion,
            pendingPermission = pendingPermission,
            reviewMode = reviewMode,
            availableReviewModes = availableReviewModes,
            reviewDiffs = local.reviewDiffs,
            isReviewLoading = local.isReviewLoading,
            reviewError = local.reviewError,
            isGitProject = isGitProject,
            status = status,
            isWorking = isWorking,
            isLoading = isLoading,
            hasOlderMessages = messageHistoryBySession[local.currentSessionId]?.hasOlderMessages == true,
            isLoadingOlderMessages = local.isLoadingOlderMessages,
            isSending = local.isSending,
            isReadingAttachments = local.isReadingAttachments,
            isQuestionActionInProgress = local.isQuestionActionInProgress,
            isPermissionActionInProgress = local.isPermissionActionInProgress,
            isSessionActionInProgress = local.isSessionActionInProgress,
            isRevertActionInProgress = local.isRevertActionInProgress,
            isRevertBranchCommitting = isRevertBranchCommitting,
            isUpdatingProjectName = local.isUpdatingProjectName,
            projectNameError = local.projectNameError,
            submitAction = action,
            error = local.error ?: error,
        )
    }

    override fun onCleared() {
        sessionVisibilityLease.release()
        projectConnectionLease.release()
        globalConnectionLease.release()
    }
}

private data class SessionLocalState(
    val currentSessionId: String,
    val activeTab: SessionDetailTab = SessionDetailTab.Session,
    val composer: String = "",
    val attachments: List<PromptAttachment> = emptyList(),
    val projectFileContexts: List<ProjectFileContext> = emptyList(),
    val selectedAgentOverride: String? = null,
    val selectedModel: PromptModelSelection? = null,
    val selectedVariant: String? = null,
    val isSending: Boolean = false,
    val isLoadingOlderMessages: Boolean = false,
    val isReadingAttachments: Boolean = false,
    val isQuestionActionInProgress: Boolean = false,
    val isPermissionActionInProgress: Boolean = false,
    val isSessionActionInProgress: Boolean = false,
    val isRevertActionInProgress: Boolean = false,
    val revertBranchStartMessageId: String? = null,
    val isUpdatingProjectName: Boolean = false,
    val projectNameError: UiText? = null,
    val error: UiText? = null,
    val modelPreferenceRestored: Boolean = false,
    val serverVersion: String? = null,
    val mcpActionName: String? = null,
    val reviewMode: ReviewMode = ReviewMode.Git,
    val reviewDiffs: List<OpenCodeDiffFile> = emptyList(),
    val isReviewLoading: Boolean = false,
    val reviewError: UiText? = null,
    val loadedReviewKey: ReviewLoadKey? = null,
) {
    override fun toString(): String =
        "SessionLocalState(currentSessionId=<redacted>, activeTab=$activeTab, " +
            "composerLength=${composer.length}, attachmentCount=${attachments.size}, " +
            "projectFileContextCount=${projectFileContexts.size}, " +
            "selectedAgentPresent=${selectedAgentOverride != null}, selectedModelPresent=${selectedModel != null}, " +
            "selectedVariantPresent=${selectedVariant != null}, isSending=$isSending, " +
            "isLoadingOlderMessages=$isLoadingOlderMessages, " +
            "isReadingAttachments=$isReadingAttachments, isQuestionActionInProgress=$isQuestionActionInProgress, " +
            "isPermissionActionInProgress=$isPermissionActionInProgress, " +
            "isSessionActionInProgress=$isSessionActionInProgress, " +
            "isRevertActionInProgress=$isRevertActionInProgress, " +
            "revertBranchStartMessageId=${redactedIfPresent(revertBranchStartMessageId)}, " +
            "isUpdatingProjectName=$isUpdatingProjectName, projectNameErrorPresent=${projectNameError != null}, " +
            "errorPresent=${error != null}, modelPreferenceRestored=$modelPreferenceRestored, " +
            "serverVersionPresent=${serverVersion != null}, mcpActionNamePresent=${mcpActionName != null}, " +
            "reviewMode=$reviewMode, reviewDiffCount=${reviewDiffs.size}, isReviewLoading=$isReviewLoading, " +
            "reviewErrorPresent=${reviewError != null}, loadedReviewKeyPresent=${loadedReviewKey != null})"
}

private data class ReviewLoadKey(
    val sessionId: String,
    val mode: ReviewMode,
    val isGitProject: Boolean,
) {
    override fun toString(): String =
        "ReviewLoadKey(sessionId=<redacted>, mode=$mode, isGitProject=$isGitProject)"
}

enum class SessionDetailTab {
    Session,
    Changes,
}

enum class ReviewMode {
    Git,
    Turn,
}

data class SessionDetailUiState(
    val serverId: String,
    val directory: String,
    val project: OpenCodeProjectState,
    val serverConfig: ServerConfig?,
    val serverVersion: String?,
    val mcpActionName: String?,
    val currentSessionId: String,
    val activeTab: SessionDetailTab,
    val title: String,
    val isChildSession: Boolean,
    val parentSessionId: String?,
    val parentTitle: String?,
    val childTitle: String?,
    val messages: List<OpenCodeMessage>,
    val visibleMessages: List<OpenCodeMessage>,
    val revertedUserMessages: List<OpenCodeMessage>,
    val revert: OpenCodeSessionRevert?,
    val agents: List<OpenCodeAgent>,
    val models: List<OpenCodeModel>,
    val commands: List<OpenCodeCommand>,
    val composer: String,
    val attachments: List<PromptAttachment>,
    val projectFileContexts: List<ProjectFileContext>,
    val selectedAgent: String?,
    val selectedModel: PromptModelSelection?,
    val selectedModelLabel: String?,
    val selectedVariant: String?,
    val contextUsage: SessionContextUsage?,
    val pendingQuestion: OpenCodeQuestionRequest?,
    val pendingPermission: OpenCodePermissionRequest?,
    val reviewMode: ReviewMode,
    val availableReviewModes: List<ReviewMode>,
    val reviewDiffs: List<OpenCodeDiffFile>,
    val isReviewLoading: Boolean,
    val reviewError: UiText?,
    val isGitProject: Boolean,
    val status: String,
    val isWorking: Boolean,
    val isLoading: Boolean,
    val hasOlderMessages: Boolean,
    val isLoadingOlderMessages: Boolean,
    val isSending: Boolean,
    val isReadingAttachments: Boolean,
    val isQuestionActionInProgress: Boolean,
    val isPermissionActionInProgress: Boolean,
    val isSessionActionInProgress: Boolean,
    val isRevertActionInProgress: Boolean,
    val isRevertBranchCommitting: Boolean,
    val isUpdatingProjectName: Boolean,
    val projectNameError: UiText?,
    val submitAction: PromptSendAction,
    val error: UiText?,
) {
    override fun toString(): String =
        "SessionDetailUiState(serverId=<redacted>, directory=<redacted>, " +
            "projectDataRevision=${project.projectDataRevision}, messageDataRevision=${project.messageDataRevision}, " +
            "serverConfigPresent=${serverConfig != null}, serverVersionPresent=${serverVersion != null}, " +
            "mcpActionNamePresent=${mcpActionName != null}, currentSessionId=<redacted>, activeTab=$activeTab, " +
            "titleLength=${title.length}, isChildSession=$isChildSession, " +
            "parentSessionId=${redactedIfPresent(parentSessionId)}, parentTitlePresent=${parentTitle != null}, " +
            "childTitlePresent=${childTitle != null}, messageCount=${messages.size}, " +
            "visibleMessageCount=${visibleMessages.size}, revertedUserMessageCount=${revertedUserMessages.size}, " +
            "revertPresent=${revert != null}, agentCount=${agents.size}, modelCount=${models.size}, " +
            "commandCount=${commands.size}, composerLength=${composer.length}, attachmentCount=${attachments.size}, " +
            "projectFileContextCount=${projectFileContexts.size}, " +
            "selectedAgentPresent=${selectedAgent != null}, selectedModelPresent=${selectedModel != null}, " +
            "selectedModelLabelPresent=${selectedModelLabel != null}, selectedVariantPresent=${selectedVariant != null}, " +
            "contextUsagePresent=${contextUsage != null}, pendingQuestionPresent=${pendingQuestion != null}, " +
            "pendingPermissionPresent=${pendingPermission != null}, reviewMode=$reviewMode, " +
            "availableReviewModeCount=${availableReviewModes.size}, reviewDiffCount=${reviewDiffs.size}, " +
            "isReviewLoading=$isReviewLoading, reviewErrorPresent=${reviewError != null}, " +
            "isGitProject=$isGitProject, status=$status, isWorking=$isWorking, isLoading=$isLoading, " +
            "hasOlderMessages=$hasOlderMessages, isLoadingOlderMessages=$isLoadingOlderMessages, " +
            "isSending=$isSending, isReadingAttachments=$isReadingAttachments, " +
            "isQuestionActionInProgress=$isQuestionActionInProgress, " +
            "isPermissionActionInProgress=$isPermissionActionInProgress, " +
            "isSessionActionInProgress=$isSessionActionInProgress, " +
            "isRevertActionInProgress=$isRevertActionInProgress, " +
            "isRevertBranchCommitting=$isRevertBranchCommitting, " +
            "isUpdatingProjectName=$isUpdatingProjectName, projectNameErrorPresent=${projectNameError != null}, " +
            "submitAction=$submitAction, errorPresent=${error != null})"
}

internal data class AttachmentReadStartResult(
    val started: Boolean,
    val failureReason: LocalAttachmentFailureReason? = null,
)

data class SessionContextUsage(
    val sessionTitle: String,
    val messageCount: Int,
    val providerId: String?,
    val modelName: String?,
    val contextLimit: Long?,
    val currentTurnTokens: Long?,
    val totalTokens: Long?,
    val usagePercent: Int?,
    val usageRatio: Float?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val reasoningTokens: Long?,
    val cacheReadTokens: Long?,
    val cacheWriteTokens: Long?,
    val userMessageCount: Int,
    val assistantMessageCount: Int,
    val cost: Double?,
    val createdAt: Long?,
    val updatedAt: Long?,
    val rawMessages: List<SessionContextMessage>,
) {
    override fun toString(): String =
        "SessionContextUsage(sessionTitle=<redacted>, messageCount=$messageCount, " +
            "providerPresent=${providerId != null}, modelPresent=${modelName != null}, " +
            "contextLimitPresent=${contextLimit != null}, currentTurnTokensPresent=${currentTurnTokens != null}, " +
            "totalTokensPresent=${totalTokens != null}, usagePercent=$usagePercent, usageRatio=$usageRatio, " +
            "inputTokensPresent=${inputTokens != null}, outputTokensPresent=${outputTokens != null}, " +
            "reasoningTokensPresent=${reasoningTokens != null}, cacheReadTokensPresent=${cacheReadTokens != null}, " +
            "cacheWriteTokensPresent=${cacheWriteTokens != null}, userMessageCount=$userMessageCount, " +
            "assistantMessageCount=$assistantMessageCount, costPresent=${cost != null}, " +
            "created=${createdAt != null}, updated=${updatedAt != null}, rawMessageCount=${rawMessages.size})"
}

data class SessionContextMessage(
    val role: String,
    val id: String,
    val createdAt: Long?,
) {
    override fun toString(): String =
        "SessionContextMessage(role=$role, id=<redacted>, created=${createdAt != null})"
}

private fun redactedIfPresent(value: Any?): String = if (value == null) "null" else "<redacted>"

internal fun findPendingQuestion(
    currentSessionId: String,
    sessions: List<OpenCodeSession>,
    questionsBySession: Map<String, List<OpenCodeQuestionRequest>>,
): OpenCodeQuestionRequest? {
    if (currentSessionId == OpenCodePromptSender.NEW_SESSION_ID) return null
    val childrenByParent = sessions
        .filter { !it.parentId.isNullOrBlank() }
        .groupBy { it.parentId.orEmpty() }
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(currentSessionId)
    while (queue.isNotEmpty()) {
        val sessionId = queue.removeFirst()
        if (!visited.add(sessionId)) continue
        questionsBySession[sessionId]?.sortedBy { it.id }?.firstOrNull()?.let { return it }
        childrenByParent[sessionId].orEmpty().forEach { child -> queue.add(child.id) }
    }
    return null
}

internal fun findPendingPermission(
    currentSessionId: String,
    sessions: List<OpenCodeSession>,
    permissionsBySession: Map<String, List<OpenCodePermissionRequest>>,
): OpenCodePermissionRequest? {
    if (currentSessionId == OpenCodePromptSender.NEW_SESSION_ID) return null
    val childrenByParent = sessions
        .filter { !it.parentId.isNullOrBlank() }
        .groupBy { it.parentId.orEmpty() }
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(currentSessionId)
    while (queue.isNotEmpty()) {
        val sessionId = queue.removeFirst()
        if (!visited.add(sessionId)) continue
        permissionsBySession[sessionId]?.sortedBy { it.id }?.firstOrNull()?.let { return it }
        childrenByParent[sessionId].orEmpty().forEach { child -> queue.add(child.id) }
    }
    return null
}

internal fun buildSessionContextUsage(
    session: OpenCodeSession,
    messages: List<OpenCodeMessage>,
    models: List<OpenCodeModel>,
): SessionContextUsage {
    val usageMessage = messages.lastOrNull { message ->
        message.role.equals("assistant", ignoreCase = true) && (message.tokens?.total ?: 0L) > 0L
    }
    val providerId = usageMessage?.modelProviderId ?: session.modelProviderId ?: session.modelLabelProviderId()
    val modelId = usageMessage?.modelId ?: session.modelId ?: session.modelLabelModelId()
    val model = models.firstOrNull { it.providerId == providerId && it.modelId == modelId }
    val tokens = session.tokens
    val totalTokens = tokens?.total
    val currentTurnTokens = usageMessage?.tokens?.total
    val contextLimit = model?.contextLimit
    val usageRatio = if (currentTurnTokens != null && contextLimit != null && contextLimit > 0) {
        currentTurnTokens.toFloat() / contextLimit.toFloat()
    } else {
        null
    }
    return SessionContextUsage(
        sessionTitle = session.title,
        messageCount = messages.size,
        providerId = providerId,
        modelName = model?.name ?: modelId ?: session.modelLabel,
        contextLimit = contextLimit,
        currentTurnTokens = currentTurnTokens,
        totalTokens = totalTokens,
        usagePercent = usageRatio?.let { (it * 100).roundToInt().coerceAtLeast(0) },
        usageRatio = usageRatio,
        inputTokens = tokens?.input,
        outputTokens = tokens?.output,
        reasoningTokens = tokens?.reasoning,
        cacheReadTokens = tokens?.cacheRead,
        cacheWriteTokens = tokens?.cacheWrite,
        userMessageCount = messages.count { it.role.equals("user", ignoreCase = true) },
        assistantMessageCount = messages.count { it.role.equals("assistant", ignoreCase = true) },
        cost = session.cost,
        createdAt = session.createdAt,
        updatedAt = usageMessage?.createdAt ?: session.updatedAt,
        rawMessages = messages.map { message ->
            SessionContextMessage(
                role = message.role,
                id = message.id,
                createdAt = message.createdAt,
            )
        },
    )
}

private fun OpenCodeSession.modelLabelProviderId(): String? = modelLabel
    ?.split("/")
    ?.takeIf { it.size >= 2 }
    ?.getOrNull(0)

private fun OpenCodeSession.modelLabelModelId(): String? = modelLabel
    ?.split("/")
    ?.let { parts -> if (parts.size >= 2) parts[1] else parts.firstOrNull() }

private fun childTaskDescription(
    childSessionId: String,
    parentSessionId: String,
    messagesBySession: Map<String, List<OpenCodeMessage>>,
    partsByMessage: Map<String, List<io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart>>,
): String? = messagesBySession[parentSessionId]
    .orEmpty()
    .asSequence()
    .flatMap { message -> partsByMessage[message.id].orEmpty().asSequence() }
    .filter { part -> part.tool == "task" && part.toolMetadata.firstValue("sessionId", "sessionID") == childSessionId }
    .mapNotNull { part -> part.toolInput.firstValue("description") ?: part.toolMetadata.firstValue("title", "description") }
    .lastOrNull()

private fun String.withoutSubagentSuffix(): String = replace(SubagentTitleSuffixRegex, "").trim()

private fun Map<String, String>.firstValue(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    get(key)?.takeIf { it.isNotBlank() }
}

private val SubagentTitleSuffixRegex = Regex("\\s+\\(@[^)]+ subagent\\)$")

private fun replaceActiveMention(input: String, agentId: String): String {
    val lastSpace = input.lastIndexOf(' ')
    val tokenStart = if (lastSpace == -1) 0 else lastSpace + 1
    val prefix = input.take(tokenStart)
    val suffix = input.drop(tokenStart)
    return if (suffix.startsWith("@")) "$prefix@$agentId " else "$input @$agentId "
}

private fun Throwable.toPromptUiText(@StringRes fallback: Int): UiText = when (this) {
    is PromptCapabilitiesNotLoadedException -> UiText.Resource(R.string.prompt_error_capabilities_not_loaded)
    is PromptCapabilitiesStaleException -> UiText.Resource(R.string.prompt_error_capabilities_changed)
    is PromptModelSelectionRequiredException -> UiText.Resource(R.string.prompt_error_model_required)
    is PromptModelUnavailableException -> UiText.Resource(R.string.prompt_error_model_unavailable)
    is PromptAgentUnavailableException -> UiText.Resource(R.string.prompt_error_agent_unavailable)
    is PromptVariantUnavailableException -> UiText.Resource(R.string.prompt_error_variant_unavailable)
    is PromptOperationInProgressException -> UiText.Resource(R.string.prompt_error_operation_in_progress)
    is PromptAttachmentCountLimitException -> UiText.Resource(
        R.string.attachment_count_limit,
        listOf(AttachmentLimits.MAX_ATTACHMENT_COUNT),
    )
    is PromptAttachmentFileTooLargeException -> UiText.Resource(
        R.string.attachment_too_large,
        listOf(AttachmentLimits.MAX_FILE_BYTES / (1024L * 1024L)),
    )
    is PromptAttachmentTotalSizeLimitException -> UiText.Resource(
        R.string.attachment_total_size_limit,
        listOf(AttachmentLimits.MAX_TOTAL_BYTES / (1024L * 1024L)),
    )
    is PromptAttachmentMalformedException,
    is PromptAttachmentMetadataInvalidException -> UiText.Resource(R.string.attachment_invalid)
    is PromptProjectContextCountLimitException -> UiText.Resource(
        R.string.project_file_context_count_limit,
        listOf(ProjectFileContextLimits.MAX_CONTEXT_COUNT),
    )
    is PromptProjectContextInvalidException -> UiText.Resource(R.string.project_file_context_invalid)
    is PromptSendOutcomeUncertainException -> UiText.Resource(R.string.prompt_error_outcome_uncertain)
    else -> toErrorUiText(fallback)
}

private fun SessionRevertDraftFailure.toUiText(): UiText = when (this) {
    SessionRevertDraftFailure.AttachmentCountLimit -> UiText.Resource(
        R.string.attachment_count_limit,
        listOf(AttachmentLimits.MAX_ATTACHMENT_COUNT),
    )
    SessionRevertDraftFailure.AttachmentTotalSizeLimit -> UiText.Resource(
        R.string.attachment_total_size_limit,
        listOf(AttachmentLimits.MAX_TOTAL_BYTES / (1024L * 1024L)),
    )
    SessionRevertDraftFailure.AttachmentTooLarge -> UiText.Resource(
        R.string.attachment_too_large,
        listOf(AttachmentLimits.MAX_FILE_BYTES / (1024L * 1024L)),
    )
    SessionRevertDraftFailure.AttachmentInvalid -> UiText.Resource(R.string.attachment_invalid)
    SessionRevertDraftFailure.ProjectContextCountLimit -> UiText.Resource(
        R.string.project_file_context_count_limit,
        listOf(ProjectFileContextLimits.MAX_CONTEXT_COUNT),
    )
    SessionRevertDraftFailure.ProjectContextInvalid -> UiText.Resource(R.string.project_file_context_invalid)
}
