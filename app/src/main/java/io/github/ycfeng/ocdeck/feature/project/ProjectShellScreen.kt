package io.github.ycfeng.ocdeck.feature.project

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.store.NotificationDotKind
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.core.store.SseConnectionStatus
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.feature.composer.AgentPickerPopup
import io.github.ycfeng.ocdeck.feature.composer.ComposerParameterButton
import io.github.ycfeng.ocdeck.feature.composer.ModelPickerPopup
import io.github.ycfeng.ocdeck.feature.composer.VariantPickerPopup
import io.github.ycfeng.ocdeck.feature.composer.agentParameterLabel
import io.github.ycfeng.ocdeck.feature.composer.composerAgentOptions
import io.github.ycfeng.ocdeck.feature.composer.rememberPromptPlaceholderText
import io.github.ycfeng.ocdeck.feature.composer.toVariantDisplayLabel
import io.github.ycfeng.ocdeck.feature.sessionlist.SessionListWindowFooter
import io.github.ycfeng.ocdeck.ui.component.OpenCodeCard
import io.github.ycfeng.ocdeck.ui.component.OpenCodeHeaderIconButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeNotificationDot
import io.github.ycfeng.ocdeck.ui.component.OpenCodePill
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSectionLabel
import io.github.ycfeng.ocdeck.ui.component.OpenCodeServerStatusButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSessionRunningIndicator
import io.github.ycfeng.ocdeck.ui.component.OpenCodeTopBar
import io.github.ycfeng.ocdeck.ui.component.isOpenCodeWorkingSessionStatus
import io.github.ycfeng.ocdeck.ui.component.notificationStateDescription
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import io.github.ycfeng.ocdeck.ui.text.asString

@Composable
fun ProjectShellScreen(
    viewModel: ProjectShellViewModel,
    onOpenDrawer: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenServers: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenNewSession: (String?, PromptModelSelection?) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val state = uiState.project
    val rootSessions = remember(state.sessions) { state.sessions.filter { it.parentId.isNullOrBlank() } }
    val visibleSessions = rootSessions.take(state.sessionListWindow.visibleRootLimit)

    Scaffold(
        containerColor = OpenCodePalette.Canvas,
        topBar = {
            OpenCodeTopBar(
                navigationLabel = stringResource(R.string.a11y_menu),
                navigationIconRes = R.drawable.ic_opencode_menu,
                navigationContentDescription = stringResource(R.string.a11y_toggle_menu),
                onNavigationClick = onOpenDrawer,
                actions = {
                    OpenCodeHeaderIconButton(
                        iconRes = R.drawable.ic_opencode_file,
                        contentDescription = stringResource(R.string.a11y_open_project_files),
                        onClick = onOpenFiles,
                    )
                    OpenCodeServerStatusButton(
                        project = state,
                        serverConfig = uiState.serverConfig,
                        serverVersion = uiState.serverVersion,
                        mcpActionName = uiState.mcpActionName,
                        onOpenServers = onOpenServers,
                        onMcpToggle = viewModel::toggleMcp,
                    )
                },
            )
        },
        bottomBar = {
            ProjectHomeComposerPreview(
                enabled = !state.isLoading,
                agents = state.agents,
                models = state.models,
                selectedAgent = uiState.selectedAgent,
                selectedModel = uiState.selectedModel,
                selectedModelLabel = uiState.selectedModelLabel,
                selectedVariant = uiState.selectedVariant,
                showVariant = uiState.showVariant,
                onOpenNewSession = { onOpenNewSession(uiState.selectedAgent, uiState.selectedModel) },
                onAgentSelected = viewModel::selectAgent,
                onModelSelected = viewModel::selectModel,
                onVariantSelected = viewModel::selectVariant,
                onOpenProviders = onOpenProviders,
                onOpenModels = onOpenModels,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 17.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ProjectHero(state = state, connectionLabel = state.connectionStatus.label(), sessionCount = rootSessions.size)
            }
            state.error?.let { error ->
                item { Text(error.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            item { OpenCodeSectionLabel(stringResource(R.string.project_recent_sessions)) }
            items(visibleSessions, key = { it.id }) { session ->
                val status = state.statuses[session.id]?.type ?: "idle"
                val isWorking = status.isOpenCodeWorkingSessionStatus()
                val notificationDotKind = state.sessionNotificationDotKind(session.id)
                val notificationStateDescription = notificationDotKind.notificationStateDescription()
                OpenCodeCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            notificationStateDescription?.let { stateDescription = it }
                        }
                        .clickable { onOpenSession(session.id) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isWorking || notificationDotKind != NotificationDotKind.None) {
                            Box(
                                modifier = Modifier.size(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isWorking) {
                                    OpenCodeSessionRunningIndicator(agent = session.agent)
                                } else {
                                    OpenCodeNotificationDot(kind = notificationDotKind)
                                }
                            }
                        }
                        Text(
                            session.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item(key = "session-list-window-footer") {
                SessionListWindowFooter(
                    window = state.sessionListWindow,
                    hasMoreLoadedRoots = rootSessions.size > state.sessionListWindow.visibleRootLimit,
                    isProjectLoading = state.isLoading,
                    onLoadMore = viewModel::loadMoreSessions,
                    onRetry = viewModel::retrySessionListWindow,
                )
            }
        }
    }
}

private fun OpenCodeProjectState.sessionNotificationDotKind(sessionId: String): NotificationDotKind = when {
    permissionsBySession[sessionId].orEmpty().isNotEmpty() -> NotificationDotKind.Permission
    notifications.sessionUnseenHasError(sessionId) -> NotificationDotKind.Error
    notifications.sessionUnseenCount(sessionId) > 0 -> NotificationDotKind.Unseen
    else -> NotificationDotKind.None
}

@Composable
private fun ProjectHero(
    state: OpenCodeProjectState,
    connectionLabel: String,
    sessionCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 74.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.project_build_anything), style = MaterialTheme.typography.headlineMedium, color = OpenCodePalette.Text)
        Text(
            text = state.normalizedDirectory,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = OpenCodePalette.MutedText,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OpenCodePill(selected = state.connectionStatus == SseConnectionStatus.Open) {
                Text(connectionLabel, style = MaterialTheme.typography.labelMedium, color = OpenCodePalette.Text)
            }
            OpenCodePill {
                Text(stringResource(R.string.project_session_count, sessionCount), style = MaterialTheme.typography.labelMedium, color = OpenCodePalette.Text)
            }
        }
        Text(
            stringResource(R.string.project_home_stats, state.providerCount, state.models.size, state.agents.size),
            style = MaterialTheme.typography.bodySmall,
            color = OpenCodePalette.FaintText,
        )
    }
}

@Composable
private fun ProjectHomeComposerPreview(
    enabled: Boolean,
    agents: List<OpenCodeAgent>,
    models: List<OpenCodeModel>,
    selectedAgent: String?,
    selectedModel: PromptModelSelection?,
    selectedModelLabel: String?,
    selectedVariant: String?,
    showVariant: Boolean,
    onOpenNewSession: () -> Unit,
    onAgentSelected: (String) -> Unit,
    onModelSelected: (OpenCodeModel) -> Unit,
    onVariantSelected: (String?) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
) {
    var activePicker by remember { mutableStateOf<ProjectComposerPicker?>(null) }
    val composerAgents = remember(agents) { composerAgentOptions(agents) }
    val placeholder = rememberPromptPlaceholderText(
        showExamples = true,
        rotateExamples = true,
    )

    LaunchedEffect(enabled, composerAgents, showVariant) {
        if (!enabled || composerAgents.isEmpty() && activePicker == ProjectComposerPicker.Agent) {
            activePicker = null
        }
        if (!showVariant && activePicker == ProjectComposerPicker.Variant) {
            activePicker = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenCodePalette.Canvas)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = OpenCodePalette.Panel,
            border = BorderStroke(1.dp, OpenCodePalette.Border),
            shadowElevation = 2.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    placeholder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onOpenNewSession,
                        )
                        .padding(start = 12.dp, top = 14.dp, end = 12.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OpenCodePalette.FaintText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProjectComposerPreviewIconButton(
                        iconRes = R.drawable.ic_opencode_plus,
                        contentDescription = stringResource(R.string.a11y_attach_file),
                        onClick = onOpenNewSession,
                        enabled = enabled,
                        containerColor = Color.Transparent,
                        iconTint = OpenCodePalette.Text,
                        cornerRadius = 6.dp,
                        iconSize = 18.dp,
                    )
                    ProjectComposerPreviewIconButton(
                        iconRes = R.drawable.ic_opencode_arrow_up,
                        contentDescription = stringResource(R.string.a11y_send),
                        onClick = onOpenNewSession,
                        enabled = false,
                        containerColor = OpenCodePalette.IconStrongDisabled,
                        iconTint = OpenCodePalette.IconInvert,
                        cornerRadius = 4.dp,
                        iconSize = 16.dp,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                ComposerParameterButton(
                    text = selectedAgent?.let { agentParameterLabel(it) }
                        ?: stringResource(R.string.composer_select_agent),
                    expanded = activePicker == ProjectComposerPicker.Agent,
                    enabled = enabled && composerAgents.isNotEmpty(),
                    onClick = {
                        activePicker = if (activePicker == ProjectComposerPicker.Agent) {
                            null
                        } else {
                            ProjectComposerPicker.Agent
                        }
                    },
                )
                if (activePicker == ProjectComposerPicker.Agent) {
                    AgentPickerPopup(
                        agents = composerAgents,
                        selectedAgent = selectedAgent,
                        onDismiss = { activePicker = null },
                        onSelected = {
                            onAgentSelected(it)
                            activePicker = null
                        },
                    )
                }
            }
            Box {
                ComposerParameterButton(
                    text = selectedModelLabel ?: stringResource(R.string.composer_select_model),
                    expanded = activePicker == ProjectComposerPicker.Model,
                    enabled = enabled,
                    onClick = {
                        activePicker = if (activePicker == ProjectComposerPicker.Model) {
                            null
                        } else {
                            ProjectComposerPicker.Model
                        }
                    },
                )
                if (activePicker == ProjectComposerPicker.Model) {
                    ModelPickerPopup(
                        models = models,
                        selectedModel = selectedModel,
                        onDismiss = { activePicker = null },
                        onOpenProviders = {
                            activePicker = null
                            onOpenProviders()
                        },
                        onOpenModels = {
                            activePicker = null
                            onOpenModels()
                        },
                        onSelected = {
                            onModelSelected(it)
                            activePicker = null
                        },
                    )
                }
            }
            if (showVariant) {
                Box {
                    ComposerParameterButton(
                        text = selectedVariant?.toVariantDisplayLabel()
                            ?: stringResource(R.string.composer_default_variant),
                        expanded = activePicker == ProjectComposerPicker.Variant,
                        enabled = enabled,
                        onClick = {
                            activePicker = if (activePicker == ProjectComposerPicker.Variant) {
                                null
                            } else {
                                ProjectComposerPicker.Variant
                            }
                        },
                    )
                    if (activePicker == ProjectComposerPicker.Variant) {
                        VariantPickerPopup(
                            variants = selectedModel?.let { selection ->
                                models.firstOrNull {
                                    it.providerId == selection.providerId && it.modelId == selection.modelId
                                }?.variants
                            }.orEmpty(),
                            selectedVariant = selectedVariant,
                            onDismiss = { activePicker = null },
                            onSelected = {
                                onVariantSelected(it)
                                activePicker = null
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SseConnectionStatus.label(): String = when (this) {
    SseConnectionStatus.Closed -> stringResource(R.string.sse_closed)
    SseConnectionStatus.Connecting -> stringResource(R.string.sse_connecting)
    SseConnectionStatus.Open -> stringResource(R.string.sse_open)
    is SseConnectionStatus.Retrying -> stringResource(R.string.sse_retrying, attempt)
    is SseConnectionStatus.Failed -> stringResource(R.string.sse_failed)
}

@Composable
private fun ProjectComposerPreviewIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    containerColor: Color,
    iconTint: Color,
    cornerRadius: Dp,
    iconSize: Dp,
) {
    Box(
        modifier = modifier
            .width(48.dp)
            .height(48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(cornerRadius),
            color = containerColor,
            border = BorderStroke(1.dp, Color.Transparent),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconSize),
                    tint = iconTint,
                )
            }
        }
    }
}

private enum class ProjectComposerPicker {
    Agent,
    Model,
    Variant,
}
