package io.github.ycfeng.ocdeck.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.opencode.OpenCodeRepository
import io.github.ycfeng.ocdeck.data.project.RecentProjectStore
import io.github.ycfeng.ocdeck.domain.model.OpenCodeDirectorySuggestion
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProjectPickerViewModel(
    private val serverId: String,
    private val repository: OpenCodeRepository,
    private val recentProjectStore: RecentProjectStore,
    private val pathNormalizer: PathNormalizer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProjectPickerUiState())
    val uiState: StateFlow<ProjectPickerUiState> = _uiState.asStateFlow()
    private var suggestionJob: Job? = null

    init {
        viewModelScope.launch {
            recentProjectStore.observe(serverId).collect { projects ->
                _uiState.update { it.copy(recentProjects = projects) }
            }
        }
    }

    fun onDirectoryChanged(value: String) {
        _uiState.update { it.copy(directory = value, error = null) }
        scheduleSuggestions(value)
    }

    fun completeDirectoryFromSuggestion(directory: String): String {
        suggestionJob?.cancel()
        val completed = completeDirectorySuggestion(directory, pathNormalizer)
        _uiState.update {
            it.copy(
                directory = completed,
                suggestions = emptyList(),
                isSuggesting = false,
                suggestionError = null,
                error = null,
            )
        }
        scheduleSuggestions(completed)
        return completed
    }

    fun openProject(directory: String, onOpen: (String) -> Unit) {
        if (directory.isBlank()) {
            _uiState.update { it.copy(error = UiText.Resource(R.string.project_error_directory_empty)) }
            return
        }
        suggestionJob?.cancel()
        viewModelScope.launch {
            val normalized = pathNormalizer.normalize(directory)
            _uiState.update { it.copy(isSaving = true, error = null) }
            runCatching { recentProjectStore.add(serverId, normalized) }
                .onSuccess {
                    _uiState.update { state -> state.copy(isSaving = false, directory = normalized) }
                    onOpen(normalized)
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = throwable.toErrorUiText(R.string.project_error_save_recent_failed),
                        )
                    }
                }
        }
    }

    fun deleteRecentProject(project: ProjectRef) {
        val normalized = pathNormalizer.normalize(project.normalizedDirectory)
        viewModelScope.launch {
            _uiState.update { it.copy(deletingProjectDirectory = normalized, error = null) }
            runCatching { recentProjectStore.remove(serverId, normalized) }
                .onSuccess {
                    _uiState.update { it.copy(deletingProjectDirectory = null) }
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            deletingProjectDirectory = null,
                            error = throwable.toErrorUiText(R.string.project_error_delete_recent_failed),
                        )
                    }
                }
        }
    }

    private fun scheduleSuggestions(value: String) {
        suggestionJob?.cancel()
        val query = value.trim()
        if (query.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList(), isSuggesting = false, suggestionError = null) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(SUGGESTION_DEBOUNCE_MS)
            _uiState.update { it.copy(isSuggesting = true, suggestionError = null) }
            val searchRoot = _uiState.value.recentProjects.firstOrNull()?.normalizedDirectory?.let(::parentDirectory)
            repository.suggestProjectDirectories(
                serverId = serverId,
                input = query,
                searchRoot = searchRoot,
                limit = MAX_SUGGESTIONS,
            ).onSuccess { suggestions ->
                if (_uiState.value.directory.trim() == query) {
                    _uiState.update { it.copy(suggestions = suggestions, isSuggesting = false, suggestionError = null) }
                }
            }.onFailure {
                if (_uiState.value.directory.trim() == query) {
                    _uiState.update {
                        it.copy(
                            suggestions = emptyList(),
                            isSuggesting = false,
                            suggestionError = UiText.Resource(R.string.project_error_load_candidates_failed),
                        )
                    }
                }
            }
        }
    }

    private fun parentDirectory(directory: String): String? {
        val normalized = pathNormalizer.normalize(directory)
        if (normalized == "/" || WINDOWS_DRIVE_ROOT_REGEX.matches(normalized)) return normalized
        val separatorIndex = normalized.lastIndexOf('/')
        return when {
            separatorIndex < 0 -> null
            separatorIndex == 0 -> "/"
            separatorIndex == 2 && normalized.getOrNull(1) == ':' -> normalized.substring(0, separatorIndex + 1)
            else -> normalized.substring(0, separatorIndex)
        }
    }

    private companion object {
        const val SUGGESTION_DEBOUNCE_MS = 300L
        const val MAX_SUGGESTIONS = 8
        val WINDOWS_DRIVE_ROOT_REGEX = Regex("^[A-Za-z]:/$")
    }
}

internal fun completeDirectorySuggestion(directory: String, pathNormalizer: PathNormalizer): String {
    val normalized = pathNormalizer.normalize(directory)
    return if (normalized.isEmpty() || normalized.endsWith('/')) normalized else "$normalized/"
}

data class ProjectPickerUiState(
    val directory: String = "",
    val recentProjects: List<ProjectRef> = emptyList(),
    val suggestions: List<OpenCodeDirectorySuggestion> = emptyList(),
    val isSuggesting: Boolean = false,
    val suggestionError: UiText? = null,
    val isSaving: Boolean = false,
    val deletingProjectDirectory: String? = null,
    val error: UiText? = null,
) {
    override fun toString(): String =
        "ProjectPickerUiState(directory=<redacted>, recentProjectCount=${recentProjects.size}, " +
            "suggestionCount=${suggestions.size}, isSuggesting=$isSuggesting, " +
            "suggestionErrorPresent=${suggestionError != null}, isSaving=$isSaving, " +
            "deletingProjectDirectory=${if (deletingProjectDirectory == null) "null" else "<redacted>"}, " +
            "errorPresent=${error != null})"
}
