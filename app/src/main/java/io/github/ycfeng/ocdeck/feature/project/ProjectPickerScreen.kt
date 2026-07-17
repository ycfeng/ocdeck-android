package io.github.ycfeng.ocdeck.feature.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    var pendingDeleteProject by remember { mutableStateOf<ProjectRef?>(null) }
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.recentProjects, key = { it.normalizedDirectory }) { project ->
                    RecentProjectCard(
                        project = project,
                        isDeleting = state.deletingProjectDirectory == project.normalizedDirectory,
                        isOpening = state.isOpening,
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
    project: ProjectRef,
    isDeleting: Boolean,
    isOpening: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    OpenCodeCard(modifier = Modifier.fillMaxWidth()) {
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
                    .clickable(enabled = !isOpening && !isDeleting, onClick = onOpen)
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
