package io.github.ycfeng.ocdeck.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.app.AppContainer
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.domain.prompt.OpenCodePromptSender
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContextLimits
import io.github.ycfeng.ocdeck.feature.file.ProjectFileBrowserPage
import io.github.ycfeng.ocdeck.feature.file.ProjectFileBrowserViewModel
import io.github.ycfeng.ocdeck.feature.file.ProjectFilePanel
import io.github.ycfeng.ocdeck.feature.file.ProjectFilePanelMode
import io.github.ycfeng.ocdeck.feature.sessionlist.SessionListDrawerContent
import io.github.ycfeng.ocdeck.ui.component.OpenCodeConfirmDialog
import io.github.ycfeng.ocdeck.ui.component.OpenCodeDialog
import io.github.ycfeng.ocdeck.ui.component.OpenCodeDialogButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodePlainTextField
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.asString
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class ActiveProjectDrawerRoute(
    val serverId: String,
    val directory: String,
    val sessionId: String?,
) {
    override fun toString(): String =
        "ActiveProjectDrawerRoute(serverId=<redacted>, directory=<redacted>, " +
            "sessionId=${if (sessionId == null) "null" else "<redacted>"})"
}

private data class ProjectFilePickerRequest(
    val owner: ActiveProjectDrawerRoute,
    val selectedPaths: Set<String>,
    val onConfirm: (List<String>) -> Unit,
) {
    override fun toString(): String =
        "ProjectFilePickerRequest(owner=$owner, selectedPathCount=${selectedPaths.size}, onConfirm=<redacted>)"
}

@Composable
internal fun ProjectDrawerHost(
    activeProject: ActiveProjectDrawerRoute?,
    appContainer: AppContainer,
    onOpenProject: (serverId: String, directory: String) -> Unit,
    onOpenProjectPicker: (serverId: String) -> Unit,
    onOpenSettings: (serverId: String) -> Unit,
    onOpenSession: (serverId: String, directory: String, sessionId: String) -> Unit,
    onCloseProject: (serverId: String, directory: String) -> Unit,
    content: @Composable (
        onOpenDrawer: () -> Unit,
        onOpenFiles: () -> Unit,
        onPickProjectFiles: (List<String>, (List<String>) -> Unit) -> Unit,
    ) -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var isDrawerNavigationCommitted by remember { mutableStateOf(false) }
    val projectRouteKey = activeProject?.let { route ->
        "${route.serverId}:${appContainer.pathNormalizer.normalize(route.directory)}"
    }
    val routeKey = activeProject?.let { "${it.serverId}:${it.directory}:${it.sessionId.orEmpty()}" }
    var archiveSession by remember(routeKey) { mutableStateOf<OpenCodeSession?>(null) }
    var showEditProjectName by remember(routeKey) { mutableStateOf(false) }
    var editProjectName by remember(routeKey) { mutableStateOf("") }
    var confirmCloseProject by remember(routeKey) { mutableStateOf(false) }
    var projectNameError by remember(routeKey) { mutableStateOf<UiText?>(null) }
    var isUpdatingProjectName by remember(routeKey) { mutableStateOf(false) }
    var isArchivingSession by remember(routeKey) { mutableStateOf(false) }
    var archiveError by remember(routeKey) { mutableStateOf<UiText?>(null) }
    var projectReorderError by remember(projectRouteKey) { mutableStateOf<UiText?>(null) }
    var isProjectRailDragging by remember(projectRouteKey) { mutableStateOf(false) }
    val projectReorderJobs = remember { mutableMapOf<String, Job>() }
    val fileProjectKey = projectRouteKey
    var isFilePanelOpen by remember(routeKey) { mutableStateOf(false) }
    var filePickerRequest by remember(routeKey) { mutableStateOf<ProjectFilePickerRequest?>(null) }
    val fileViewModelStore = remember(fileProjectKey) { ViewModelStore() }
    val fileViewModelStoreOwner = remember(fileViewModelStore) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = fileViewModelStore
        }
    }
    DisposableEffect(fileViewModelStore) {
        onDispose(fileViewModelStore::clear)
    }
    val fileViewModel = if (activeProject != null && fileProjectKey != null) {
        viewModel<ProjectFileBrowserViewModel>(
            viewModelStoreOwner = fileViewModelStoreOwner,
            key = "project-files-$fileProjectKey",
            factory = appContainer.viewModelProvider.projectFileBrowserFactory(
                serverId = activeProject.serverId,
                directory = activeProject.directory,
            ),
        )
    } else {
        null
    }
    val closeFilePanel = {
        fileViewModel?.onPanelClosed()
        isFilePanelOpen = false
        filePickerRequest = null
    }
    val commitDrawerNavigation: (() -> Unit) -> Unit = { navigate ->
        if (!isDrawerNavigationCommitted) {
            isDrawerNavigationCommitted = true
            navigate()
            scope.launch {
                try {
                    drawerState.close()
                } finally {
                    isDrawerNavigationCommitted = false
                }
            }
        }
    }

    DisposableEffect(routeKey, fileViewModel) {
        onDispose { fileViewModel?.onPanelClosed() }
    }
    LaunchedEffect(activeProject == null) {
        if (activeProject == null && drawerState.isOpen) {
            drawerState.snapTo(DrawerValue.Closed)
        }
        if (activeProject == null) {
            isFilePanelOpen = false
            filePickerRequest = null
            isProjectRailDragging = false
        }
    }

    BoxWithConstraints {
        val drawerWidth = maxWidth.coerceAtMost(ProjectDrawerMaxWidth)
        val filePanelWidth = maxWidth.coerceAtMost(ProjectFilePanelMaxWidth)

        Box(modifier = Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                modifier = if (isFilePanelOpen) Modifier.clearAndSetSemantics { } else Modifier,
                drawerState = drawerState,
                gesturesEnabled = activeProject != null && !isFilePanelOpen && !isProjectRailDragging,
                drawerContent = {
                val route = activeProject
                if (route == null) {
                    ModalDrawerSheet(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(drawerWidth),
                        drawerContainerColor = OpenCodePalette.Panel,
                    ) {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                } else {
                    val normalizedDirectory = remember(route.serverId, route.directory) {
                        appContainer.pathNormalizer.normalize(route.directory)
                    }
                    val projectState by appContainer.openCodeStore
                        .observeProject(route.serverId, normalizedDirectory)
                        .collectAsStateWithLifecycle(
                            OpenCodeProjectState(
                                serverId = route.serverId,
                                normalizedDirectory = normalizedDirectory,
                            ),
                        )
                    val projectDisplayName = projectState.project?.displayName ?: normalizedDirectory.displayName()
                    val recentProjectsFlow = remember(route.serverId) {
                        appContainer.recentProjectRepository.observe(route.serverId)
                    }
                    val recentProjects by recentProjectsFlow.collectAsStateWithLifecycle(emptyList())
                    val currentProject = projectState.project?.copy(
                        normalizedDirectory = normalizedDirectory,
                        displayName = projectDisplayName,
                    ) ?: ProjectRef(
                        normalizedDirectory = normalizedDirectory,
                        projectId = null,
                        displayName = projectDisplayName,
                        vcs = null,
                        icon = null,
                    )
                    val drawerProjects = remember(currentProject, recentProjects) {
                        mergeProjectDrawerProjects(
                            currentProject = currentProject,
                            recentProjects = recentProjects,
                            pathNormalizer = appContainer.pathNormalizer,
                        )
                    }
                    val projectToEnsure = remember(currentProject, recentProjects) {
                        projectToEnsureForDrawer(
                            currentProject = currentProject,
                            recentProjects = recentProjects,
                            pathNormalizer = appContainer.pathNormalizer,
                        )
                    }
                    ModalDrawerSheet(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(drawerWidth),
                        drawerContainerColor = OpenCodePalette.Panel,
                    ) {
                        SessionListDrawerContent(
                            state = projectState,
                            activeSessionId = route.sessionId,
                            projects = drawerProjects,
                            projectReorderError = projectReorderError,
                            onReorderProjects = { projects, onFailure ->
                            projectReorderError = null
                            projectReorderJobs[route.serverId]?.cancel()
                            val reorderJob = scope.launch {
                                try {
                                    appContainer.recentProjectRepository.reorder(
                                            serverId = route.serverId,
                                            projects = projects,
                                            projectToEnsure = projectToEnsure,
                                        )
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Error) {
                                        throw error
                                    } catch (_: Exception) {
                                        onFailure()
                                    projectReorderError = UiText.Resource(R.string.project_error_reorder_failed)
                                }
                            }
                            projectReorderJobs[route.serverId] = reorderJob
                            reorderJob.invokeOnCompletion {
                                if (projectReorderJobs[route.serverId] === reorderJob) {
                                    projectReorderJobs.remove(route.serverId)
                                }
                            }
                        },
                            onProjectDragStateChanged = { isDragging ->
                                isProjectRailDragging = isDragging
                            },
                            onSelectProject = { directory ->
                                commitDrawerNavigation {
                                    onOpenProject(route.serverId, directory)
                                }
                            },
                            onOpenProjectPicker = {
                                commitDrawerNavigation {
                                    onOpenProjectPicker(route.serverId)
                                }
                            },
                            onOpenSettings = {
                                commitDrawerNavigation {
                                    onOpenSettings(route.serverId)
                                }
                            },
                            onNewSession = {
                                commitDrawerNavigation {
                                    if (route.sessionId != OpenCodePromptSender.NEW_SESSION_ID) {
                                        onOpenSession(route.serverId, route.directory, OpenCodePromptSender.NEW_SESSION_ID)
                                    }
                                }
                            },
                            onOpenSession = { sessionId ->
                                commitDrawerNavigation {
                                    if (route.sessionId != sessionId) {
                                        onOpenSession(route.serverId, route.directory, sessionId)
                                    }
                                }
                            },
                            onLoadMoreSessions = {
                                appContainer.sessionListWindowCoordinator.loadMore(route.serverId, normalizedDirectory)
                            },
                            onRetrySessionListWindow = {
                                appContainer.sessionListWindowCoordinator.retry(route.serverId, normalizedDirectory)
                            },
                            onArchiveSession = { session ->
                                archiveError = null
                                archiveSession = session
                            },
                            onEditProject = {
                                projectNameError = null
                                editProjectName = projectDisplayName
                                showEditProjectName = true
                            },
                            onClearNotifications = {
                                appContainer.openCodeStore.markProjectNotificationsViewed(route.serverId, normalizedDirectory)
                            },
                            onCloseProject = { confirmCloseProject = true },
                        )
                    }

                    if (showEditProjectName) {
                        EditProjectNameDialog(
                            name = editProjectName,
                            isSaving = isUpdatingProjectName,
                            error = projectNameError,
                            onNameChange = {
                                editProjectName = it
                                projectNameError = null
                            },
                            onDismiss = {
                                showEditProjectName = false
                                projectNameError = null
                            },
                            onSave = {
                                val trimmedName = editProjectName.trim()
                                val projectId = projectState.project?.projectId
                                when {
                                    trimmedName.isBlank() -> projectNameError = UiText.Resource(R.string.project_name_empty_error)
                                    projectId.isNullOrBlank() -> projectNameError = UiText.Resource(R.string.project_name_missing_id_error)
                                    isUpdatingProjectName -> Unit
                                    else -> scope.launch {
                                        isUpdatingProjectName = true
                                        projectNameError = null
                                        appContainer.openCodeRepository.updateProjectName(
                                            serverId = route.serverId,
                                            directory = normalizedDirectory,
                                            projectId = projectId,
                                            name = trimmedName,
                                        ).onSuccess { project ->
                                            appContainer.openCodeStore.updateProject(route.serverId, normalizedDirectory, project)
                                            appContainer.recentProjectRecorder.recordUpsert(route.serverId, project)
                                            isUpdatingProjectName = false
                                            showEditProjectName = false
                                        }.onFailure { throwable ->
                                            isUpdatingProjectName = false
                                            projectNameError = throwable.toErrorUiText(R.string.project_name_save_failed)
                                        }
                                    }
                                }
                            },
                        )
                    }

                    if (confirmCloseProject) {
                        OpenCodeConfirmDialog(
                            title = stringResource(R.string.project_close_title),
                            message = stringResource(R.string.project_close_message, projectDisplayName),
                            confirmText = stringResource(R.string.project_close_confirm),
                            onDismiss = { confirmCloseProject = false },
                            onConfirm = {
                                confirmCloseProject = false
                                appContainer.openCodeEventClient.closeProjectNow(route.serverId, normalizedDirectory)
                                scope.launch { drawerState.close() }
                                onCloseProject(route.serverId, route.directory)
                            },
                        )
                    }

                    archiveSession?.let { session ->
                        OpenCodeConfirmDialog(
                            title = stringResource(R.string.session_archive_title),
                            message = stringResource(R.string.session_archive_message, session.title),
                            confirmText = stringResource(
                                if (archiveError == null) R.string.session_archive_confirm else R.string.action_retry,
                            ),
                            isConfirming = isArchivingSession,
                            errorMessage = archiveError?.asString(),
                            confirmingText = stringResource(R.string.action_archiving),
                            onDismiss = {
                                if (!isArchivingSession) {
                                    archiveSession = null
                                    archiveError = null
                                }
                            },
                            onConfirm = {
                                if (isArchivingSession) return@OpenCodeConfirmDialog
                                scope.launch {
                                    isArchivingSession = true
                                    archiveError = null
                                    appContainer.openCodeRepository.archiveSession(
                                        serverId = route.serverId,
                                        directory = normalizedDirectory,
                                        sessionId = session.id,
                                    ).onSuccess {
                                        appContainer.openCodeStore.removeSession(route.serverId, normalizedDirectory, session.id)
                                        archiveSession = null
                                        archiveError = null
                                        if (route.sessionId == session.id) {
                                            drawerState.close()
                                            onOpenSession(route.serverId, route.directory, OpenCodePromptSender.NEW_SESSION_ID)
                                        }
                                    }.onFailure { throwable ->
                                        archiveError = throwable.toErrorUiText(R.string.session_archive_failed)
                                    }
                                    isArchivingSession = false
                                }
                            },
                        )
                    }
                }
                },
            ) {
                content(
                    {
                        if (activeProject != null) {
                            scope.launch { drawerState.open() }
                        }
                    },
                    {
                        if (activeProject != null) {
                            scope.launch {
                                if (drawerState.isOpen) drawerState.close()
                                filePickerRequest = null
                                fileViewModel?.onPanelOpened()
                                isFilePanelOpen = true
                            }
                        }
                    },
                    { initialPaths, onConfirm ->
                        val route = activeProject
                        if (route != null) {
                            scope.launch {
                                if (drawerState.isOpen) drawerState.close()
                                filePickerRequest = ProjectFilePickerRequest(
                                    owner = route,
                                    selectedPaths = initialPaths
                                        .take(ProjectFileContextLimits.MAX_CONTEXT_COUNT)
                                        .toSet(),
                                    onConfirm = onConfirm,
                                )
                                fileViewModel?.onPanelOpened()
                                isFilePanelOpen = true
                            }
                        }
                    },
                )
            }

            if (isFilePanelOpen && activeProject != null && fileViewModel != null) {
                ProjectFilePanelBackHandler(
                    currentPage = { fileViewModel.uiState.value.page },
                    onShowTree = fileViewModel::showTree,
                    onClose = closeFilePanel,
                )
                val closeDescription = stringResource(R.string.a11y_close)
                val pickerRequest = filePickerRequest
                val paneDescription = stringResource(
                    if (pickerRequest == null) R.string.file_browser_title else R.string.project_file_picker_title,
                )
                val fileState by fileViewModel.uiState.collectAsStateWithLifecycle()
                Row(
                    modifier = Modifier
                        .fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.Black.copy(alpha = 0.32f))
                            .semantics {
                                contentDescription = closeDescription
                                role = Role.Button
                            }
                            .clickable(onClick = closeFilePanel),
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(filePanelWidth)
                            .semantics { paneTitle = paneDescription },
                        color = OpenCodePalette.Panel,
                        border = BorderStroke(1.dp, OpenCodePalette.Border),
                        shadowElevation = 8.dp,
                    ) {
                        ProjectFilePanel(
                            state = fileState,
                            projectDirectory = activeProject.directory,
                            onSearchQueryChanged = fileViewModel::onSearchQueryChanged,
                            onToggleDirectory = fileViewModel::toggleDirectory,
                            onRetryDirectory = fileViewModel::retryDirectory,
                            onOpenFile = fileViewModel::openFile,
                            onShowTree = fileViewModel::showTree,
                            onRefreshTree = fileViewModel::refreshTree,
                            onRefreshFile = fileViewModel::refreshCurrentFile,
                            onClose = closeFilePanel,
                            mode = if (pickerRequest == null) {
                                ProjectFilePanelMode.Browse
                            } else {
                                ProjectFilePanelMode.Pick
                            },
                            selectedPaths = pickerRequest?.selectedPaths.orEmpty(),
                            selectionLimit = ProjectFileContextLimits.MAX_CONTEXT_COUNT,
                            onToggleFileSelection = { file ->
                                val request = filePickerRequest
                                if (request != null) {
                                    val selectedPaths = request.selectedPaths
                                    val nextPaths = when {
                                        file.path in selectedPaths -> selectedPaths - file.path
                                        selectedPaths.size < ProjectFileContextLimits.MAX_CONTEXT_COUNT -> {
                                            selectedPaths + file.path
                                        }
                                        else -> selectedPaths
                                    }
                                    filePickerRequest = request.copy(selectedPaths = nextPaths)
                                }
                            },
                            onConfirmSelection = {
                                val request = filePickerRequest
                                if (request != null && request.owner == activeProject) {
                                    val selectedPaths = request.selectedPaths.toList()
                                    closeFilePanel()
                                    request.onConfirm(selectedPaths)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

}

@Composable
internal fun ProjectFilePanelBackHandler(
    currentPage: () -> ProjectFileBrowserPage?,
    onShowTree: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler {
        if (currentPage() == ProjectFileBrowserPage.Content) {
            onShowTree()
        } else {
            onClose()
        }
    }
}

@Composable
private fun EditProjectNameDialog(
    name: String,
    isSaving: Boolean,
    error: UiText?,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    OpenCodeDialog(
        title = stringResource(R.string.project_edit_title),
        isBusy = isSaving,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.project_name_label),
                style = MaterialTheme.typography.labelMedium,
                color = OpenCodePalette.FaintText,
            )
            OpenCodePlainTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.project_name_placeholder),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        if (error != null) {
            Text(
                text = error.asString(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OpenCodeDialogButton(
                text = stringResource(R.string.action_cancel),
                enabled = !isSaving,
                onClick = onDismiss,
            )
            OpenCodeDialogButton(
                text = if (isSaving) stringResource(R.string.action_saving) else stringResource(R.string.action_save),
                primary = true,
                enabled = !isSaving && name.isNotBlank(),
                onClick = onSave,
            )
        }
    }
}

private fun String.displayName(): String = trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .ifBlank { this }

private val ProjectDrawerMaxWidth = 390.dp
private val ProjectFilePanelMaxWidth = 420.dp
