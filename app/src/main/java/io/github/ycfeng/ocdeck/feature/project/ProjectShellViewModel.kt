package io.github.ycfeng.ocdeck.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.network.GlobalConnectionLease
import io.github.ycfeng.ocdeck.core.network.OpenCodeEventClient
import io.github.ycfeng.ocdeck.core.network.ProjectConnectionLease
import io.github.ycfeng.ocdeck.core.network.SessionListWindowCoordinator
import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.core.util.LatestRequestGate
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.opencode.OpenCodeRepository
import io.github.ycfeng.ocdeck.data.project.RecentProjectRecorder
import io.github.ycfeng.ocdeck.data.server.ServerComposerModelPreference
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerRepository
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMcp
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.feature.composer.findModel
import io.github.ycfeng.ocdeck.feature.composer.resolveComposerAgent
import io.github.ycfeng.ocdeck.feature.composer.selectPromptModel
import io.github.ycfeng.ocdeck.feature.composer.selectPromptVariant
import io.github.ycfeng.ocdeck.feature.composer.toComposerAgentOrNull
import io.github.ycfeng.ocdeck.feature.composer.toValidPromptModelSelection
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProjectShellViewModel(
    private val serverId: String,
    directory: String,
    private val repository: OpenCodeRepository,
    private val serverRepository: ServerRepository,
    private val store: InMemoryOpenCodeStore,
    private val eventClient: OpenCodeEventClient,
    private val sessionListWindowCoordinator: SessionListWindowCoordinator,
    private val recentProjectRecorder: RecentProjectRecorder,
    pathNormalizer: PathNormalizer,
) : ViewModel() {
    val normalizedDirectory = pathNormalizer.normalize(directory)
    private val localState = MutableStateFlow(ProjectShellLocalState())
    private val refreshGate = LatestRequestGate()
    private lateinit var projectConnectionLease: ProjectConnectionLease
    private lateinit var globalConnectionLease: GlobalConnectionLease

    val uiState: StateFlow<ProjectShellUiState> = combine(
        store.observeProject(serverId, normalizedDirectory),
        serverRepository.observeComposerModelPreference(serverId),
        serverRepository.observeServers(),
        localState,
    ) { project, preference, servers, local ->
        project.toUiState(
            preference = preference,
            serverConfig = servers.firstOrNull { it.id == serverId },
            local = local,
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OpenCodeProjectState(serverId = serverId, normalizedDirectory = normalizedDirectory).toUiState(
                preference = null,
                serverConfig = null,
                local = ProjectShellLocalState(),
            ),
    )

    init {
        refresh()
        recentProjectRecorder.recordAdd(serverId, normalizedDirectory)
        projectConnectionLease = eventClient.connectProject(serverId, normalizedDirectory)
        globalConnectionLease = eventClient.connectGlobal(serverId)
    }

    fun refresh() {
        val requestId = refreshGate.begin()
        viewModelScope.launch { refreshSnapshot(requestId) }
        refreshServerHealth(requestId)
    }

    fun toggleMcp(mcp: OpenCodeMcp) {
        if (mcp.status.equals("needs_auth", ignoreCase = true)) {
            store.setProjectLoading(
                serverId = serverId,
                directory = normalizedDirectory,
                isLoading = false,
                error = UiText.Resource(R.string.project_error_mcp_needs_auth, listOf(mcp.name)),
            )
            return
        }
        viewModelScope.launch {
            localState.update { it.copy(mcpActionName = mcp.name) }
            val result = if (mcp.status.equals("connected", ignoreCase = true)) {
                repository.disconnectMcp(serverId, normalizedDirectory, mcp.name)
            } else {
                repository.connectMcp(serverId, normalizedDirectory, mcp.name)
            }
            result.onSuccess { refreshSnapshot(refreshGate.begin()) }
                .onFailure { throwable ->
                    store.setProjectLoading(
                        serverId = serverId,
                        directory = normalizedDirectory,
                        isLoading = false,
                        error = throwable.toErrorUiText(R.string.project_error_mcp_action_failed),
                    )
                }
            localState.update { it.copy(mcpActionName = null) }
        }
    }

    fun selectAgent(agentId: String) {
        val agent = agentId.toComposerAgentOrNull() ?: return
        if (uiState.value.project.agents.none { it.id == agent }) return
        localState.update { it.copy(selectedAgentOverride = agent) }
    }

    fun selectModel(model: OpenCodeModel) {
        val selection = uiState.value.selectedModel.selectPromptModel(model)
        localState.update { it.copy(selectedModelOverride = selection) }
        saveModelPreference(selection)
    }

    fun selectVariant(variant: String?) {
        val state = uiState.value
        val selection = state.selectedModel?.selectPromptVariant(variant, state.project.models) ?: return
        localState.update { it.copy(selectedModelOverride = selection) }
        saveModelPreference(selection)
    }

    fun archiveSession(sessionId: String) {
        if (sessionId.isBlank()) return
        viewModelScope.launch {
            repository.archiveSession(
                serverId = serverId,
                directory = normalizedDirectory,
                sessionId = sessionId,
            ).onSuccess {
                store.removeSession(serverId, normalizedDirectory, sessionId)
            }.onFailure { throwable ->
                store.setProjectLoading(
                    serverId = serverId,
                    directory = normalizedDirectory,
                    isLoading = false,
                    error = throwable.toErrorUiText(R.string.session_archive_failed),
                )
            }
        }
    }

    fun loadMoreSessions() {
        sessionListWindowCoordinator.loadMore(serverId, normalizedDirectory)
    }

    fun retrySessionListWindow() {
        sessionListWindowCoordinator.retry(serverId, normalizedDirectory)
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

    private suspend fun refreshSnapshot(requestId: Long) {
        if (!refreshGate.isCurrent(requestId)) return
        store.setProjectLoading(serverId, normalizedDirectory, isLoading = true)
        val expectedRevision = store.captureProjectDataRevision(serverId, normalizedDirectory)
        val sessionWindow = store.captureSessionListWindow(serverId, normalizedDirectory)
        repository.loadProject(
            serverId = serverId,
            directory = normalizedDirectory,
            sessionWindow = sessionWindow,
        )
            .onSuccess { snapshot ->
                if (!refreshGate.isCurrent(requestId)) return@onSuccess
                if (!store.applyProjectSnapshotIfRevision(snapshot, expectedRevision)) {
                    store.setProjectLoading(serverId, normalizedDirectory, isLoading = false)
                }
            }
            .onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                if (!refreshGate.isCurrent(requestId)) return@onFailure
                store.setProjectLoading(
                    serverId,
                    normalizedDirectory,
                    isLoading = false,
                    error = throwable.toErrorUiText(R.string.project_snapshot_load_failed),
                )
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

    private fun saveModelPreference(selection: PromptModelSelection) {
        viewModelScope.launch {
            serverRepository.saveComposerModelPreference(serverId, selection)
        }
    }

    override fun onCleared() {
        projectConnectionLease.release()
        globalConnectionLease.release()
    }

    private fun OpenCodeProjectState.toUiState(
        preference: ServerComposerModelPreference?,
        serverConfig: ServerConfig?,
        local: ProjectShellLocalState,
    ): ProjectShellUiState {
        val selection = local.selectedModelOverride.toValidPromptModelSelection(models)
            ?: preference.toValidPromptModelSelection(models)
        val selectedModel = selection?.findModel(models)
        return ProjectShellUiState(
            project = this,
            serverConfig = serverConfig,
            serverVersion = local.serverVersion,
            mcpActionName = local.mcpActionName,
            isUpdatingProjectName = local.isUpdatingProjectName,
            projectNameError = local.projectNameError,
            selectedAgent = resolveComposerAgent(
                localOverride = local.selectedAgentOverride,
                sessionAgent = null,
                messages = emptyList(),
                agents = agents,
            ),
            selectedModel = selection,
            selectedModelLabel = selectedModel?.name,
            selectedVariant = selection?.variant,
            showVariant = selectedModel?.variants?.isNotEmpty() == true,
        )
    }
}

private data class ProjectShellLocalState(
    val serverVersion: String? = null,
    val mcpActionName: String? = null,
    val isUpdatingProjectName: Boolean = false,
    val projectNameError: UiText? = null,
    val selectedAgentOverride: String? = null,
    val selectedModelOverride: PromptModelSelection? = null,
) {
    override fun toString(): String =
        "ProjectShellLocalState(serverVersionPresent=${serverVersion != null}, " +
            "mcpActionNamePresent=${mcpActionName != null}, isUpdatingProjectName=$isUpdatingProjectName, " +
            "projectNameErrorPresent=${projectNameError != null}, " +
            "selectedAgentPresent=${selectedAgentOverride != null}, " +
            "selectedModelPresent=${selectedModelOverride != null})"
}

data class ProjectShellUiState(
    val project: OpenCodeProjectState,
    val serverConfig: ServerConfig? = null,
    val serverVersion: String? = null,
    val mcpActionName: String? = null,
    val isUpdatingProjectName: Boolean = false,
    val projectNameError: UiText? = null,
    val selectedAgent: String? = null,
    val selectedModel: PromptModelSelection? = null,
    val selectedModelLabel: String? = null,
    val selectedVariant: String? = null,
    val showVariant: Boolean = false,
) {
    override fun toString(): String =
        "ProjectShellUiState(projectDataRevision=${project.projectDataRevision}, " +
            "messageDataRevision=${project.messageDataRevision}, serverConfigPresent=${serverConfig != null}, " +
            "serverVersionPresent=${serverVersion != null}, mcpActionNamePresent=${mcpActionName != null}, " +
            "isUpdatingProjectName=$isUpdatingProjectName, projectNameErrorPresent=${projectNameError != null}, " +
            "selectedAgentPresent=${selectedAgent != null}, selectedModelPresent=${selectedModel != null}, " +
            "selectedModelLabelPresent=${selectedModelLabel != null}, " +
            "selectedVariantPresent=${selectedVariant != null}, showVariant=$showVariant)"
}
