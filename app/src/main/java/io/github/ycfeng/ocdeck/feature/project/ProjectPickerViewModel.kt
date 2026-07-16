package io.github.ycfeng.ocdeck.feature.project

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.project.RecentProjectRecorder
import io.github.ycfeng.ocdeck.data.project.RecentProjectRepository
import io.github.ycfeng.ocdeck.domain.model.OpenCodeDirectorySuggestion
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProjectPickerViewModel(
    private val serverId: String,
    private val suggestionLoader: ProjectDirectorySuggestionLoader,
    private val recentProjectRepository: RecentProjectRepository,
    private val recentProjectRecorder: RecentProjectRecorder,
    private val pathNormalizer: PathNormalizer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProjectPickerUiState())
    val uiState: StateFlow<ProjectPickerUiState> = _uiState.asStateFlow()
    private var suggestionJob: Job? = null

    init {
        viewModelScope.launch {
            recentProjectRepository.observe(serverId).collect { projects ->
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
        val normalized = pathNormalizer.normalize(directory)
        while (true) {
            val state = _uiState.value
            if (state.isOpening) return
            if (_uiState.compareAndSet(state, state.copy(directory = normalized, isOpening = true, error = null))) break
        }
        suggestionJob?.cancel()
        try {
            onOpen(normalized)
            recentProjectRecorder.recordAdd(serverId, normalized)
        } catch (cancelled: CancellationException) {
            releaseOpeningLock()
            throw cancelled
        } catch (error: Error) {
            releaseOpeningLock()
            throw error
        } catch (_: Exception) {
            _uiState.update {
                it.copy(
                    isOpening = false,
                    error = UiText.Resource(R.string.project_error_open_navigation_failed),
                )
            }
        }
    }

    fun onPickerVisible() {
        releaseOpeningLock()
    }

    fun deleteRecentProject(project: ProjectRef) {
        val normalized = pathNormalizer.normalize(project.normalizedDirectory)
        viewModelScope.launch {
            _uiState.update { it.copy(deletingProjectDirectory = normalized, error = null) }
            try {
                recentProjectRepository.remove(serverId, normalized)
                _uiState.update { it.copy(deletingProjectDirectory = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Error) {
                throw error
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        deletingProjectDirectory = null,
                        error = UiText.Resource(R.string.project_error_delete_recent_failed),
                    )
                }
            }
        }
    }

    private fun releaseOpeningLock() {
        _uiState.update { state ->
            if (state.isOpening) state.copy(isOpening = false) else state
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
            suggestionLoader.load(
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

fun interface ProjectDirectorySuggestionLoader {
    suspend fun load(
        serverId: String,
        input: String,
        searchRoot: String?,
        limit: Int,
    ): Result<List<OpenCodeDirectorySuggestion>>
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
    val isOpening: Boolean = false,
    val deletingProjectDirectory: String? = null,
    val error: UiText? = null,
) {
    override fun toString(): String =
        "ProjectPickerUiState(directory=<redacted>, recentProjectCount=${recentProjects.size}, " +
            "suggestionCount=${suggestions.size}, isSuggesting=$isSuggesting, " +
            "suggestionErrorPresent=${suggestionError != null}, isOpening=$isOpening, " +
            "deletingProjectDirectory=${if (deletingProjectDirectory == null) "null" else "<redacted>"}, " +
            "errorPresent=${error != null})"
}
