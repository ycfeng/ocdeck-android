package io.github.ycfeng.ocdeck.feature.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.domain.model.OpenCodeDirectorySuggestion
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.ui.component.OpenCodeCard
import io.github.ycfeng.ocdeck.ui.component.OpenCodeConfirmDialog
import io.github.ycfeng.ocdeck.ui.component.OpenCodeHeaderIconButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodePlainTextField
import io.github.ycfeng.ocdeck.ui.component.OpenCodePrimaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSectionLabel
import io.github.ycfeng.ocdeck.ui.component.OpenCodeTopBar
import io.github.ycfeng.ocdeck.ui.component.calculateVerticalReorderAutoScroll
import io.github.ycfeng.ocdeck.ui.component.draggedOffsetAfterVerticalMove
import io.github.ycfeng.ocdeck.ui.component.findVerticalReorderTarget
import io.github.ycfeng.ocdeck.ui.component.findVisibleReorderItem
import io.github.ycfeng.ocdeck.ui.component.moveItem
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.asString

@Composable
fun ProjectPickerScreen(
    viewModel: ProjectPickerViewModel,
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProject: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val recentProjectListState = rememberLazyListState()
    val autoScrollThresholdPx = with(density) { ProjectDragAutoScrollThreshold.toPx() }
    val autoScrollStepPx = with(density) { ProjectDragAutoScrollStep.toPx() }
    val currentRecentProjects by rememberUpdatedState(state.recentProjects)
    var pendingDeleteProject by remember { mutableStateOf<ProjectRef?>(null) }
    var displayedProjects by remember { mutableStateOf(state.recentProjects) }
    var dragState by remember { mutableStateOf<ProjectDragState?>(null) }
    var autoScrollDelta by remember { mutableFloatStateOf(0f) }
    var directoryFieldValue by remember {
        mutableStateOf(TextFieldValue(state.directory, selection = TextRange(state.directory.length)))
    }

    LaunchedEffect(state.directory) {
        if (directoryFieldValue.text != state.directory) {
            directoryFieldValue = TextFieldValue(
                text = state.directory,
                selection = TextRange(state.directory.length),
            )
        }
    }

    LaunchedEffect(state.recentProjects) {
        if (dragState == null) displayedProjects = state.recentProjects
    }

    fun moveDraggedProjectToVisibleTarget() {
        val activeDragState = dragState ?: return
        val draggedItem = recentProjectListState.findVisibleReorderItem(activeDragState.directory) ?: return
        val draggedTop = draggedItem.offset.toFloat() + activeDragState.offsetY
        val draggedBottom = draggedTop + draggedItem.size.toFloat()
        val targetItem = recentProjectListState.findVerticalReorderTarget(
            draggedKey = activeDragState.directory,
            draggedTop = draggedTop,
            draggedBottom = draggedBottom,
            reorderableKeys = displayedProjects.mapTo(mutableSetOf<Any>()) { it.normalizedDirectory },
        ) ?: return
        val fromIndex = displayedProjects.indexOfFirst {
            it.normalizedDirectory == activeDragState.directory
        }
        val toIndex = displayedProjects.indexOfFirst {
            it.normalizedDirectory == targetItem.key
        }
        if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return
        val newDraggedOffset = targetItem.draggedOffsetAfterVerticalMove(
            draggedItem = draggedItem,
            movingDown = fromIndex < toIndex,
        )
        displayedProjects = displayedProjects.moveItem(fromIndex, toIndex)
        dragState = activeDragState.copy(
            offsetY = activeDragState.offsetY + draggedItem.offset.toFloat() - newDraggedOffset,
        )
    }

    fun updateAutoScrollDelta() {
        val activeDragState = dragState
        val draggedItem = activeDragState?.let {
            recentProjectListState.findVisibleReorderItem(it.directory)
        }
        autoScrollDelta = if (activeDragState == null || draggedItem == null) {
            0f
        } else {
            val draggedTop = draggedItem.offset.toFloat() + activeDragState.offsetY
            recentProjectListState.calculateVerticalReorderAutoScroll(
                draggedTop = draggedTop,
                draggedBottom = draggedTop + draggedItem.size.toFloat(),
                thresholdPx = autoScrollThresholdPx,
                stepPx = autoScrollStepPx,
            )
        }
    }

    LaunchedEffect(dragState?.directory, autoScrollDelta) {
        while (dragState != null && autoScrollDelta != 0f) {
            withFrameNanos { }
            val consumed = recentProjectListState.scrollBy(autoScrollDelta)
            if (consumed == 0f) {
                autoScrollDelta = 0f
                break
            }
            dragState = dragState?.let { it.copy(offsetY = it.offsetY + consumed) }
            moveDraggedProjectToVisibleTarget()
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onPickerVisible()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.onPickerVisible()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    pendingDeleteProject?.let { project ->
        OpenCodeConfirmDialog(
            title = stringResource(R.string.project_delete_recent_title),
            message = stringResource(R.string.project_delete_recent_message, project.displayName),
            confirmText = stringResource(R.string.project_delete_recent_confirm),
            isConfirming = state.deletingProjectDirectory == project.normalizedDirectory,
            onDismiss = { pendingDeleteProject = null },
            onConfirm = {
                viewModel.deleteRecentProject(project)
                pendingDeleteProject = null
            },
        )
    }

    Scaffold(
        containerColor = OpenCodePalette.Canvas,
        topBar = {
            OpenCodeTopBar(
                navigationLabel = stringResource(R.string.servers_title),
                navigationIconRes = R.drawable.ic_opencode_status,
                navigationContentDescription = stringResource(R.string.a11y_server),
                onNavigationClick = onOpenServers,
                title = stringResource(R.string.project_open_title),
                actions = {
                    OpenCodeHeaderIconButton(
                        iconRes = R.drawable.ic_opencode_settings_gear,
                        contentDescription = stringResource(R.string.a11y_settings),
                        onClick = onOpenSettings,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 17.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.project_choose_title), style = MaterialTheme.typography.headlineMedium)
            OpenCodeSectionLabel(stringResource(R.string.project_path_description))
            OpenCodePlainTextField(
                modifier = Modifier.fillMaxWidth(),
                value = directoryFieldValue,
                onValueChange = { value ->
                    val textChanged = value.text != directoryFieldValue.text
                    directoryFieldValue = value
                    if (textChanged) viewModel.onDirectoryChanged(value.text)
                },
                placeholder = stringResource(R.string.project_path_placeholder),
                contentAlignment = Alignment.CenterStart,
                singleLine = true,
            )
            ProjectDirectorySuggestions(
                suggestions = state.suggestions,
                isLoading = state.isSuggesting,
                error = state.suggestionError,
                onSuggestionClick = { suggestion ->
                    val completedDirectory = viewModel.completeDirectoryFromSuggestion(suggestion.directory)
                    directoryFieldValue = TextFieldValue(
                        text = completedDirectory,
                        selection = TextRange(completedDirectory.length),
                    )
                },
            )
            state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            OpenCodePrimaryButton(
                text = if (state.isOpening) stringResource(R.string.project_opening) else stringResource(R.string.project_open_title),
                enabled = !state.isOpening && state.directory.isNotBlank(),
                onClick = { viewModel.openProject(state.directory, onOpenProject) },
                modifier = Modifier.fillMaxWidth(),
            )
            OpenCodeSectionLabel(stringResource(R.string.project_recent_section))
            val moveProjectUpLabel = stringResource(R.string.a11y_move_project_up)
            val moveProjectDownLabel = stringResource(R.string.a11y_move_project_down)
            LazyColumn(
                state = recentProjectListState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(
                    items = displayedProjects,
                    key = { _, project -> project.normalizedDirectory },
                ) { index, project ->
                    val currentDragState = dragState
                    val isDragging = currentDragState?.directory == project.normalizedDirectory
                    val accessibilityReorderActions = buildList {
                        if (index > 0) {
                            add(CustomAccessibilityAction(moveProjectUpLabel) {
                                val currentIndex = displayedProjects.indexOfFirst {
                                    it.normalizedDirectory == project.normalizedDirectory
                                }
                                if (currentIndex <= 0) return@CustomAccessibilityAction false
                                val reorderedProjects = displayedProjects.moveItem(currentIndex, currentIndex - 1)
                                displayedProjects = reorderedProjects
                                viewModel.reorderRecentProjects(reorderedProjects) {
                                    displayedProjects = currentRecentProjects
                                }
                                true
                            })
                        }
                        if (index < displayedProjects.lastIndex) {
                            add(CustomAccessibilityAction(moveProjectDownLabel) {
                                val currentIndex = displayedProjects.indexOfFirst {
                                    it.normalizedDirectory == project.normalizedDirectory
                                }
                                if (currentIndex !in 0 until displayedProjects.lastIndex) {
                                    return@CustomAccessibilityAction false
                                }
                                val reorderedProjects = displayedProjects.moveItem(currentIndex, currentIndex + 1)
                                displayedProjects = reorderedProjects
                                viewModel.reorderRecentProjects(reorderedProjects) {
                                    displayedProjects = currentRecentProjects
                                }
                                true
                            })
                        }
                    }
                    RecentProjectCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isDragging) Modifier else Modifier.animateItem())
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = currentDragState?.offsetY?.takeIf { isDragging } ?: 0f
                            },
                        project = project,
                        isDeleting = state.deletingProjectDirectory == project.normalizedDirectory,
                        isOpening = state.isOpening,
                        isDragging = isDragging,
                        accessibilityReorderActions = accessibilityReorderActions,
                        dragHandleModifier = if (displayedProjects.size > 1) {
                            Modifier.pointerInput(project.normalizedDirectory) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragState = ProjectDragState(project.normalizedDirectory)
                                        autoScrollDelta = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val activeDragState = dragState
                                        val draggedItem = activeDragState?.let {
                                            recentProjectListState.findVisibleReorderItem(it.directory)
                                        }
                                        if (activeDragState != null && draggedItem != null) {
                                            dragState = activeDragState.copy(
                                                offsetY = activeDragState.offsetY + dragAmount.y,
                                            )
                                            moveDraggedProjectToVisibleTarget()
                                            updateAutoScrollDelta()
                                        }
                                    },
                                    onDragEnd = {
                                        dragState = null
                                        autoScrollDelta = 0f
                                        if (displayedProjects.map { it.normalizedDirectory } !=
                                            currentRecentProjects.map { it.normalizedDirectory }
                                        ) {
                                            viewModel.reorderRecentProjects(displayedProjects) {
                                                displayedProjects = currentRecentProjects
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        dragState = null
                                        autoScrollDelta = 0f
                                        displayedProjects = currentRecentProjects
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                        onOpen = { viewModel.openProject(project.normalizedDirectory, onOpenProject) },
                        onDelete = { pendingDeleteProject = project },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentProjectCard(
    modifier: Modifier = Modifier,
    project: ProjectRef,
    isDeleting: Boolean,
    isOpening: Boolean,
    isDragging: Boolean,
    accessibilityReorderActions: List<CustomAccessibilityAction>,
    dragHandleModifier: Modifier,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    OpenCodeCard(
        modifier = modifier,
        color = if (isDragging) OpenCodePalette.PanelMuted else OpenCodePalette.Panel,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) { customActions = accessibilityReorderActions }
                    .clickable(enabled = !isOpening && !isDeleting, onClick = onOpen)
                    .then(dragHandleModifier)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Text(project.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    project.normalizedDirectory,
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenCodePalette.MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                enabled = !isDeleting,
                onClick = onDelete,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_opencode_delete),
                    contentDescription = stringResource(R.string.a11y_delete_recent_project),
                    tint = if (isDeleting) OpenCodePalette.FaintText else OpenCodePalette.Danger,
                )
            }
        }
    }
}

private data class ProjectDragState(
    val directory: String,
    val offsetY: Float = 0f,
) {
    override fun toString(): String = "ProjectDragState(directory=<redacted>, offsetY=$offsetY)"
}

private val ProjectDragAutoScrollThreshold = 56.dp
private val ProjectDragAutoScrollStep = 24.dp

@Composable
private fun ProjectDirectorySuggestions(
    suggestions: List<OpenCodeDirectorySuggestion>,
    isLoading: Boolean,
    error: UiText?,
    onSuggestionClick: (OpenCodeDirectorySuggestion) -> Unit,
) {
    when {
        suggestions.isNotEmpty() -> {
            OpenCodeSectionLabel(stringResource(R.string.project_candidates_section))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion ->
                    OpenCodeCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onSuggestionClick(suggestion) },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(suggestion.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                suggestion.directory,
                                style = MaterialTheme.typography.bodySmall,
                                color = OpenCodePalette.MutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        isLoading -> OpenCodeSectionLabel(stringResource(R.string.project_candidates_loading))
        error != null -> OpenCodeSectionLabel(error.asString())
    }
}
