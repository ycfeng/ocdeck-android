package io.github.ycfeng.ocdeck.feature.file

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.util.ProjectFilePathNormalizer
import io.github.ycfeng.ocdeck.data.opencode.OpenCodeRepository
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileContent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileEntry
import io.github.ycfeng.ocdeck.domain.model.OpenCodeFileType
import io.github.ycfeng.ocdeck.ui.text.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProjectFileBrowserViewModel(
    private val serverId: String,
    private val directory: String,
    private val repository: OpenCodeRepository,
    private val pathNormalizer: ProjectFilePathNormalizer,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ProjectFileBrowserUiState())
    val uiState: StateFlow<ProjectFileBrowserUiState> = mutableUiState.asStateFlow()

    private val backslashIsSeparator = pathNormalizer.usesWindowsSeparators(directory)
    private val directoryJobs = mutableMapOf<String, Job>()
    private val directoryRequestIds = mutableMapOf<String, Long>()
    private var searchJob: Job? = null
    private var contentJob: Job? = null
    private var treeGeneration = 0
    private var requestSequence = 0L
    private var searchRequestId = 0L
    private var contentRequestId = 0L

    fun onPanelOpened() {
        val root = mutableUiState.value.directories[""]
        if (root?.isLoaded != true && root?.isLoading != true) loadDirectory(path = "")
    }

    fun onPanelClosed() {
        treeGeneration += 1
        directoryJobs.values.forEach(Job::cancel)
        directoryJobs.clear()
        directoryRequestIds.clear()
        searchJob?.cancel()
        contentJob?.cancel()
        searchRequestId = nextRequestId()
        contentRequestId = nextRequestId()
        mutableUiState.update { state ->
            val retainedDirectories = state.directories
                .filterValues(ProjectFileDirectoryState::isLoaded)
                .mapValues { (_, directoryState) -> directoryState.copy(isLoading = false) }
            state.copy(
                directories = retainedDirectories,
                expandedDirectories = state.expandedDirectories.filterTo(mutableSetOf()) {
                    retainedDirectories[it]?.isLoaded == true
                },
                searchQuery = "",
                searchResults = emptyList(),
                isSearching = false,
                searchError = null,
                page = ProjectFileBrowserPage.Tree,
                selectedPath = null,
                content = null,
                isContentLoading = false,
                contentError = null,
            )
        }
    }

    fun toggleDirectory(entry: OpenCodeFileEntry) {
        if (entry.type != OpenCodeFileType.Directory) return
        val isExpanded = entry.path in mutableUiState.value.expandedDirectories
        mutableUiState.update { state ->
            state.copy(
                expandedDirectories = if (isExpanded) {
                    state.expandedDirectories - entry.path
                } else {
                    state.expandedDirectories + entry.path
                },
            )
        }
        if (!isExpanded) loadDirectory(entry.path)
    }

    fun retryDirectory(path: String) {
        loadDirectory(path = path, force = true)
    }

    fun refreshTree() {
        val query = mutableUiState.value.searchQuery
        if (query.isNotBlank()) {
            startSearch(query = query, debounce = false)
            return
        }

        treeGeneration += 1
        directoryJobs.values.forEach(Job::cancel)
        directoryJobs.clear()
        directoryRequestIds.clear()
        mutableUiState.update {
            it.copy(
                directories = emptyMap(),
                expandedDirectories = emptySet(),
            )
        }
        loadDirectory(path = "", force = true)
    }

    fun onSearchQueryChanged(query: String) {
        mutableUiState.update {
            it.copy(
                searchQuery = query,
                searchResults = emptyList(),
                isSearching = false,
                searchError = null,
            )
        }
        searchJob?.cancel()
        searchRequestId = nextRequestId()
        if (query.isNotBlank()) {
            startSearch(query = query, debounce = true)
        }
    }

    fun openFile(entry: OpenCodeFileEntry) {
        if (entry.type != OpenCodeFileType.File) return
        openFile(entry.path)
    }

    fun openFile(path: String) {
        val normalizedPath = runCatching {
            pathNormalizer.normalize(path, backslashIsSeparator = backslashIsSeparator)
        }.getOrNull() ?: return
        contentJob?.cancel()
        val requestId = nextRequestId()
        contentRequestId = requestId
        mutableUiState.update {
            it.copy(
                page = ProjectFileBrowserPage.Content,
                selectedPath = normalizedPath,
                content = null,
                isContentLoading = true,
                contentError = null,
            )
        }
        contentJob = viewModelScope.launch {
            repository.loadProjectFile(
                serverId = serverId,
                directory = directory,
                path = normalizedPath,
            ).onSuccess { content ->
                if (contentRequestId == requestId && mutableUiState.value.selectedPath == normalizedPath) {
                    mutableUiState.update {
                        it.copy(content = content, isContentLoading = false, contentError = null)
                    }
                }
            }.onFailure {
                if (contentRequestId == requestId && mutableUiState.value.selectedPath == normalizedPath) {
                    mutableUiState.update {
                        it.copy(
                            content = null,
                            isContentLoading = false,
                            contentError = UiText.Resource(R.string.file_browser_content_failed),
                        )
                    }
                }
            }
        }
    }

    fun refreshCurrentFile() {
        mutableUiState.value.selectedPath?.let(::openFile)
    }

    fun showTree() {
        contentJob?.cancel()
        contentRequestId = nextRequestId()
        mutableUiState.update {
            it.copy(
                page = ProjectFileBrowserPage.Tree,
                selectedPath = null,
                content = null,
                isContentLoading = false,
                contentError = null,
            )
        }
    }

    private fun loadDirectory(path: String, force: Boolean = false) {
        val normalizedPath = runCatching {
            pathNormalizer.normalize(
                path = path,
                allowEmpty = true,
                backslashIsSeparator = backslashIsSeparator,
            )
        }.getOrNull() ?: return
        val current = mutableUiState.value.directories[normalizedPath]
        if (!force && (current?.isLoading == true || current?.isLoaded == true)) return

        directoryJobs.remove(normalizedPath)?.cancel()
        val requestGeneration = treeGeneration
        val requestId = nextRequestId()
        directoryRequestIds[normalizedPath] = requestId
        mutableUiState.update { state ->
            state.copy(
                directories = state.directories + (
                    normalizedPath to (current ?: ProjectFileDirectoryState()).copy(
                        isLoading = true,
                        error = null,
                    )
                ),
            )
        }
        directoryJobs[normalizedPath] = viewModelScope.launch {
            repository.listProjectFiles(
                serverId = serverId,
                directory = directory,
                path = normalizedPath,
            ).onSuccess { entries ->
                if (treeGeneration == requestGeneration && directoryRequestIds[normalizedPath] == requestId) {
                    mutableUiState.update { state ->
                        state.copy(
                            directories = state.directories + (
                                normalizedPath to ProjectFileDirectoryState(
                                    entries = entries,
                                    isLoaded = true,
                                )
                            ),
                        )
                    }
                }
            }.onFailure {
                if (treeGeneration == requestGeneration && directoryRequestIds[normalizedPath] == requestId) {
                    mutableUiState.update { state ->
                        state.copy(
                            directories = state.directories + (
                                normalizedPath to ProjectFileDirectoryState(
                                    isLoaded = true,
                                    error = UiText.Resource(R.string.file_browser_directory_failed),
                                )
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun startSearch(query: String, debounce: Boolean) {
        searchJob?.cancel()
        val requestId = nextRequestId()
        searchRequestId = requestId
        mutableUiState.update {
            it.copy(
                searchResults = emptyList(),
                isSearching = true,
                searchError = null,
            )
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(SEARCH_DEBOUNCE_MILLIS)
            repository.searchProjectFiles(
                serverId = serverId,
                directory = directory,
                query = query,
            ).onSuccess { files ->
                if (
                    searchRequestId == requestId &&
                    mutableUiState.value.searchQuery == query
                ) {
                    mutableUiState.update { it.copy(searchResults = files, isSearching = false) }
                }
            }.onFailure {
                if (
                    searchRequestId == requestId &&
                    mutableUiState.value.searchQuery == query
                ) {
                    mutableUiState.update {
                        it.copy(
                            searchResults = emptyList(),
                            isSearching = false,
                            searchError = UiText.Resource(R.string.file_browser_search_failed),
                        )
                    }
                }
            }
        }
    }

    private fun nextRequestId(): Long = ++requestSequence

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}

enum class ProjectFileBrowserPage {
    Tree,
    Content,
}

data class ProjectFileDirectoryState(
    val entries: List<OpenCodeFileEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val error: UiText? = null,
) {
    override fun toString(): String =
        "ProjectFileDirectoryState(entryCount=${entries.size}, isLoading=$isLoading, " +
            "isLoaded=$isLoaded, errorPresent=${error != null})"
}

data class ProjectFileBrowserUiState(
    val directories: Map<String, ProjectFileDirectoryState> = emptyMap(),
    val expandedDirectories: Set<String> = emptySet(),
    val searchQuery: String = "",
    val searchResults: List<OpenCodeFileEntry> = emptyList(),
    val isSearching: Boolean = false,
    val searchError: UiText? = null,
    val page: ProjectFileBrowserPage = ProjectFileBrowserPage.Tree,
    val selectedPath: String? = null,
    val content: OpenCodeFileContent? = null,
    val isContentLoading: Boolean = false,
    val contentError: UiText? = null,
) {
    override fun toString(): String =
        "ProjectFileBrowserUiState(directoryCount=${directories.size}, " +
            "expandedDirectoryCount=${expandedDirectories.size}, searchQueryLength=${searchQuery.length}, " +
            "searchResultCount=${searchResults.size}, isSearching=$isSearching, " +
            "searchErrorPresent=${searchError != null}, page=$page, " +
            "selectedPath=${if (selectedPath == null) "null" else "<redacted>"}, " +
            "contentPresent=${content != null}, isContentLoading=$isContentLoading, " +
            "contentErrorPresent=${contentError != null})"
}

sealed interface ProjectFileTreeRow {
    val depth: Int

    data class Entry(
        val file: OpenCodeFileEntry,
        override val depth: Int,
    ) : ProjectFileTreeRow {
        override fun toString(): String = "ProjectFileTreeRow.Entry(file=$file, depth=$depth)"
    }

    data class Loading(
        val parentPath: String,
        override val depth: Int,
    ) : ProjectFileTreeRow {
        override fun toString(): String =
            "ProjectFileTreeRow.Loading(parentPath=<redacted>, depth=$depth)"
    }

    data class Error(
        val parentPath: String,
        override val depth: Int,
        val message: UiText,
    ) : ProjectFileTreeRow {
        override fun toString(): String =
            "ProjectFileTreeRow.Error(parentPath=<redacted>, depth=$depth, messagePresent=true)"
    }

    data class Empty(
        val parentPath: String,
        override val depth: Int,
    ) : ProjectFileTreeRow {
        override fun toString(): String =
            "ProjectFileTreeRow.Empty(parentPath=<redacted>, depth=$depth)"
    }
}

internal fun flattenProjectFileTree(
    directories: Map<String, ProjectFileDirectoryState>,
    expandedDirectories: Set<String>,
    maxDepth: Int = 128,
): List<ProjectFileTreeRow> {
    val rows = mutableListOf<ProjectFileTreeRow>()
    val emittedPaths = mutableSetOf<String>()

    fun appendDirectory(path: String, depth: Int, chain: Set<String>) {
        val state = directories[path]
        when {
            state == null || state.isLoading -> rows += ProjectFileTreeRow.Loading(path, depth)
            state.error != null -> rows += ProjectFileTreeRow.Error(path, depth, state.error)
            state.isLoaded && state.entries.isEmpty() -> rows += ProjectFileTreeRow.Empty(path, depth)
            else -> state.entries.forEach { entry ->
                if (!emittedPaths.add(entry.path)) return@forEach
                rows += ProjectFileTreeRow.Entry(entry, depth)
                if (
                    entry.type == OpenCodeFileType.Directory &&
                    entry.path in expandedDirectories &&
                    depth < maxDepth &&
                    entry.path !in chain
                ) {
                    appendDirectory(entry.path, depth + 1, chain + entry.path)
                }
            }
        }
    }

    appendDirectory(path = "", depth = 0, chain = emptySet())
    return rows
}
