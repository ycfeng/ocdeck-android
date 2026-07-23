package io.github.ycfeng.ocdeck.feature.session

import android.content.ActivityNotFoundException
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommand
import io.github.ycfeng.ocdeck.domain.model.OpenCodeDiffFile
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessage
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessageComment
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePart
import io.github.ycfeng.ocdeck.domain.model.OpenCodeModel
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionInfo
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionOption
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import io.github.ycfeng.ocdeck.domain.prompt.AttachmentLimits
import io.github.ycfeng.ocdeck.domain.prompt.OpenCodePromptSender
import io.github.ycfeng.ocdeck.domain.prompt.PromptAttachment
import io.github.ycfeng.ocdeck.domain.prompt.PromptSendAction
import io.github.ycfeng.ocdeck.domain.prompt.ProjectFileContext
import io.github.ycfeng.ocdeck.ui.component.LocalizedModalBottomSheet
import io.github.ycfeng.ocdeck.ui.component.LocalizedPopup
import io.github.ycfeng.ocdeck.ui.component.LocalPlatformActionModeActive
import io.github.ycfeng.ocdeck.feature.composer.AgentPickerPopup
import io.github.ycfeng.ocdeck.feature.composer.ComposerParameterButton
import io.github.ycfeng.ocdeck.feature.composer.ModelPickerPopup
import io.github.ycfeng.ocdeck.feature.composer.VariantPickerPopup
import io.github.ycfeng.ocdeck.feature.composer.agentParameterLabel
import io.github.ycfeng.ocdeck.feature.composer.composerAgentOptions
import io.github.ycfeng.ocdeck.feature.composer.rememberPromptPlaceholderText
import io.github.ycfeng.ocdeck.feature.composer.toVariantDisplayLabel
import io.github.ycfeng.ocdeck.ui.component.OpenCodeCard
import io.github.ycfeng.ocdeck.ui.component.OpenCodeConfirmDialog
import io.github.ycfeng.ocdeck.ui.component.OpenCodeDialog
import io.github.ycfeng.ocdeck.ui.component.OpenCodeDialogButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeHeaderIconButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeMarkdownText
import io.github.ycfeng.ocdeck.ui.component.OpenCodePlainTextField
import io.github.ycfeng.ocdeck.ui.component.OpenCodePrimaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeRefreshIconButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSecondaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeServerStatusButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSessionRunningIndicator
import io.github.ycfeng.ocdeck.ui.component.OpenCodeTopBar
import io.github.ycfeng.ocdeck.ui.component.openCodeTextFieldCursorBrush
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.asString
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SessionDetailScreen(
    viewModel: SessionDetailViewModel,
    onOpenDrawer: () -> Unit,
    onOpenFiles: () -> Unit,
    onPickProjectFiles: (List<String>, (List<String>) -> Unit) -> Unit,
    onOpenServers: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSession: (String) -> Unit,
    onSessionMaterialized: (String) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val screenScope = rememberCoroutineScope()
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) {
            viewModel.finishReadingAttachments()
            return@rememberLauncherForActivityResult
        }
        val budget = viewModel.attachmentBudgetForRead() ?: return@rememberLauncherForActivityResult
        screenScope.launch {
            try {
                val result = LocalAttachmentReader.read(context.contentResolver, uris, budget)
                val admissionFailure = viewModel.addAttachments(result.attachments)
                (result.failureReason ?: admissionFailure)?.let { reason ->
                    viewModel.showAttachmentError(reason.toUiText())
                }
            } finally {
                viewModel.finishReadingAttachments()
            }
        }
    }
    val visibleMessages = remember(state.visibleMessages) {
        state.visibleMessages.filter { message ->
            message.text.isNotBlank() ||
                hasRenderableSessionPart(message.parts) ||
                hasMessageCommentPart(message.parts) ||
                hasFileAttachmentPart(message.parts) ||
                hasProjectFileContextPart(message.parts) ||
                message.error != null ||
                message.isOptimistic
        }
    }
    val assistantTurnDurationMillis = remember(state.messages) {
        assistantTurnDurationMillisByMessageId(state.messages)
    }
    val isNewSessionEmpty = state.currentSessionId == OpenCodePromptSender.NEW_SESSION_ID &&
        visibleMessages.isEmpty() &&
        !state.isSending
    val showAssistantThinking = state.pendingQuestion == null &&
        state.pendingPermission == null &&
        (state.isWorking || state.isSending) &&
        !isNewSessionEmpty &&
        visibleMessages.lastOrNull()?.role?.equals("assistant", ignoreCase = true) != true
    val messageListState = rememberLazyListState()
    val isTextSelectionActive = LocalPlatformActionModeActive.current
    val latestVisibleMessageId = visibleMessages.lastOrNull()?.id
    val firstUserMessageIndex = remember(visibleMessages) {
        findFirstUserMessageIndex(visibleMessages)
    }
    val latestMessageItemIndex = resolveLatestMessageItemIndex(
        messageCount = visibleMessages.size,
        showAssistantThinking = showAssistantThinking,
    )
    val activeAssistantMessageId = if (state.isWorking) {
        visibleMessages.lastOrNull()?.takeIf { it.role.equals("assistant", ignoreCase = true) }?.id
    } else {
        null
    }
    val resetMessagesEnabled = state.currentSessionId != OpenCodePromptSender.NEW_SESSION_ID &&
        !state.isChildSession &&
        state.pendingQuestion == null &&
        state.pendingPermission == null &&
        !state.isSending &&
        !state.isRevertActionInProgress &&
        !state.isRevertBranchCommitting
    var contextUsageExpanded by remember { mutableStateOf(false) }
    var sessionMenuExpanded by remember { mutableStateOf(false) }
    var sessionDialog by remember { mutableStateOf<SessionDialog?>(null) }
    var renameTitle by remember { mutableStateOf("") }
    var hasPositionedInitialMessages by remember(state.currentSessionId) { mutableStateOf(false) }
    var jumpToFirstUserPending by remember(state.currentSessionId) { mutableStateOf(false) }

    LaunchedEffect(state.currentSessionId, latestVisibleMessageId, showAssistantThinking) {
        latestMessageItemIndex?.let { targetIndex ->
            if (!isTextSelectionActive) messageListState.animateScrollToItem(targetIndex)
            hasPositionedInitialMessages = true
        }
    }

    LaunchedEffect(state.currentSessionId, visibleMessages.isEmpty(), state.hasOlderMessages) {
        if (shouldLoadOlderMessagesForEmptyTimeline(visibleMessages.size, state.hasOlderMessages)) {
            viewModel.loadOlderMessages()
        }
    }

    LaunchedEffect(messageListState, state.currentSessionId) {
        snapshotFlow {
            hasPositionedInitialMessages &&
                messageListState.firstVisibleItemIndex == 0 &&
                messageListState.firstVisibleItemScrollOffset == 0
        }.collect { atTop ->
            if (atTop) viewModel.loadOlderMessages()
        }
    }

    LaunchedEffect(
        jumpToFirstUserPending,
        state.hasOlderMessages,
        state.isLoadingOlderMessages,
        firstUserMessageIndex,
    ) {
        if (
            jumpToFirstUserPending &&
            !state.hasOlderMessages &&
            !state.isLoadingOlderMessages
        ) {
            firstUserMessageIndex?.let { messageListState.animateScrollToItem(it) }
            jumpToFirstUserPending = false
        }
    }

    LaunchedEffect(isTextSelectionActive) {
        if (isTextSelectionActive) messageListState.stopScroll()
    }

    Scaffold(
        containerColor = OpenCodePalette.Canvas,
        topBar = {
            Column {
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
                            project = state.project,
                            serverConfig = state.serverConfig,
                            serverVersion = state.serverVersion,
                            mcpActionName = state.mcpActionName,
                            onOpenServers = onOpenServers,
                            onMcpToggle = viewModel::toggleMcp,
                        )
                        OpenCodeRefreshIconButton(
                            isRefreshing = state.isLoading,
                            onClick = viewModel::refresh,
                            enabled = !state.isLoading,
                        )
                    },
                )
                SessionTabs(
                    activeTab = state.activeTab,
                    changesCount = state.reviewDiffs.size,
                    changesEnabled = state.currentSessionId != OpenCodePromptSender.NEW_SESSION_ID,
                    onTabSelected = viewModel::selectTab,
                )
            }
        },
        bottomBar = {
            val pendingQuestion = state.pendingQuestion
            val pendingPermission = state.pendingPermission
            if (state.activeTab == SessionDetailTab.Changes && !isNewSessionEmpty) {
                // Web 移动端切到“更改”时是互斥页面，composer 和 dock 都隐藏。
            } else if (pendingQuestion != null && state.currentSessionId != OpenCodePromptSender.NEW_SESSION_ID) {
                QuestionDock(
                    request = pendingQuestion,
                    isSubmitting = state.isQuestionActionInProgress,
                    onReply = viewModel::replyQuestion,
                    onReject = viewModel::rejectQuestion,
                )
            } else if (pendingPermission != null && state.currentSessionId != OpenCodePromptSender.NEW_SESSION_ID) {
                PermissionDock(
                    request = pendingPermission,
                    isSubmitting = state.isPermissionActionInProgress,
                    onRespond = viewModel::respondPermission,
                )
            } else if (state.isChildSession) {
                SubagentReadonlyDock(
                    parentSessionId = state.parentSessionId,
                    onOpenSession = onOpenSession,
                )
            } else {
                Column(modifier = Modifier.background(OpenCodePalette.Canvas)) {
                    if (state.revertedUserMessages.isNotEmpty() && !state.isRevertBranchCommitting) {
                        SessionRevertDock(
                            messages = state.revertedUserMessages,
                            actionsEnabled = !state.isWorking && !state.isSending && !state.isRevertActionInProgress,
                            onRestoreThrough = viewModel::restoreRevertedThrough,
                            onRestoreAll = viewModel::restoreAllRevertedMessages,
                        )
                    }
                    BottomComposer(
                        value = state.composer,
                        onValueChange = viewModel::onComposerChanged,
                        attachments = state.attachments,
                        projectFileContexts = state.projectFileContexts,
                        agents = state.agents,
                        models = state.models,
                        commands = state.commands,
                        selectedAgent = state.selectedAgent,
                        selectedModel = state.selectedModel,
                        selectedModelLabel = state.selectedModelLabel,
                        selectedVariant = state.selectedVariant,
                        submitAction = state.submitAction,
                        isSending = state.isSending,
                        isReadingAttachments = state.isReadingAttachments,
                        showSuggestedPlaceholder = isNewSessionEmpty,
                        onAgentSelected = viewModel::selectAgent,
                        onModelSelected = viewModel::selectModel,
                        onVariantSelected = viewModel::selectVariant,
                        onSlashCommandSelected = viewModel::applySlashCommand,
                        onMentionSelected = viewModel::applyMention,
                        onAttachFromPhone = {
                            val start = viewModel.beginReadingAttachments()
                            start.failureReason?.let { reason -> viewModel.showAttachmentError(reason.toUiText()) }
                            if (start.started) {
                                var pickerLaunched = false
                                try {
                                    attachmentPicker.launch(LocalAttachmentReader.pickerMimeTypes)
                                    pickerLaunched = true
                                } catch (_: ActivityNotFoundException) {
                                    viewModel.showAttachmentError(UiText.Resource(R.string.attachment_read_failed))
                                } catch (_: SecurityException) {
                                    viewModel.showAttachmentError(UiText.Resource(R.string.attachment_read_failed))
                                } finally {
                                    if (!pickerLaunched) viewModel.finishReadingAttachments()
                                }
                            }
                        },
                        onAttachFromProject = {
                            onPickProjectFiles(
                                state.projectFileContexts.map(ProjectFileContext::relativePath),
                                viewModel::setProjectFileContexts,
                            )
                        },
                        onRemoveAttachment = viewModel::removeAttachment,
                        onRemoveProjectFileContext = viewModel::removeProjectFileContext,
                        onOpenProviders = onOpenProviders,
                        onOpenModels = onOpenModels,
                        onSubmit = { viewModel.submit(onSessionMaterialized) },
                    )
                }
            }
        },
    ) { padding ->
        if (isNewSessionEmpty) {
            NewSessionEmptyContent(
                directory = state.directory,
                error = state.error,
                modifier = Modifier.padding(padding),
            )
        } else if (state.activeTab == SessionDetailTab.Changes) {
            ReviewTabContent(
                state = state,
                onReviewModeSelected = viewModel::selectReviewMode,
                onRetry = viewModel::refreshReview,
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 9.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SessionTitleHeader(
                        state = state,
                        onOpenSession = onOpenSession,
                        modifier = Modifier.weight(1f),
                    )
                    ContextUsageButton(
                        usage = state.contextUsage,
                        expanded = contextUsageExpanded,
                        enabled = state.currentSessionId != OpenCodePromptSender.NEW_SESSION_ID,
                        onExpandedChange = {
                            contextUsageExpanded = it
                            if (it) sessionMenuExpanded = false
                        },
                    )
                    if (!state.isChildSession) {
                        SessionMoreMenu(
                            expanded = sessionMenuExpanded,
                            enabled = state.currentSessionId != OpenCodePromptSender.NEW_SESSION_ID && !state.isSessionActionInProgress,
                            onExpandedChange = {
                                sessionMenuExpanded = it
                                if (it) contextUsageExpanded = false
                            },
                            onRename = {
                                renameTitle = state.title
                                sessionDialog = SessionDialog.Rename
                            },
                            onArchive = { sessionDialog = SessionDialog.Archive },
                            onDelete = { sessionDialog = SessionDialog.Delete },
                        )
                    }
                }
                state.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    LazyColumn(
                        state = messageListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 124.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(visibleMessages, key = { it.id }) { message ->
                            SessionMessageCard(
                                message = message,
                                sessions = state.project.sessions,
                                models = state.models,
                                turnDurationMillis = assistantTurnDurationMillis[message.id],
                                isActiveAssistantMessage = message.id == activeAssistantMessageId,
                                resetEnabled = resetMessagesEnabled,
                                onResetToMessage = viewModel::resetToMessage,
                                onOpenSession = onOpenSession,
                            )
                        }
                        if (showAssistantThinking) {
                            item(key = "assistant-thinking") {
                                AssistantThinkingCard()
                            }
                        }
                    }
                    val jumpControlsVisible = shouldShowMessageJumpControls(
                        latestMessageItemIndex = latestMessageItemIndex,
                        hasOlderMessages = state.hasOlderMessages,
                        isTextSelectionActive = isTextSelectionActive,
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = jumpControlsVisible,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 3.dp, bottom = 3.dp),
                        enter = fadeIn(animationSpec = tween(160)) + scaleIn(
                            animationSpec = tween(160),
                            initialScale = 0.92f,
                            transformOrigin = TransformOrigin(1f, 1f),
                        ),
                        exit = fadeOut(animationSpec = tween(140)) + scaleOut(
                            animationSpec = tween(140),
                            targetScale = 0.92f,
                            transformOrigin = TransformOrigin(1f, 1f),
                        ),
                    ) {
                        SessionMessageJumpControls(
                            firstUserEnabled = jumpControlsVisible &&
                                !state.isLoadingOlderMessages &&
                                !jumpToFirstUserPending &&
                                (state.hasOlderMessages || firstUserMessageIndex != null),
                            latestEnabled = latestMessageItemIndex != null,
                            onJumpToFirstUser = {
                                jumpToFirstUserPending = true
                                viewModel.loadAllOlderMessages { completed ->
                                    if (!completed) jumpToFirstUserPending = false
                                }
                            },
                            onJumpToLatest = {
                                jumpToFirstUserPending = false
                                latestMessageItemIndex?.let { targetIndex ->
                                    screenScope.launch { messageListState.animateScrollToItem(targetIndex) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    when (sessionDialog) {
        SessionDialog.Rename -> RenameSessionDialog(
            title = renameTitle,
            isSaving = state.isSessionActionInProgress,
            onTitleChange = { renameTitle = it },
            onDismiss = { sessionDialog = null },
            onConfirm = {
                sessionDialog = null
                viewModel.renameSession(renameTitle)
            },
        )
        SessionDialog.Archive -> ConfirmSessionActionDialog(
            title = stringResource(R.string.session_archive_title),
            message = stringResource(R.string.session_archive_message, state.title),
            confirmText = stringResource(R.string.session_archive_confirm),
            isSaving = state.isSessionActionInProgress,
            onDismiss = { sessionDialog = null },
            onConfirm = {
                sessionDialog = null
                viewModel.archiveSession { onOpenSession(OpenCodePromptSender.NEW_SESSION_ID) }
            },
        )
        SessionDialog.Delete -> ConfirmSessionActionDialog(
            title = stringResource(R.string.session_delete_title),
            message = stringResource(R.string.session_delete_message, state.title),
            confirmText = stringResource(R.string.session_delete_confirm),
            isSaving = state.isSessionActionInProgress,
            onDismiss = { sessionDialog = null },
            onConfirm = {
                sessionDialog = null
                viewModel.deleteSession { onOpenSession(OpenCodePromptSender.NEW_SESSION_ID) }
            },
        )
        null -> Unit
    }
}

@Composable
private fun SessionMessageJumpControls(
    firstUserEnabled: Boolean,
    latestEnabled: Boolean,
    onJumpToFirstUser: () -> Unit,
    onJumpToLatest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SessionMessageJumpButton(
            contentDescription = stringResource(R.string.a11y_jump_to_first_user_message),
            enabled = firstUserEnabled,
            rotation = 180f,
            onClick = onJumpToFirstUser,
        )
        SessionMessageJumpButton(
            contentDescription = stringResource(R.string.a11y_jump_to_latest_message),
            enabled = latestEnabled,
            rotation = 0f,
            onClick = onJumpToLatest,
        )
    }
}

@Composable
private fun SessionMessageJumpButton(
    contentDescription: String,
    enabled: Boolean,
    rotation: Float,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = OpenCodePalette.Panel,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
        tonalElevation = 0.dp,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_opencode_chevron_down),
                contentDescription = null,
                tint = if (enabled) OpenCodePalette.IconMuted else OpenCodePalette.IconMuted.copy(alpha = 0.38f),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(rotation),
            )
        }
    }
}

@Composable
private fun NewSessionEmptyContent(
    directory: String,
    error: UiText?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OpenCodePalette.Canvas)
            .padding(start = 24.dp, end = 24.dp, bottom = 120.dp),
        contentAlignment = Alignment.Center,
    ) {
        error?.let {
            Text(
                text = it.asString(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 12.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Column(
            modifier = Modifier.widthIn(max = 340.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.ocdeck_app_logo),
                    contentDescription = null,
                    modifier = Modifier.size(50.dp),
                )
                Text(
                    text = stringResource(R.string.project_build_anything),
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 20.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = OpenCodePalette.Text,
                )
            }
            Text(
                text = directory,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = OpenCodePalette.MutedText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SessionTabs(
    activeTab: SessionDetailTab,
    changesCount: Int,
    changesEnabled: Boolean,
    onTabSelected: (SessionDetailTab) -> Unit,
) {
    val changesText = if (changesCount > 0) {
        stringResource(R.string.session_tab_changed_files, changesCount)
    } else {
        stringResource(R.string.session_tab_changes)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionTab(
            text = stringResource(R.string.session_tab_session),
            selected = activeTab == SessionDetailTab.Session,
            onClick = { onTabSelected(SessionDetailTab.Session) },
            modifier = Modifier.weight(1f),
        )
        SessionTab(
            text = changesText,
            selected = activeTab == SessionDetailTab.Changes,
            enabled = changesEnabled,
            onClick = { onTabSelected(SessionDetailTab.Changes) },
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = OpenCodePalette.Border)
}

@Composable
private fun SessionTab(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                selected -> OpenCodePalette.Text
                enabled -> OpenCodePalette.MutedText
                else -> OpenCodePalette.MutedText.copy(alpha = 0.55f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .width(32.dp)
                .height(2.dp),
            color = if (selected) OpenCodePalette.SelectionBorder else Color.Transparent,
        ) {}
    }
}

@Composable
private fun SessionTitleHeader(
    state: SessionDetailUiState,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val parentSessionId = state.parentSessionId
    if (!state.isChildSession || parentSessionId == null) {
        Column(modifier = modifier) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                color = OpenCodePalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(0.42f, fill = false)
                .widthIn(min = 48.dp)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.Button) { onOpenSession(parentSessionId) }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.parentTitle?.takeIf { it.isNotBlank() } ?: state.title,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = OpenCodePalette.MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "/",
            style = MaterialTheme.typography.labelMedium,
            color = OpenCodePalette.MutedText,
            maxLines = 1,
        )
        Text(
            text = state.childTitle?.takeIf { it.isNotBlank() } ?: state.title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = OpenCodePalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SubagentReadonlyDock(
    parentSessionId: String?,
    onOpenSession: (String) -> Unit,
) {
    val canOpenParent = !parentSessionId.isNullOrBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenCodePalette.Canvas)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = subagentReadonlyBackground(),
            border = BorderStroke(1.dp, OpenCodePalette.Border),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.session_child_prompt_disabled),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenCodePalette.MutedText,
                )
                Box(
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = canOpenParent, role = Role.Button) {
                            parentSessionId?.let(onOpenSession)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.session_child_back_to_parent),
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (canOpenParent) {
                            OpenCodePalette.Text
                        } else {
                            OpenCodePalette.MutedText.copy(alpha = 0.55f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRevertDock(
    messages: List<OpenCodeMessage>,
    actionsEnabled: Boolean,
    onRestoreThrough: (String) -> Unit,
    onRestoreAll: () -> Unit,
) {
    val firstMessage = messages.firstOrNull() ?: return
    var expanded by rememberSaveable(firstMessage.id, messages.size) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = OpenCodePalette.PanelMuted,
            border = BorderStroke(1.dp, OpenCodePalette.Border),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pluralStringResource(R.plurals.session_revert_dock_summary, messages.size, messages.size),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = OpenCodePalette.Text,
                            maxLines = 1,
                        )
                        Text(
                            text = firstMessage.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = OpenCodePalette.MutedText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        modifier = Modifier
                            .widthIn(min = 48.dp)
                            .heightIn(min = 48.dp),
                        enabled = actionsEnabled,
                        onClick = onRestoreAll,
                    ) {
                        Text(stringResource(R.string.session_revert_restore_all))
                    }
                    QuestionDockIconButton(
                        label = stringResource(if (expanded) R.string.session_revert_collapse else R.string.session_revert_expand),
                        iconRes = R.drawable.ic_opencode_chevron_down,
                        rotationDegrees = if (expanded) 180f else 0f,
                        enabled = actionsEnabled,
                        expanded = expanded,
                        onClick = { expanded = !expanded },
                    )
                }
                if (expanded) {
                    HorizontalDivider(color = OpenCodePalette.Border)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 208.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        messages.forEach { message ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(start = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = message.text,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OpenCodePalette.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    modifier = Modifier
                                        .widthIn(min = 48.dp)
                                        .heightIn(min = 48.dp),
                                    enabled = actionsEnabled,
                                    onClick = { onRestoreThrough(message.id) },
                                ) {
                                    Text(stringResource(R.string.session_revert_restore_message))
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.session_revert_branch_hint),
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MutedText,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewTabContent(
    state: SessionDetailUiState,
    onReviewModeSelected: (ReviewMode) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OpenCodePalette.Canvas)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ReviewModeSelector(
                selected = state.reviewMode,
                modes = state.availableReviewModes,
                onSelected = onReviewModeSelected,
            )
        }

        when {
            state.isReviewLoading -> ReviewStatusText(stringResource(R.string.review_loading_changes))
            state.reviewError != null -> ReviewErrorContent(message = state.reviewError, onRetry = onRetry)
            state.reviewDiffs.isEmpty() -> ReviewEmptyContent(state)
            else -> ReviewDiffList(diffs = state.reviewDiffs)
        }
    }
}

@Composable
private fun ReviewModeSelector(
    selected: ReviewMode,
    modes: List<ReviewMode>,
    onSelected: (ReviewMode) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val popupOffsetY = with(LocalDensity.current) { ReviewModePopupOffsetY.roundToPx() }

    Box {
        Box(
            modifier = Modifier
                .widthIn(min = 48.dp)
                .heightIn(min = 48.dp)
                .expandableStateSemantics(expandable = true, expanded = expanded)
                .clickable(role = Role.Button) { expanded = !expanded },
            contentAlignment = Alignment.CenterStart,
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = OpenCodePalette.PanelMuted,
            ) {
                Row(
                    modifier = Modifier
                        .padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = selected.labelText(),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = OpenCodePalette.Text,
                        maxLines = 1,
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_opencode_chevron_down),
                        contentDescription = null,
                        tint = OpenCodePalette.IconMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        if (expanded) {
            LocalizedPopup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, popupOffsetY),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    modifier = Modifier.width(ReviewModePopupWidth),
                    shape = RoundedCornerShape(6.dp),
                    color = OpenCodePalette.Panel,
                    border = BorderStroke(1.dp, OpenCodePalette.Border),
                    shadowElevation = 4.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .padding(4.dp)
                            .selectableGroup(),
                    ) {
                        modes.forEach { mode ->
                            ReviewModeOption(
                                mode = mode,
                                selected = mode == selected,
                                onClick = {
                                    expanded = false
                                    onSelected(mode)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewModeOption(
    mode: ReviewMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(4.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
        ),
        color = if (selected) OpenCodePalette.PanelMuted else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, OpenCodePalette.SelectionBorder) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = mode.labelText(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = OpenCodePalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReviewStatusText(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = OpenCodePalette.MutedText,
    )
}

@Composable
private fun ReviewMode.labelText(): String = when (this) {
    ReviewMode.Git -> stringResource(R.string.review_mode_git)
    ReviewMode.Turn -> stringResource(R.string.review_mode_turn)
}

@Composable
private fun ReviewErrorContent(
    message: UiText,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message.asString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        OpenCodeSecondaryButton(text = stringResource(R.string.action_retry), onClick = onRetry)
    }
}

@Composable
private fun ReviewEmptyContent(state: SessionDetailUiState) {
    val title = when {
        !state.isGitProject -> stringResource(R.string.review_create_git_repo)
        state.reviewMode == ReviewMode.Git -> stringResource(R.string.review_no_uncommitted_changes)
        else -> stringResource(R.string.review_no_changes)
    }
    val description = if (!state.isGitProject) stringResource(R.string.review_create_git_description) else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = OpenCodePalette.Text,
                textAlign = TextAlign.Center,
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenCodePalette.MutedText,
                    textAlign = TextAlign.Center,
                )
                OpenCodeSecondaryButton(
                    text = stringResource(R.string.review_create_git_repo),
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun ReviewDiffList(diffs: List<OpenCodeDiffFile>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "review-summary") {
            Text(
                text = stringResource(R.string.session_tab_changed_files, diffs.size),
                style = MaterialTheme.typography.labelMedium,
                color = OpenCodePalette.MutedText,
            )
        }
        items(
            items = diffs,
            key = { diff -> "${diff.file}:${diff.oldFile}:${diff.status}:${diff.patch.hashCode()}" },
        ) { diff ->
            ReviewDiffItem(diff)
        }
    }
}

@Composable
private fun ReviewDiffItem(diff: OpenCodeDiffFile) {
    val file = remember(diff) { diff.toPatchFileDisplay() }
    PatchFileItem(
        file = file,
        itemKey = "review:${diff.file}:${diff.oldFile}:${diff.status}:${diff.patch.hashCode()}",
    )
}

private fun OpenCodeDiffFile.toPatchFileDisplay(): PatchFileDisplay = PatchFileDisplay(
    filePath = file,
    relativePath = file,
    type = reviewStatusToPatchType(status, oldFile),
    additions = additions,
    deletions = deletions,
    movePath = oldFile,
    diff = patch,
)

private fun reviewStatusToPatchType(status: String?, oldFile: String?): String = when (status?.lowercase(Locale.ROOT)) {
    "add", "added", "create", "created", "new" -> "add"
    "delete", "deleted", "remove", "removed" -> "delete"
    "move", "moved", "rename", "renamed" -> "move"
    else -> if (!oldFile.isNullOrBlank()) "move" else "edit"
}

@Composable
private fun ContextUsageButton(
    usage: SessionContextUsage?,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val popupOffsetY = with(LocalDensity.current) { ContextUsagePopupOffsetY.roundToPx() }
    val contextUsageContentDescription = usage?.usagePercent?.let { usagePercent ->
        stringResource(R.string.a11y_context_usage_percent, formatPercent(usagePercent))
    } ?: stringResource(R.string.a11y_context_usage)
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val availableWidth = (screenWidth - ContextUsagePopupScreenMargin * 2).coerceAtLeast(ContextUsageTooltipWidth)
    val popupWidth = if (availableWidth < ContextUsagePopupWidth) availableWidth else ContextUsagePopupWidth

    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = contextUsageContentDescription }
                .expandableStateSemantics(expandable = enabled, expanded = expanded)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onExpandedChange(!expanded) },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = RoundedCornerShape(6.dp),
                color = if (expanded && enabled) OpenCodePalette.PanelMuted else Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ContextUsageProgressCircle(
                        fraction = usage?.usageRatio,
                        enabled = enabled,
                    )
                }
            }
        }

        if (expanded && enabled) {
            LocalizedPopup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, popupOffsetY),
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                ContextUsagePopup(
                    usage = usage,
                    width = popupWidth,
                )
            }
        }
    }
}

@Composable
private fun ContextUsagePopup(
    usage: SessionContextUsage?,
    width: androidx.compose.ui.unit.Dp,
) {
    Surface(
        modifier = Modifier.width(width),
        shape = RoundedCornerShape(6.dp),
        color = OpenCodePalette.Panel,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = ContextUsagePopupMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (usage == null) {
                Text(
                    text = stringResource(R.string.context_usage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenCodePalette.MutedText,
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ContextUsageProgressCircle(fraction = usage.usageRatio, enabled = true)
                Text(
                    text = stringResource(R.string.context_usage_title),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodePalette.Text,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatPercent(usage.usagePercent),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 12.sp),
                    color = OpenCodePalette.MutedText,
                )
            }

            ContextUsageSummaryRows(usage)

            ContextUsageInfoGrid(
                items = listOf(
                    ContextUsageInfoItem(stringResource(R.string.context_usage_session), usage.sessionTitle),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_message_count), usage.messageCount.toString()),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_provider), usage.providerId ?: "--"),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_model), usage.modelName ?: "--"),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_limit), formatNumber(usage.contextLimit)),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_current_turn_tokens), formatNumber(usage.currentTurnTokens)),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_total_tokens), formatNumber(usage.totalTokens)),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_rate), formatPercent(usage.usagePercent)),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_input_tokens), formatNumber(usage.inputTokens)),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_output_tokens), formatNumber(usage.outputTokens)),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_reasoning_tokens), formatNumber(usage.reasoningTokens)),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_cache_tokens), "${formatNumber(usage.cacheReadTokens)} / ${formatNumber(usage.cacheWriteTokens)}"),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_user_messages), usage.userMessageCount.toString()),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_assistant_messages), usage.assistantMessageCount.toString()),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_total_cost), formatCost(usage.cost)),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_created_at), formatContextDateTime(usage.createdAt)),
                    ContextUsageInfoItem(stringResource(R.string.context_usage_last_active), formatContextDateTime(usage.updatedAt)),
                ),
            )

            ContextUsageTokenBreakdown(usage)
            ContextUsageRawMessages(usage.rawMessages)
        }
    }
}

@Composable
private fun ContextUsageSummaryRows(usage: SessionContextUsage) {
    Surface(
        modifier = Modifier.width(ContextUsageTooltipWidth),
        shape = RoundedCornerShape(4.dp),
        color = OpenCodePalette.Canvas,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContextUsageSummaryRow(label = stringResource(R.string.context_usage_cost), value = formatCost(usage.cost))
            ContextUsageSummaryRow(label = stringResource(R.string.context_usage_rate), value = formatPercent(usage.usagePercent))
            ContextUsageSummaryRow(
                label = stringResource(R.string.context_usage_tokens),
                value = formatNumber(usage.totalTokens),
            )
        }
    }
}

@Composable
private fun ContextUsageSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = OpenCodePalette.MutedText,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = OpenCodePalette.Text,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
private fun ContextUsageInfoGrid(items: List<ContextUsageInfoItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ContextUsageInfoCell(item = rowItems[0], modifier = Modifier.weight(1f))
                if (rowItems.size > 1) {
                    ContextUsageInfoCell(item = rowItems[1], modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ContextUsageInfoCell(
    item: ContextUsageInfoItem,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = OpenCodePalette.MutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.value,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = OpenCodePalette.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ContextUsageTokenBreakdown(usage: SessionContextUsage) {
    val segments = listOf(
        ContextUsageTokenSegment(stringResource(R.string.context_usage_input), usage.inputTokens ?: 0, OpenCodePalette.ContextInput),
        ContextUsageTokenSegment(stringResource(R.string.context_usage_output), usage.outputTokens ?: 0, OpenCodePalette.ContextOutput),
        ContextUsageTokenSegment(stringResource(R.string.context_usage_reasoning), usage.reasoningTokens ?: 0, OpenCodePalette.ContextReasoning),
        ContextUsageTokenSegment(stringResource(R.string.context_usage_cache), (usage.cacheReadTokens ?: 0) + (usage.cacheWriteTokens ?: 0), OpenCodePalette.ContextOther),
    ).filter { it.value > 0 }
    val total = segments.sumOf { it.value }
    if (total <= 0) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.context_usage_breakdown),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = OpenCodePalette.MutedText,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(OpenCodePalette.PanelMuted),
        ) {
            segments.forEach { segment ->
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(segment.value.toFloat())
                        .background(segment.color),
                )
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segments.forEach { segment ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(segment.color, RoundedCornerShape(4.dp)),
                    )
                    Text(
                        text = "${segment.label} ${formatRatio(segment.value, total)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 12.sp),
                        color = OpenCodePalette.MutedText,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextUsageRawMessages(messages: List<SessionContextMessage>) {
    if (messages.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.context_usage_raw_messages),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = OpenCodePalette.MutedText,
        )
        Column {
            messages.forEachIndexed { index, message ->
                val shape = when {
                    messages.size == 1 -> RoundedCornerShape(8.dp)
                    index == 0 -> RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
                    index == messages.lastIndex -> RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shape,
                    color = OpenCodePalette.PanelMuted,
                    border = BorderStroke(1.dp, OpenCodePalette.Border),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${message.role} • ${message.id}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
                            color = OpenCodePalette.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatContextDateTime(message.createdAt),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 12.sp),
                            color = OpenCodePalette.MessageMeta,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextUsageProgressCircle(fraction: Float?, enabled: Boolean) {
    val progress = fraction?.coerceIn(0f, 1f) ?: 0f
    val alpha = if (enabled) 1f else 0.5f
    val borderColor = OpenCodePalette.IconMuted.copy(alpha = alpha)
    val progressColor = OpenCodePalette.Accent.copy(alpha = alpha)
    Canvas(modifier = Modifier.size(16.dp)) {
        val strokeWidth = 2.dp.toPx()
        val radius = (size.minDimension - strokeWidth) / 2f
        drawCircle(
            color = borderColor,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth),
        )
        if (progress > 0f) {
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
            )
        }
    }
}

@Composable
private fun SessionMoreMenu(
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val popupOffsetY = with(LocalDensity.current) { SessionMorePopupOffsetY.roundToPx() }
    val moreOptionsContentDescription = stringResource(R.string.a11y_more_options)

    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .expandableStateSemantics(expandable = enabled, expanded = expanded)
                .clickable(enabled = enabled, role = Role.Button) { onExpandedChange(!expanded) },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = RoundedCornerShape(6.dp),
                color = if (expanded && enabled) OpenCodePalette.PanelMuted else Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_opencode_dot_grid),
                        contentDescription = moreOptionsContentDescription,
                        modifier = Modifier.size(16.dp),
                        tint = if (enabled) OpenCodePalette.IconMuted else OpenCodePalette.IconMuted.copy(alpha = 0.5f),
                    )
                }
            }
        }

        if (expanded && enabled) {
            LocalizedPopup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, popupOffsetY),
                onDismissRequest = { onExpandedChange(false) },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    modifier = Modifier.width(SessionMorePopupWidth),
                    shape = RoundedCornerShape(6.dp),
                    color = OpenCodePalette.Panel,
                    border = BorderStroke(1.dp, OpenCodePalette.Border),
                    shadowElevation = 4.dp,
                ) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        SessionMoreMenuItem(
                            text = stringResource(R.string.action_rename),
                            onClick = {
                                onExpandedChange(false)
                                onRename()
                            },
                        )
                        SessionMoreMenuItem(
                            text = stringResource(R.string.action_archive),
                            onClick = {
                                onExpandedChange(false)
                                onArchive()
                            },
                        )
                        SessionMoreMenuItem(
                            text = stringResource(R.string.action_delete),
                            destructive = true,
                            onClick = {
                                onExpandedChange(false)
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionMoreMenuItem(
    text: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (destructive) OpenCodePalette.Danger else OpenCodePalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RenameSessionDialog(
    title: String,
    isSaving: Boolean,
    onTitleChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val canSave = title.trim().isNotBlank() && !isSaving
    OpenCodeDialog(
        title = stringResource(R.string.session_rename_title),
        isBusy = isSaving,
        onDismiss = onDismiss,
    ) {
        OpenCodePlainTextField(
            value = title,
            onValueChange = onTitleChange,
            placeholder = stringResource(R.string.session_title_placeholder),
            modifier = Modifier.fillMaxWidth(),
            minHeight = 40.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            contentAlignment = Alignment.CenterStart,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = OpenCodePalette.Text),
        )
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
                enabled = canSave,
                onClick = onConfirm,
            )
        }
    }
}

@Composable
private fun EditProjectNameDialog(
    name: String,
    isSaving: Boolean,
    error: String?,
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
                color = OpenCodePalette.MutedText,
            )
            OpenCodePlainTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.project_name_placeholder),
                modifier = Modifier.fillMaxWidth(),
                minHeight = 40.dp,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                contentAlignment = Alignment.CenterStart,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = OpenCodePalette.Text),
            )
        }
        if (error != null) {
            Text(
                text = error,
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

@Composable
private fun ConfirmSessionActionDialog(
    title: String,
    message: String,
    confirmText: String,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OpenCodeConfirmDialog(
        title = title,
        message = message,
        confirmText = confirmText,
        isConfirming = isSaving,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionDock(
    request: OpenCodePermissionRequest,
    isSubmitting: Boolean,
    onRespond: (OpenCodePermissionRequest, String) -> Unit,
) {
    val patterns = request.patterns.ifEmpty { request.always }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenCodePalette.Canvas)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = OpenCodePalette.Panel,
            border = BorderStroke(1.dp, OpenCodePalette.Border),
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PermissionWarningMark()
                    Text(
                        text = stringResource(R.string.permission_required),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = OpenCodePalette.Text,
                    )
                }
                Column(
                    modifier = Modifier.padding(start = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = permissionDescription(request.permission),
                        style = MaterialTheme.typography.bodyLarge,
                        color = OpenCodePalette.MutedText,
                    )
                    if (patterns.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 150.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            patterns.forEach { pattern ->
                                SelectionContainer {
                                    Text(
                                        text = pattern,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = OpenCodePalette.Text,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = OpenCodePalette.PanelMuted,
            border = BorderStroke(1.dp, OpenCodePalette.Border),
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OpenCodeSecondaryButton(
                    text = stringResource(R.string.action_reject),
                    enabled = !isSubmitting,
                    onClick = { onRespond(request, "reject") },
                )
                OpenCodeSecondaryButton(
                    text = stringResource(R.string.action_always_allow),
                    enabled = !isSubmitting,
                    onClick = { onRespond(request, "always") },
                )
                OpenCodePrimaryButton(
                    text = stringResource(R.string.action_allow_once),
                    enabled = !isSubmitting,
                    onClick = { onRespond(request, "once") },
                )
            }
        }
    }
}

@Composable
private fun PermissionWarningMark() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(OpenCodePalette.Warning, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "!",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = OpenCodePalette.OnWarning,
        )
    }
}

@Composable
private fun permissionDescription(permission: String): String = when (permission) {
    "external_directory" -> stringResource(R.string.permission_external_directory)
    "bash", "shell" -> stringResource(R.string.permission_bash)
    "edit" -> stringResource(R.string.permission_edit)
    "read" -> stringResource(R.string.permission_read)
    "write" -> stringResource(R.string.permission_write)
    else -> stringResource(R.string.permission_generic, permission.replace('_', ' '))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionDock(
    request: OpenCodeQuestionRequest,
    isSubmitting: Boolean,
    onReply: (OpenCodeQuestionRequest, List<List<String>>) -> Unit,
    onReject: (OpenCodeQuestionRequest) -> Unit,
) {
    val questions = request.questions.ifEmpty {
        listOf(
            OpenCodeQuestionInfo(
                header = null,
                question = stringResource(R.string.question_waiting_confirmation),
                options = emptyList(),
                multiple = false,
                custom = true,
            ),
        )
    }
    val questionCount = questions.size
    var tab by remember(request.id) { mutableStateOf(0) }
    var minimized by remember(request.id) { mutableStateOf(false) }
    var answers by remember(request.id, questionCount) { mutableStateOf(List(questionCount) { emptyList<String>() }) }
    var customAnswers by remember(request.id, questionCount) { mutableStateOf(List(questionCount) { "" }) }
    var customOn by remember(request.id, questionCount) { mutableStateOf(List(questionCount) { false }) }
    val safeTab = tab.coerceIn(0, questionCount - 1)
    val question = questions[safeTab]
    val currentAnswers = answers.getOrElse(safeTab) { emptyList() }
    val selectedOptions = currentAnswers.filter { answer -> question.options.any { it.label == answer } }
    val customText = customAnswers.getOrElse(safeTab) { "" }
    val customEnabled = customOn.getOrElse(safeTab) { false }

    fun updateQuestionAnswers(index: Int, nextAnswers: List<String>) {
        answers = answers.replaceAt(index, nextAnswers)
    }

    fun updateCustomAnswer(index: Int, enabled: Boolean, text: String) {
        val normalizedText = text.trim()
        val targetQuestion = questions[index]
        val optionAnswers = answers.getOrElse(index) { emptyList() }
            .filter { answer -> targetQuestion.options.any { it.label == answer } }
        val nextAnswers = when {
            !enabled || normalizedText.isBlank() -> optionAnswers
            targetQuestion.multiple -> optionAnswers + normalizedText
            else -> listOf(normalizedText)
        }
        customOn = customOn.replaceAt(index, enabled)
        customAnswers = customAnswers.replaceAt(index, text)
        updateQuestionAnswers(index, nextAnswers)
    }

    fun submitCurrent() {
        if (safeTab < questionCount - 1) {
            tab = safeTab + 1
            return
        }
        onReply(request, buildQuestionReplyAnswers(request.questions, answers))
    }

    LaunchedEffect(safeTab) {
        if (tab != safeTab) tab = safeTab
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenCodePalette.Canvas)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = OpenCodePalette.Panel,
            border = BorderStroke(1.dp, OpenCodePalette.Border),
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.question_count, safeTab + 1, questionCount),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = OpenCodePalette.Text,
                        )
                        question.header?.takeIf { it.isNotBlank() }?.let { header ->
                            Text(
                                text = header,
                                style = MaterialTheme.typography.labelSmall,
                                color = OpenCodePalette.MessageMeta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    QuestionProgressSegments(
                        count = questionCount,
                        activeIndex = safeTab,
                        answered = answers.map { it.isNotEmpty() },
                    )
                    QuestionDockIconButton(
                        label = if (minimized) stringResource(R.string.question_expand) else stringResource(R.string.question_minimize),
                        iconRes = R.drawable.ic_opencode_chevron_down,
                        rotationDegrees = if (minimized) 180f else 0f,
                        expanded = !minimized,
                        onClick = { minimized = !minimized },
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = question.question,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = OpenCodePalette.Text,
                    )
                    if (!minimized) {
                        Text(
                            text = if (question.multiple) stringResource(R.string.question_multiple_choice) else stringResource(R.string.question_single_choice),
                            style = MaterialTheme.typography.bodySmall,
                            color = OpenCodePalette.MessageMeta,
                        )
                    }
                }

                if (!minimized) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                            .then(if (question.multiple) Modifier else Modifier.selectableGroup()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        question.options.forEach { option ->
                            val selected = option.label in selectedOptions
                            QuestionOptionRow(
                                option = option,
                                multiple = question.multiple,
                                selected = selected,
                                enabled = !isSubmitting,
                                onClick = {
                                    if (question.multiple) {
                                        val nextOptions = if (selected) {
                                            selectedOptions - option.label
                                        } else {
                                            selectedOptions + option.label
                                        }
                                        val nextCustom = customText.trim().takeIf { customEnabled && it.isNotBlank() }
                                        updateQuestionAnswers(safeTab, nextOptions + listOfNotNull(nextCustom))
                                    } else {
                                        customOn = customOn.replaceAt(safeTab, false)
                                        updateQuestionAnswers(safeTab, listOf(option.label))
                                    }
                                },
                            )
                        }
                        if (question.custom) {
                            QuestionCustomAnswer(
                                selected = customEnabled,
                                multiple = question.multiple,
                                value = customText,
                                enabled = !isSubmitting,
                                onToggle = {
                                    updateCustomAnswer(safeTab, !customEnabled, customText)
                                },
                                onValueChange = { value ->
                                    updateCustomAnswer(safeTab, true, value)
                                },
                            )
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpenCodeSecondaryButton(
                        text = if (isSubmitting) stringResource(R.string.action_processing) else stringResource(R.string.action_ignore),
                        enabled = !isSubmitting,
                        onClick = { onReject(request) },
                    )
                    if (safeTab > 0) {
                        OpenCodeSecondaryButton(
                            text = stringResource(R.string.action_back),
                            enabled = !isSubmitting,
                            onClick = { tab = safeTab - 1 },
                        )
                    }
                    val isLast = safeTab == questionCount - 1
                    val actionText = when {
                        isSubmitting -> stringResource(R.string.action_submitting)
                        isLast -> stringResource(R.string.action_submit)
                        else -> stringResource(R.string.action_next)
                    }
                    OpenCodePrimaryButton(
                        text = actionText,
                        enabled = !isSubmitting,
                        onClick = ::submitCurrent,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionProgressSegments(
    count: Int,
    activeIndex: Int,
    answered: List<Boolean>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            color = when {
                                index == activeIndex -> OpenCodePalette.Text
                                answered.getOrElse(index) { false } -> OpenCodePalette.Accent
                                else -> OpenCodePalette.Border
                            },
                            shape = RoundedCornerShape(50),
                        ),
                )
            }
        }
    }
}

@Composable
private fun QuestionDockIconButton(
    label: String,
    iconRes: Int,
    rotationDegrees: Float,
    enabled: Boolean = true,
    expanded: Boolean? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .expandableStateSemantics(
                expandable = enabled && expanded != null,
                expanded = expanded == true,
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) OpenCodePalette.IconMuted else OpenCodePalette.IconMuted.copy(alpha = 0.38f),
            modifier = Modifier
                .size(16.dp)
                .rotate(rotationDegrees),
        )
    }
}

@Composable
private fun QuestionOptionRow(
    option: OpenCodeQuestionOption,
    multiple: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val selectionModifier = if (multiple) {
        Modifier.toggleable(
            value = selected,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onClick() },
        )
    } else {
        Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(selectionModifier),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) OpenCodePalette.AccentSoft else OpenCodePalette.Canvas,
        border = BorderStroke(1.dp, if (selected) OpenCodePalette.Accent else OpenCodePalette.Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuestionOptionMark(selected = selected, multiple = multiple)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (enabled) OpenCodePalette.Text else OpenCodePalette.MutedText,
                )
                option.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MessageMeta,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestionCustomAnswer(
    selected: Boolean,
    multiple: Boolean,
    value: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    onValueChange: (String) -> Unit,
) {
    val selectionModifier = if (multiple) {
        Modifier.toggleable(
            value = selected,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onToggle() },
        )
    } else {
        Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onToggle,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(selectionModifier),
            shape = RoundedCornerShape(8.dp),
            color = if (selected) OpenCodePalette.AccentSoft else OpenCodePalette.Canvas,
            border = BorderStroke(1.dp, if (selected) OpenCodePalette.Accent else OpenCodePalette.Border),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                QuestionOptionMark(selected = selected, multiple = multiple)
                Text(
                    text = stringResource(R.string.question_custom_answer_title),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (enabled) OpenCodePalette.Text else OpenCodePalette.MutedText,
                )
            }
        }
        if (selected) {
            OpenCodePlainTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = stringResource(R.string.question_custom_answer_placeholder),
                modifier = Modifier.fillMaxWidth(),
                minHeight = 72.dp,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = OpenCodePalette.Text),
            )
        }
    }
}

@Composable
private fun QuestionOptionMark(
    selected: Boolean,
    multiple: Boolean,
) {
    Surface(
        modifier = Modifier
            .padding(top = 2.dp)
            .size(18.dp),
        shape = RoundedCornerShape(if (multiple) 4.dp else 9.dp),
        color = if (selected && multiple) OpenCodePalette.Accent else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) OpenCodePalette.Accent else OpenCodePalette.IconMuted),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                selected && multiple -> Icon(
                    painter = painterResource(R.drawable.ic_opencode_check),
                    contentDescription = null,
                    tint = OpenCodePalette.Panel,
                    modifier = Modifier.size(12.dp),
                )
                selected -> Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(OpenCodePalette.Accent, RoundedCornerShape(50)),
                )
            }
        }
    }
}

private fun buildQuestionReplyAnswers(
    questions: List<OpenCodeQuestionInfo>,
    answers: List<List<String>>,
): List<List<String>> = questions.indices.map { index ->
    answers.getOrElse(index) { emptyList() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = mapIndexed { itemIndex, item ->
    if (itemIndex == index) value else item
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomComposer(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<PromptAttachment>,
    projectFileContexts: List<ProjectFileContext>,
    agents: List<OpenCodeAgent>,
    models: List<OpenCodeModel>,
    commands: List<OpenCodeCommand>,
    selectedAgent: String?,
    selectedModel: PromptModelSelection?,
    selectedModelLabel: String?,
    selectedVariant: String?,
    submitAction: PromptSendAction,
    isSending: Boolean,
    isReadingAttachments: Boolean,
    showSuggestedPlaceholder: Boolean,
    onAgentSelected: (String) -> Unit,
    onModelSelected: (OpenCodeModel) -> Unit,
    onVariantSelected: (String?) -> Unit,
    onSlashCommandSelected: (OpenCodeCommand) -> Unit,
    onMentionSelected: (OpenCodeAgent) -> Unit,
    onAttachFromPhone: () -> Unit,
    onAttachFromProject: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRemoveProjectFileContext: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
    onSubmit: () -> Unit,
) {
    var activeSheet by remember { mutableStateOf<ComposerSheet?>(null) }
    var agentMenuExpanded by remember { mutableStateOf(false) }
    var dismissedSuggestionValue by remember { mutableStateOf<String?>(null) }
    var composerControlsHeightPx by remember { mutableStateOf(0) }
    val composerAgents = remember(agents) { composerAgentOptions(agents) }
    val selectedModelVariants = remember(models, selectedModel) {
        selectedModel?.findModel(models)?.variants.orEmpty()
    }
    val placeholder = rememberPromptPlaceholderText(
        showExamples = showSuggestedPlaceholder,
        rotateExamples = showSuggestedPlaceholder,
    )
    val commandQuery = slashCommandQueryOrNull(value)
    val activeToken = value.substringAfterLast(' ')
    val showCommandSuggestions = commandQuery != null
    val showMentionSuggestions = !showCommandSuggestions && activeToken.startsWith("@")
    val suggestions = remember(commands, agents, commandQuery, activeToken, showMentionSuggestions) {
        when {
            commandQuery != null -> commandSuggestions(commands, commandQuery)
            showMentionSuggestions -> mentionSuggestions(agents, activeToken.removePrefix("@"))
            else -> emptyList()
        }
    }
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val bottomInsetHeight = with(density) {
        maxOf(
            WindowInsets.ime.getBottom(this),
            WindowInsets.navigationBars.getBottom(this),
        ).toDp()
    }
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val fallbackControlsHeight = ComposerInputMinimumHeight +
        (if (attachments.isEmpty() && projectFileContexts.isEmpty()) 0.dp else ComposerAttachmentStripMaxHeight) +
        ComposerSectionSpacing + ComposerParameterRowMinimumHeight
    val composerControlsHeight = if (composerControlsHeightPx > 0) {
        with(density) { composerControlsHeightPx.toDp() }
    } else {
        fallbackControlsHeight
    }
    val maxSuggestionPanelHeight = (
        configuration.screenHeightDp.dp -
            statusBarHeight -
            SessionDetailTopChromeHeight -
            bottomInsetHeight -
            composerControlsHeight -
            ComposerBottomPadding -
            ComposerSectionSpacing -
            InlineSuggestionViewportMargin
        ).coerceIn(0.dp, InlineSuggestionPanelMaxHeight)
    val showSuggestionPanel = suggestions.isNotEmpty() &&
        dismissedSuggestionValue != value &&
        maxSuggestionPanelHeight >= InlineSuggestionPanelMinHeight

    LaunchedEffect(isReadingAttachments) {
        if (isReadingAttachments) activeSheet = null
    }
    LaunchedEffect(composerAgents) {
        if (composerAgents.isEmpty()) agentMenuExpanded = false
    }
    LaunchedEffect(value) {
        if (dismissedSuggestionValue != value) dismissedSuggestionValue = null
    }

    BackHandler(enabled = showSuggestionPanel) {
        dismissedSuggestionValue = value
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenCodePalette.Canvas)
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = ComposerBottomPadding),
        verticalArrangement = Arrangement.spacedBy(ComposerSectionSpacing),
    ) {
        if (showSuggestionPanel) {
            InlineSuggestionPanel(
                suggestions = suggestions,
                showCommands = showCommandSuggestions,
                maxHeight = maxSuggestionPanelHeight,
                onSlashCommandSelected = onSlashCommandSelected,
                onMentionSelected = onMentionSelected,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { composerControlsHeightPx = it.height },
            verticalArrangement = Arrangement.spacedBy(ComposerSectionSpacing),
        ) {
            ComposerInputArea(
                value = value,
                onValueChange = onValueChange,
                attachments = attachments,
                projectFileContexts = projectFileContexts,
                submitAction = submitAction,
                isSending = isSending,
                attachmentEnabled = !isReadingAttachments,
                agentAvailable = selectedAgent != null,
                placeholder = placeholder,
                onAttach = {
                    if (!isReadingAttachments) {
                        agentMenuExpanded = false
                        activeSheet = ComposerSheet.Attach
                    }
                },
                onRemoveAttachment = onRemoveAttachment,
                onRemoveProjectFileContext = onRemoveProjectFileContext,
                onSubmit = onSubmit,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    ComposerParameterButton(
                        text = selectedAgent?.let { agentParameterLabel(it) }
                            ?: stringResource(R.string.composer_select_agent),
                        expanded = agentMenuExpanded,
                        enabled = composerAgents.isNotEmpty(),
                        onClick = {
                            activeSheet = null
                            agentMenuExpanded = !agentMenuExpanded
                        },
                    )
                    if (agentMenuExpanded) {
                        AgentPickerPopup(
                            agents = composerAgents,
                            selectedAgent = selectedAgent,
                            onDismiss = { agentMenuExpanded = false },
                            onSelected = {
                                onAgentSelected(it)
                                agentMenuExpanded = false
                            },
                        )
                    }
                }
                Box {
                    ComposerParameterButton(
                        text = selectedModelLabel ?: stringResource(R.string.composer_select_model),
                        expanded = activeSheet == ComposerSheet.Model,
                        onClick = {
                            agentMenuExpanded = false
                            activeSheet = if (activeSheet == ComposerSheet.Model) null else ComposerSheet.Model
                        },
                        leadingIconRes = selectedModel?.providerIconRes(),
                    )
                    if (activeSheet == ComposerSheet.Model) {
                        ModelPickerPopup(
                            models = models,
                            selectedModel = selectedModel,
                            onDismiss = { activeSheet = null },
                            onOpenProviders = {
                                activeSheet = null
                                onOpenProviders()
                            },
                            onOpenModels = {
                                activeSheet = null
                                onOpenModels()
                            },
                            onSelected = {
                                onModelSelected(it)
                                activeSheet = null
                            },
                        )
                    }
                }
                if (selectedModelVariants.isNotEmpty()) {
                    Box {
                        ComposerParameterButton(
                            text = selectedVariant?.toVariantDisplayLabel() ?: stringResource(R.string.composer_default_variant),
                            expanded = activeSheet == ComposerSheet.Variant,
                            onClick = {
                                agentMenuExpanded = false
                                activeSheet = if (activeSheet == ComposerSheet.Variant) null else ComposerSheet.Variant
                            },
                        )
                        if (activeSheet == ComposerSheet.Variant) {
                            VariantPickerPopup(
                                variants = selectedModelVariants,
                                selectedVariant = selectedVariant,
                                onDismiss = { activeSheet = null },
                                onSelected = {
                                    onVariantSelected(it)
                                    activeSheet = null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (activeSheet == ComposerSheet.Attach) {
        LocalizedModalBottomSheet(onDismissRequest = { activeSheet = null }) {
            AttachmentChoiceSheet(
                onLocalClick = {
                    activeSheet = null
                    onAttachFromPhone()
                },
                onProjectClick = {
                    activeSheet = null
                    onAttachFromProject()
                },
            )
        }
    }
}

@Composable
private fun ComposerInputArea(
    value: String,
    onValueChange: (String) -> Unit,
    attachments: List<PromptAttachment>,
    projectFileContexts: List<ProjectFileContext>,
    submitAction: PromptSendAction,
    isSending: Boolean,
    attachmentEnabled: Boolean,
    agentAvailable: Boolean,
    placeholder: String,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onRemoveProjectFileContext: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    var previewAttachment by remember { mutableStateOf<PromptAttachment?>(null) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ComposerInputMinimumHeight, max = 320.dp),
        shape = RoundedCornerShape(12.dp),
        color = OpenCodePalette.Panel,
        border = BorderStroke(1.dp, OpenCodePalette.ControlBorder),
        shadowElevation = 1.dp,
    ) {
        val canSubmit = !isSending &&
            submitAction != PromptSendAction.Disabled &&
            (submitAction == PromptSendAction.Stop || agentAvailable)
        Column(modifier = Modifier.fillMaxWidth()) {
            if (attachments.isNotEmpty() || projectFileContexts.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = ComposerAttachmentStripMaxHeight),
                    contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(projectFileContexts, key = { it.id }) { context ->
                        ComposerProjectFileContextCard(
                            context = context,
                            onRemove = { onRemoveProjectFileContext(context.id) },
                        )
                    }
                    items(attachments, key = { it.id }) { attachment ->
                        ComposerAttachmentCard(
                            attachment = attachment,
                            onOpenPreview = { previewAttachment = attachment },
                            onRemove = { onRemoveAttachment(attachment.id) },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ComposerInputMinimumHeight, max = ComposerTextInputMaxHeight),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 14.dp, end = 8.dp, bottom = 56.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = OpenCodePalette.Text,
                        lineHeight = 25.sp,
                    ),
                    maxLines = 6,
                    cursorBrush = openCodeTextFieldCursorBrush(),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
                                color = OpenCodePalette.MutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    },
                )
                ComposerIconButton(
                    iconRes = R.drawable.ic_opencode_plus,
                    contentDescription = stringResource(R.string.a11y_attach_file),
                    enabled = attachmentEnabled,
                    onClick = onAttach,
                    modifier = Modifier.align(Alignment.BottomStart),
                    containerColor = Color.Transparent,
                    iconTint = OpenCodePalette.Text,
                    cornerRadius = 6.dp,
                    iconSize = 18.dp,
                )
                ComposerIconButton(
                    iconRes = if (submitAction == PromptSendAction.Stop) R.drawable.ic_opencode_stop else R.drawable.ic_opencode_arrow_up,
                    contentDescription = if (submitAction == PromptSendAction.Stop) stringResource(R.string.a11y_stop) else stringResource(R.string.a11y_send),
                    enabled = canSubmit,
                    onClick = onSubmit,
                    modifier = Modifier.align(Alignment.BottomEnd),
                    containerColor = if (canSubmit) {
                        OpenCodePalette.IconStrong
                    } else {
                        OpenCodePalette.IconStrongDisabled
                    },
                    iconTint = OpenCodePalette.IconInvert,
                    cornerRadius = 4.dp,
                    iconSize = 16.dp,
                )
            }
        }
    }

    previewAttachment?.let { attachment ->
        AttachmentImagePreviewDialog(
            dataUrl = attachment.dataUrl,
            filename = attachment.filename,
            onDismiss = { previewAttachment = null },
        )
    }
}

@Composable
private fun ComposerProjectFileContextCard(
    context: ProjectFileContext,
    onRemove: () -> Unit,
) {
    val removeDescription = stringResource(
        R.string.a11y_remove_project_file_context,
        context.filename,
    )
    Surface(
        modifier = Modifier
            .widthIn(min = 180.dp, max = 260.dp)
            .heightIn(min = 64.dp),
        shape = RoundedCornerShape(6.dp),
        color = OpenCodePalette.Canvas,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_opencode_file),
                contentDescription = null,
                tint = OpenCodePalette.Accent,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .size(18.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = context.filename,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodePalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.project_file_context_source_path, context.relativePath),
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenCodePalette.MutedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = removeDescription }
                    .clickable(role = Role.Button, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_opencode_close),
                    contentDescription = null,
                    tint = OpenCodePalette.IconMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ComposerAttachmentCard(
    attachment: PromptAttachment,
    onOpenPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    val removeDescription = stringResource(R.string.a11y_remove_attachment)
    Row(
        modifier = Modifier
            .width(112.dp)
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(64.dp),
        ) {
            if (attachment.isImage) {
                AttachmentImageThumbnail(
                    dataUrl = attachment.dataUrl,
                    filename = attachment.filename,
                    onClick = onOpenPreview,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(6.dp),
                    color = OpenCodePalette.Canvas,
                    border = BorderStroke(1.dp, OpenCodePalette.Border),
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_opencode_archive),
                            contentDescription = null,
                            tint = OpenCodePalette.IconMuted,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(OpenCodePalette.AttachmentScrim)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    text = attachment.filename,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = OpenCodePalette.OnStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = removeDescription }
                .clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(20.dp),
                shape = RoundedCornerShape(10.dp),
                color = OpenCodePalette.Panel,
                border = BorderStroke(1.dp, OpenCodePalette.Border),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_opencode_close),
                        contentDescription = null,
                        tint = OpenCodePalette.IconMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color,
    iconTint: Color,
    cornerRadius: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
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
                    tint = iconTint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun InlineSuggestionPanel(
    suggestions: List<ComposerSuggestion>,
    showCommands: Boolean,
    maxHeight: androidx.compose.ui.unit.Dp,
    onSlashCommandSelected: (OpenCodeCommand) -> Unit,
    onMentionSelected: (OpenCodeAgent) -> Unit,
) {
    if (suggestions.isEmpty()) return

    OpenCodeCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = if (showCommands) stringResource(R.string.composer_slash_commands) else stringResource(R.string.composer_agent_mention),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = OpenCodePalette.Accent,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                items(suggestions) { suggestion ->
                    when (suggestion) {
                        is ComposerSuggestion.Command -> SuggestionRow(
                            title = "/${suggestion.command.name}",
                            subtitle = suggestion.command.description ?: suggestion.command.source ?: stringResource(R.string.composer_command_fallback),
                            onClick = { onSlashCommandSelected(suggestion.command) },
                        )
                        is ComposerSuggestion.Mention -> SuggestionRow(
                            title = "@${agentDisplayName(suggestion.agent.id)}",
                            subtitle = suggestion.agent.description ?: agentDisplayName(suggestion.agent.name),
                            onClick = { onMentionSelected(suggestion.agent) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    TextButton(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AttachmentChoiceSheet(
    onLocalClick: () -> Unit,
    onProjectClick: () -> Unit,
) {
    SheetScaffold(title = stringResource(R.string.attachment_title), subtitle = stringResource(R.string.attachment_subtitle)) {
        PickerRow(
            title = stringResource(R.string.attachment_local_title),
            subtitle = stringResource(R.string.attachment_local_subtitle),
            selected = false,
            onClick = onLocalClick,
        )
        PickerRow(
            title = stringResource(R.string.attachment_project_title),
            subtitle = stringResource(R.string.attachment_project_subtitle),
            selected = false,
            onClick = onProjectClick,
        )
    }
}

@Composable
private fun SheetScaffold(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = OpenCodePalette.Text)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OpenCodePalette.MutedText)
        HorizontalDivider(color = OpenCodePalette.Border)
        content()
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OpenCodeCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OpenCodePalette.MutedText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Text(stringResource(R.string.status_selected), color = OpenCodePalette.Accent, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SessionMessageCard(
    message: OpenCodeMessage,
    sessions: List<OpenCodeSession>,
    models: List<OpenCodeModel>,
    turnDurationMillis: Long?,
    isActiveAssistantMessage: Boolean,
    resetEnabled: Boolean,
    onResetToMessage: (OpenCodeMessage) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val isUser = message.role.equals("user", ignoreCase = true)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copyFeedbackToken by remember(message.id) { mutableStateOf(0) }
    val copied = copyFeedbackToken > 0
    val comments = remember(message.parts) { messageComments(message.parts) }
    val fileParts = remember(message.parts) { message.parts.filter(::isFileAttachmentPart) }
    val projectContextParts = remember(message.parts) { projectFileContextParts(message.parts) }
    val hasUserContent = message.text.isNotBlank() || fileParts.isNotEmpty() || projectContextParts.isNotEmpty()
    val resetVisible = isUser && hasUserContent && !message.isOptimistic
    val copyText = if (isUser) {
        buildList {
            message.text.takeIf { it.isNotBlank() }?.let(::add)
            comments.forEach { comment ->
                add("${comment.path}${comment.lineSuffix()}\n${comment.comment}")
            }
        }.joinToString("\n\n").ifBlank { message.error.orEmpty() }
    } else {
        message.text.takeIf { it.isNotBlank() } ?: message.error.orEmpty()
    }
    val durationText = if (isUser) {
        formatClockTime(message.createdAt)
    } else {
        formatDurationMillisText(turnDurationMillis) ?: formatDurationText(message.createdAt, message.completedAt)
    }
    val metaItems = listOfNotNull(
        message.agent?.let(::agentDisplayName),
        message.displayModelName(models),
        durationText,
    )

    LaunchedEffect(copyFeedbackToken) {
        if (copyFeedbackToken > 0) {
            delay(CopyFeedbackDurationMillis)
            copyFeedbackToken = 0
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            val userMessageMaxWidth = maxWidth * 0.82f
            if (isUser) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    comments.forEach { comment ->
                        UserMessageCommentCard(
                            comment = comment,
                            modifier = Modifier.widthIn(max = userMessageMaxWidth),
                        )
                    }
                    if (hasUserContent) {
                        Surface(
                            modifier = Modifier.widthIn(max = userMessageMaxWidth),
                            shape = RoundedCornerShape(10.dp),
                            color = OpenCodePalette.Panel,
                            border = BorderStroke(1.dp, OpenCodePalette.Border),
                        ) {
                            UserMessageContent(
                                message = message,
                                fileParts = fileParts,
                                projectContextParts = projectContextParts,
                            )
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.96f),
                    shape = RoundedCornerShape(10.dp),
                    color = OpenCodePalette.Canvas,
                ) {
                    AssistantMessageContent(
                        message = message,
                        sessions = sessions,
                        isActiveAssistantMessage = isActiveAssistantMessage,
                        onOpenSession = onOpenSession,
                    )
                }
            }
        }
        MessageMetaRow(
            isUser = isUser,
            metaItems = metaItems,
            copyEnabled = copyText.isNotBlank(),
            copied = copied,
            resetVisible = resetVisible,
            resetEnabled = resetVisible && resetEnabled,
            onReset = { onResetToMessage(message) },
            onCopy = {
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("OpenCode message", copyText)))
                    copyFeedbackToken += 1
                }
            },
        )
        message.error?.let { error ->
            SelectionContainer {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun UserMessageCommentCard(
    comment: OpenCodeMessageComment,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = OpenCodePalette.PanelMuted,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_opencode_file),
                    contentDescription = null,
                    tint = OpenCodePalette.IconMuted,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = comment.displayFilename() + comment.lineSuffix(),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodePalette.MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SelectionContainer {
                Text(
                    text = comment.comment,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenCodePalette.Text,
                )
            }
        }
    }
}

@Composable
private fun UserMessageContent(
    message: OpenCodeMessage,
    fileParts: List<OpenCodeMessagePart>,
    projectContextParts: List<OpenCodeMessagePart>,
) {
    var previewPart by remember(message.id) { mutableStateOf<OpenCodeMessagePart?>(null) }
    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (message.text.isNotBlank()) {
            SelectionContainer {
                Text(
                    message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenCodePalette.Text,
                )
            }
        }
        fileParts.forEach { part ->
            MessageAttachmentRow(
                part = part,
                onOpenPreview = { previewPart = part },
            )
        }
        projectContextParts.forEach { part ->
            MessageProjectFileContextRow(part = part)
        }
    }

    previewPart?.let { part ->
        part.url?.let { dataUrl ->
            AttachmentImagePreviewDialog(
                dataUrl = dataUrl,
                filename = part.filename?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.attachment_message_label),
                onDismiss = { previewPart = null },
            )
        }
    }
}

@Composable
private fun MessageProjectFileContextRow(part: OpenCodeMessagePart) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = OpenCodePalette.Canvas,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_opencode_file),
                contentDescription = null,
                tint = OpenCodePalette.Accent,
                modifier = Modifier.size(18.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = part.projectContextDisplayFilename(),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodePalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.project_file_context_source),
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenCodePalette.MutedText,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MessageAttachmentRow(
    part: OpenCodeMessagePart,
    onOpenPreview: () -> Unit,
) {
    val filename = part.filename?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.attachment_message_label)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = OpenCodePalette.Canvas,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isImageAttachmentPart(part)) {
                AttachmentImageThumbnail(
                    dataUrl = part.url.orEmpty(),
                    filename = filename,
                    onClick = onOpenPreview,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_opencode_archive),
                    contentDescription = null,
                    tint = OpenCodePalette.IconMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = filename,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodePalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                part.mime?.takeIf { it.isNotBlank() }?.let { mime ->
                    Text(
                        text = mime,
                        style = MaterialTheme.typography.labelSmall,
                        color = OpenCodePalette.MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageContent(
    message: OpenCodeMessage,
    sessions: List<OpenCodeSession>,
    isActiveAssistantMessage: Boolean,
    onOpenSession: (String) -> Unit,
) {
    val groups = remember(message.parts) { groupAssistantParts(message.parts) }
    if (groups.isEmpty()) {
        OpenCodeMarkdownText(
            text = message.text,
            modifier = Modifier.padding(2.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = OpenCodePalette.Text,
            selectable = true,
        )
        return
    }

    val busyContextKey = if (isActiveAssistantMessage) {
        groups.filterIsInstance<AssistantPartGroup.Context>().lastOrNull()?.key
    } else {
        null
    }
    Column(
        modifier = Modifier.padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        groups.forEach { group ->
            when (group) {
                is AssistantPartGroup.Text -> OpenCodeMarkdownText(
                    text = group.part.text.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OpenCodePalette.Text,
                    selectable = true,
                )
                is AssistantPartGroup.Context -> ContextToolGroup(
                    group = group,
                    busy = group.key == busyContextKey,
                )
                is AssistantPartGroup.Shell -> ShellToolGroup(part = group.part)
                is AssistantPartGroup.Patch -> PatchToolGroup(part = group.part)
                is AssistantPartGroup.Skill -> SkillToolGroup(part = group.part)
                is AssistantPartGroup.Question -> QuestionToolGroup(part = group.part)
                is AssistantPartGroup.SubagentTask -> SubagentToolGroup(
                    part = group.part,
                    sessions = sessions,
                    parentSessionId = message.sessionId,
                    onOpenSession = onOpenSession,
                )
                is AssistantPartGroup.GenericTool -> GenericToolGroup(part = group.part)
                is AssistantPartGroup.ToolError -> ToolErrorGroup(part = group.part)
            }
        }
    }
}

@Composable
private fun PatchToolGroup(part: OpenCodeMessagePart) {
    val display = remember(part) { patchToolDisplay(part) }
    val singleFile = display.singleFile
    val canToggle = !display.pending && display.files.isNotEmpty()
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }

    LaunchedEffect(canToggle) {
        if (!canToggle) expanded = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .expandableStateSemantics(expandable = canToggle, expanded = expanded)
                .clickable(enabled = canToggle, role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (display.pending) {
                ToolHeaderShimmerText(text = stringResource(R.string.tool_patch))
            } else {
                Text(
                    text = stringResource(R.string.tool_patch),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodePalette.Text,
                    maxLines = 1,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                when {
                    display.pending -> Unit
                    singleFile != null -> {
                        Text(
                            text = singleFile.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = OpenCodePalette.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        singleFile.directory?.let { directory ->
                            Text(
                                text = directory,
                                style = MaterialTheme.typography.labelSmall,
                                color = OpenCodePalette.MessageMeta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    display.fileCountLabel != null -> Text(
                        text = display.fileCountLabel.asString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MessageMeta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (!display.pending && singleFile != null) {
                PatchChangeCounts(file = singleFile)
            }
            if (canToggle) {
                ToolChevronIcon(expanded = expanded)
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                display.files.forEach { file ->
                    PatchFileItem(file = file, itemKey = "${part.id}:${file.filePath}")
                }
            }
        }
    }
}

@Composable
private fun PatchFileItem(
    file: PatchFileDisplay,
    itemKey: String,
) {
    val hasDiff = !file.diff.isNullOrBlank()
    var expanded by rememberSaveable(itemKey) { mutableStateOf(file.type != "delete" && hasDiff) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .expandableStateSemantics(expandable = hasDiff, expanded = expanded)
                .clickable(enabled = hasDiff, role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                file.directory?.let { directory ->
                    Text(
                        text = directory,
                        style = MaterialTheme.typography.labelSmall,
                        color = OpenCodePalette.MessageMeta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodePalette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            file.actionLabel?.let { label ->
                Text(
                    text = label.asString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = when (file.type) {
                        "add" -> OpenCodePalette.PatchAdd
                        "delete" -> OpenCodePalette.PatchDelete
                        else -> OpenCodePalette.PatchModified
                    },
                    maxLines = 1,
                )
            } ?: PatchChangeCounts(file = file)
            if (hasDiff) {
                ToolChevronIcon(expanded = expanded)
            }
        }
        if (expanded && hasDiff) {
            PatchDiffBlock(diff = file.diff.orEmpty())
        }
    }
}

@Composable
private fun PatchChangeCounts(file: PatchFileDisplay) {
    if (!file.hasChangeCount) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "+${file.additions}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
            color = OpenCodePalette.PatchAdd,
            maxLines = 1,
        )
        Text(
            text = "-${file.deletions}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium),
            color = OpenCodePalette.PatchDelete,
            maxLines = 1,
        )
    }
}

@Composable
private fun PatchDiffBlock(diff: String) {
    val verticalState = rememberScrollState()
    val horizontalState = rememberScrollState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        SelectionContainer {
            Text(
                text = diff.ifBlank { " " },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .horizontalScroll(horizontalState)
                    .verticalScroll(verticalState)
                    .padding(10.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = OpenCodePalette.Text,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SkillToolGroup(part: OpenCodeMessagePart) {
    val display = remember(part) { skillToolDisplay(part) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (display.pending) {
            ToolHeaderShimmerText(text = display.title.asString())
        } else {
            Text(
                text = display.title.asString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = OpenCodePalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuestionToolGroup(part: OpenCodeMessagePart) {
    val display = remember(part) { questionToolDisplay(part) }
    val canToggle = display.answers.isNotEmpty()
    var expanded by rememberSaveable(part.id) { mutableStateOf(display.defaultOpen) }

    LaunchedEffect(canToggle) {
        if (!canToggle) expanded = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .expandableStateSemantics(expandable = canToggle, expanded = expanded)
                .clickable(enabled = canToggle, role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tool_question),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = OpenCodePalette.Text,
                maxLines = 1,
            )
            Text(
                text = display.subtitle.asString(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = OpenCodePalette.MessageMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canToggle) {
                ToolChevronIcon(expanded = expanded)
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                display.answers.forEach { answer ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SelectionContainer {
                            Text(
                                text = answer.question.asString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = OpenCodePalette.MessageMeta,
                            )
                        }
                        SelectionContainer {
                            Text(
                                text = answer.answer.asString(),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = OpenCodePalette.Text,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentToolGroup(
    part: OpenCodeMessagePart,
    sessions: List<OpenCodeSession>,
    parentSessionId: String,
    onOpenSession: (String) -> Unit,
) {
    val display = remember(part) { subagentTaskDisplay(part) }
    val childSessionId = remember(display, parentSessionId, sessions) {
        findSubagentChildSessionId(
            display = display,
            parentSessionId = parentSessionId,
            sessions = sessions,
        )
    }
    val targetSessionId = childSessionId?.takeIf { it.isNotBlank() }
    val canOpen = targetSessionId != null
    val agent = display.agent?.takeIf { it.isNotBlank() } ?: stringResource(R.string.subagent_agent_fallback)
    val taskTitle = display.description?.takeIf { it.isNotBlank() }
        ?: sessions.firstOrNull { it.id == targetSessionId }?.title?.withoutSubagentSuffix()
        ?: stringResource(R.string.subagent_task_fallback)
    val subagentDescription = if (canOpen) {
        stringResource(R.string.a11y_open_subagent_with_details, agent, taskTitle)
    } else {
        stringResource(R.string.a11y_subagent_task_with_details, agent, taskTitle)
    }
    val openSubagent: () -> Unit = { targetSessionId?.let(onOpenSession) }
    val shape = RoundedCornerShape(6.dp)
    val cardModifier = Modifier
        .widthIn(min = 48.dp, max = 320.dp)
        .heightIn(min = 48.dp)
        .clip(shape)
        .then(
            if (canOpen) {
                Modifier.clickable(role = Role.Button, onClick = openSubagent)
            } else {
                Modifier
            },
        )
        .clearAndSetSemantics {
            contentDescription = subagentDescription
            if (canOpen) {
                role = Role.Button
                onClick {
                    openSubagent()
                    true
                }
            }
        }

    Surface(
        modifier = cardModifier,
        shape = shape,
        color = subagentTaskCardBackground(),
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (display.pending) {
                OpenCodeSessionRunningIndicator(agent = display.agent)
            }
            Text(
                text = agent,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = subagentAgentColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = taskTitle,
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyLarge,
                color = OpenCodePalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canOpen) {
                ToolChevronIcon(expanded = false)
            }
        }
    }
}

@Composable
private fun GenericToolGroup(part: OpenCodeMessagePart) {
    val display = remember(part) { genericToolDisplay(part) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (display.pending) {
            ToolHeaderShimmerText(text = display.title.asString())
            Spacer(Modifier.weight(1f))
        } else {
            Text(
                text = display.title.asString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = OpenCodePalette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = listOfNotNull(
                display.subtitle,
                display.args.takeIf { it.isNotEmpty() }?.joinToString(" "),
            ).joinToString(" ")
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenCodePalette.MessageMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolErrorGroup(part: OpenCodeMessagePart) {
    val display = remember(part) { toolErrorDisplay(part) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val canToggle = display.body.isNotBlank()
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }
    var copyFeedbackToken by remember(display.copyText) { mutableStateOf(0) }
    val copied = copyFeedbackToken > 0

    LaunchedEffect(copyFeedbackToken) {
        if (copyFeedbackToken > 0) {
            delay(CopyFeedbackDurationMillis)
            copyFeedbackToken = 0
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .expandableStateSemantics(expandable = canToggle, expanded = expanded)
                .clickable(enabled = canToggle, role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = display.title.asString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = OpenCodePalette.Text,
                maxLines = 1,
            )
            Text(
                text = display.subtitle.asString(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canToggle) {
                ToolChevronIcon(expanded = expanded)
            }
        }
        if (expanded) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            modifier = Modifier
                                .widthIn(min = 48.dp)
                                .heightIn(min = 48.dp),
                            enabled = display.copyText.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("OpenCode tool error", display.copyText)))
                                    copyFeedbackToken += 1
                                }
                            },
                        ) {
                            Text(if (copied) stringResource(R.string.action_copied) else stringResource(R.string.tool_copy_error))
                        }
                    }
                    SelectionContainer {
                        Text(
                            text = display.body.ifBlank { " " },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShellToolGroup(part: OpenCodeMessagePart) {
    val display = remember(part) { shellToolDisplay(part) }
    val hasDetails = display.copyText.isNotBlank()
    val canToggle = !display.pending && hasDetails
    var expanded by rememberSaveable(part.id) { mutableStateOf(false) }

    LaunchedEffect(canToggle) {
        if (!canToggle) expanded = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .expandableStateSemantics(expandable = canToggle, expanded = expanded)
                .clickable(enabled = canToggle, role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (display.pending) {
                ShellShimmerText()
            } else {
                Text(
                    text = "Shell",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = OpenCodePalette.Text,
                    maxLines = 1,
                )
            }
            val subtitle = when {
                display.pending || expanded -> null
                display.error != null -> display.error.lineSequence().firstOrNull()
                else -> display.command
            }?.takeIf { it.isNotBlank() }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (display.error != null) MaterialTheme.colorScheme.error else OpenCodePalette.MessageMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (canToggle) {
                ToolChevronIcon(expanded = expanded)
            }
        }
        if (expanded) {
            ShellToolOutput(display = display)
        }
    }
}

@Composable
private fun ShellToolOutput(display: ShellToolDisplay) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var copyFeedbackToken by remember(display.copyText) { mutableStateOf(0) }
    val copied = copyFeedbackToken > 0

    LaunchedEffect(copyFeedbackToken) {
        if (copyFeedbackToken > 0) {
            delay(CopyFeedbackDurationMillis)
            copyFeedbackToken = 0
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp),
                    enabled = display.copyText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("OpenCode shell", display.copyText)))
                            copyFeedbackToken += 1
                        }
                    },
                ) {
                    Text(if (copied) stringResource(R.string.action_copied) else stringResource(R.string.action_copy))
                }
            }
            SelectionContainer {
                Text(
                    text = display.copyText.ifBlank { " " },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(scrollState),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (display.error != null) MaterialTheme.colorScheme.error else OpenCodePalette.Text,
                    softWrap = true,
                )
            }
        }
    }
}

@Composable
private fun ShellShimmerText() {
    val widthPx = with(LocalDensity.current) { 44.dp.toPx() }.coerceAtLeast(1f)
    val transition = rememberInfiniteTransition(label = "shell-tool")
    val shimmerOffset by transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ThinkingShimmerDurationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shell-tool-offset",
    )
    val highlightWidth = widthPx * 0.58f
    val highlightCenter = widthPx * shimmerOffset
    val brush = Brush.linearGradient(
        colors = listOf(
            OpenCodePalette.MessageMeta,
            OpenCodePalette.Text,
            OpenCodePalette.MessageMeta,
        ),
        start = Offset(highlightCenter - highlightWidth, 0f),
        end = Offset(highlightCenter + highlightWidth, 0f),
    )

    Text(
        text = "Shell",
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Medium,
            brush = brush,
        ),
        maxLines = 1,
    )
}

@Composable
private fun ToolHeaderShimmerText(text: String) {
    val widthDp = (text.length.coerceAtLeast(2) * 10).dp
    val widthPx = with(LocalDensity.current) { widthDp.toPx() }.coerceAtLeast(1f)
    val transition = rememberInfiniteTransition(label = "tool-header")
    val shimmerOffset by transition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ThinkingShimmerDurationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tool-header-offset",
    )
    val highlightWidth = widthPx * 0.58f
    val highlightCenter = widthPx * shimmerOffset
    val brush = Brush.linearGradient(
        colors = listOf(
            OpenCodePalette.MessageMeta,
            OpenCodePalette.Text,
            OpenCodePalette.MessageMeta,
        ),
        start = Offset(highlightCenter - highlightWidth, 0f),
        end = Offset(highlightCenter + highlightWidth, 0f),
    )

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Medium,
            brush = brush,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ContextToolGroup(
    group: AssistantPartGroup.Context,
    busy: Boolean,
) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    val pending = group.isPending(forceBusy = busy)
    val summary = contextToolSummary(group.parts).localizedLabel()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(6.dp))
                .expandableStateSemantics(expandable = true, expanded = expanded)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (pending) stringResource(R.string.tool_exploring) else stringResource(R.string.tool_explored),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = if (pending) OpenCodePalette.Text else OpenCodePalette.MessageMeta,
                maxLines = 1,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenCodePalette.MessageMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            ToolChevronIcon(expanded = expanded)
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                group.parts.forEach { part ->
                    ContextToolEntry(part = part)
                }
            }
        }
    }
}

@Composable
private fun ToolChevronIcon(expanded: Boolean) {
    Icon(
        painter = painterResource(if (expanded) R.drawable.ic_opencode_chevron_down else R.drawable.ic_opencode_chevron_right),
        contentDescription = null,
        tint = OpenCodePalette.IconMuted,
        modifier = Modifier.size(16.dp),
    )
}

@Composable
private fun Modifier.expandableStateSemantics(
    expandable: Boolean,
    expanded: Boolean,
): Modifier {
    if (!expandable) return this
    val description = stringResource(if (expanded) R.string.status_expanded else R.string.status_collapsed)
    return semantics { stateDescription = description }
}

@Composable
private fun ContextToolEntry(part: OpenCodeMessagePart) {
    val display = remember(part) { contextToolDisplay(part) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(5.dp)
                .background(OpenCodePalette.IconMuted, RoundedCornerShape(50)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = display.title.asString(),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = if (display.running) OpenCodePalette.Text else OpenCodePalette.MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!display.running) {
                display.subtitle?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenCodePalette.MessageMeta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (display.args.isNotEmpty()) {
                    Text(
                        text = display.args.joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = OpenCodePalette.MessageMeta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantThinkingCard() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.96f),
                shape = RoundedCornerShape(10.dp),
                color = OpenCodePalette.Canvas,
            ) {
                Box(modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp)) {
                    ThinkingShimmerText(text = stringResource(R.string.tool_thinking))
                }
            }
        }
    }
}

@Composable
private fun ThinkingShimmerText(text: String) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
        val transition = rememberInfiniteTransition(label = "assistant-thinking")
        val shimmerOffset by transition.animateFloat(
            initialValue = -0.5f,
            targetValue = 1.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = ThinkingShimmerDurationMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "assistant-thinking-offset",
        )
        val highlightWidth = widthPx * 0.34f
        val highlightCenter = widthPx * shimmerOffset
        val brush = Brush.linearGradient(
            colors = listOf(
                OpenCodePalette.MessageMeta,
                OpenCodePalette.Text,
                OpenCodePalette.MessageMeta,
            ),
            start = Offset(highlightCenter - highlightWidth, 0f),
            end = Offset(highlightCenter + highlightWidth, 0f),
        )

        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Medium,
                brush = brush,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun MessageMetaRow(
    isUser: Boolean,
    metaItems: List<String>,
    copyEnabled: Boolean,
    copied: Boolean,
    resetVisible: Boolean,
    resetEnabled: Boolean,
    onReset: () -> Unit,
    onCopy: () -> Unit,
) {
    if (metaItems.isEmpty() && !copyEnabled && !resetVisible) return
    val metaText = metaItems.joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth(if (isUser) 0.82f else 0.96f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            if (metaText.isNotBlank()) {
                MessageMetaText(
                    text = metaText,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
            if (resetVisible) {
                MessageActionButton(
                    iconRes = R.drawable.ic_opencode_reset,
                    contentDescription = stringResource(R.string.session_reset_to_here),
                    enabled = resetEnabled,
                    onClick = onReset,
                )
            }
            MessageCopyButton(
                contentDescription = stringResource(R.string.a11y_copy_message),
                enabled = copyEnabled,
                copied = copied,
                onClick = onCopy,
            )
        } else {
            MessageCopyButton(
                contentDescription = stringResource(R.string.a11y_copy_response),
                enabled = copyEnabled,
                copied = copied,
                onClick = onCopy,
            )
            if (metaText.isNotBlank()) {
                MessageMetaText(metaText)
            }
        }
    }
}

@Composable
private fun MessageMetaText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
        color = OpenCodePalette.MessageMeta,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MessageMetaSeparator() {
    Text(
        text = " · ",
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
        color = OpenCodePalette.MessageMeta,
        maxLines = 1,
    )
}

@Composable
private fun MessageCopyButton(
    contentDescription: String,
    enabled: Boolean,
    copied: Boolean,
    onClick: () -> Unit,
) {
    MessageActionButton(
        iconRes = if (copied) R.drawable.ic_opencode_check_linear else R.drawable.ic_opencode_copy,
        contentDescription = contentDescription,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun MessageActionButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = RoundedCornerShape(4.dp),
            color = Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (enabled) OpenCodePalette.MessageMeta else OpenCodePalette.MessageMeta.copy(alpha = 0.38f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private sealed interface ComposerSheet {
    data object Model : ComposerSheet
    data object Variant : ComposerSheet
    data object Attach : ComposerSheet
}

private fun LocalAttachmentFailureReason.toUiText(): UiText = when (this) {
    LocalAttachmentFailureReason.CountLimit -> UiText.Resource(
        R.string.attachment_count_limit,
        listOf(AttachmentLimits.MAX_ATTACHMENT_COUNT),
    )
    LocalAttachmentFailureReason.TotalSizeLimit -> UiText.Resource(
        R.string.attachment_total_size_limit,
        listOf(AttachmentLimits.MAX_TOTAL_MIB),
    )
    LocalAttachmentFailureReason.UnsupportedType -> UiText.Resource(R.string.attachment_unsupported_type)
    LocalAttachmentFailureReason.TooLarge -> UiText.Resource(
        R.string.attachment_too_large,
        listOf(AttachmentLimits.MAX_FILE_MIB),
    )
    LocalAttachmentFailureReason.ReadFailed -> UiText.Resource(R.string.attachment_read_failed)
}

private enum class SessionDialog {
    Rename,
    Archive,
    Delete,
}

private sealed interface ComposerSuggestion {
    data class Command(val command: OpenCodeCommand) : ComposerSuggestion
    data class Mention(val agent: OpenCodeAgent) : ComposerSuggestion
}

private data class ContextUsageInfoItem(
    val label: String,
    val value: String,
) {
    override fun toString(): String =
        "ContextUsageInfoItem(labelPresent=${label.isNotEmpty()}, valuePresent=${value.isNotEmpty()})"
}

private data class ContextUsageTokenSegment(
    val label: String,
    val value: Long,
    val color: Color,
)

private val ReviewModePopupWidth = 128.dp
private val ReviewModePopupOffsetY = 48.dp
private val SessionMorePopupWidth = 112.dp
private val SessionMorePopupOffsetY = 48.dp
private val ContextUsageTooltipWidth = 132.dp
private val ContextUsagePopupWidth = 360.dp
private val ContextUsagePopupMaxHeight = 560.dp
private val ContextUsagePopupOffsetY = 48.dp
private val ContextUsagePopupScreenMargin = 12.dp
private val ComposerInputMinimumHeight = 89.dp
private val ComposerAttachmentStripMaxHeight = 80.dp
private val ComposerTextInputMaxHeight = 224.dp
private val ComposerParameterRowMinimumHeight = 48.dp
private val ComposerSectionSpacing = 8.dp
private val ComposerBottomPadding = 12.dp
private val SessionDetailTopChromeHeight = 96.dp
private val InlineSuggestionViewportMargin = 8.dp
private val InlineSuggestionPanelMinHeight = 112.dp
private val InlineSuggestionPanelMaxHeight = 192.dp
private const val CopyFeedbackDurationMillis = 2_000L
private const val ThinkingShimmerDurationMillis = 1_400

internal fun slashCommandQueryOrNull(value: String): String? {
    val commandInput = value.trimStart()
    if (!commandInput.startsWith("/")) return null
    return commandInput.removePrefix("/").takeIf { query -> query.none(Char::isWhitespace) }
}

private fun commandSuggestions(commands: List<OpenCodeCommand>, query: String): List<ComposerSuggestion> = commands
        .distinctBy { it.name }
        .filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
        .map { ComposerSuggestion.Command(it) }

@Composable
private fun ContextToolSummary.localizedLabel(): String = buildList {
    if (read > 0) add(stringResource(R.string.tool_read_count, read))
    if (search > 0) add(stringResource(R.string.tool_search_count, search))
    if (list > 0) add(stringResource(R.string.tool_list_count, list))
}.joinToString(" · ")

private fun mentionSuggestions(agents: List<OpenCodeAgent>, query: String): List<ComposerSuggestion> {
    val source = agents
        .filter { it.id == "build" || it.id == "plan" }
    return source
        .filter { query.isBlank() || it.id.contains(query, ignoreCase = true) }
        .map { ComposerSuggestion.Mention(it) }
}

private fun agentDisplayName(agentIdOrName: String): String = when (agentIdOrName) {
    "build" -> "Build"
    "plan" -> "Plan"
    else -> agentIdOrName
}

private fun findSubagentChildSessionId(
    display: SubagentTaskDisplay,
    parentSessionId: String,
    sessions: List<OpenCodeSession>,
): String? {
    display.childSessionId?.takeIf { it.isNotBlank() }?.let { return it }
    val description = display.description?.takeIf { it.isNotBlank() }
    val agent = display.agent?.takeIf { it.isNotBlank() }
    return sessions.asSequence()
        .filter { it.parentId == parentSessionId && it.archivedAt == null }
        .filter { session ->
            description == null || session.title.startsWith(description) || session.title.withoutSubagentSuffix() == description
        }
        .filter { session ->
            agent == null ||
                session.agent.equals(agent, ignoreCase = true) ||
                session.title.contains("@$agent", ignoreCase = true)
        }
        .sortedWith(
            compareByDescending<OpenCodeSession> { it.createdAt ?: it.updatedAt ?: 0L }
                .thenByDescending { it.id },
        )
        .firstOrNull()
        ?.id
}

private fun String.withoutSubagentSuffix(): String = replace(SubagentTitleSuffixRegex, "").trim()

private val SubagentTitleSuffixRegex = Regex("\\s+\\(@[^)]+ subagent\\)$", RegexOption.IGNORE_CASE)

@Composable
private fun subagentReadonlyBackground(): Color = OpenCodePalette.SubagentBackground

@Composable
private fun subagentTaskCardBackground(): Color = subagentReadonlyBackground()

@Composable
private fun subagentAgentColor(): Color = OpenCodePalette.SubagentAgent

private fun OpenCodeMessage.displayModelName(models: List<OpenCodeModel>): String? {
    val modelId = effectiveModelId() ?: return modelLabel?.takeUnless { it.contains("/") }
    val providerId = effectiveProviderId()
    return models.firstOrNull { model ->
        providerId != null &&
            model.providerId.equals(providerId, ignoreCase = true) &&
            model.modelId.equals(modelId, ignoreCase = true)
    }?.name
        ?: models.firstOrNull { it.modelId.equals(modelId, ignoreCase = true) }?.name
        ?: modelId.toDisplayModelName()
}

private fun OpenCodeMessage.effectiveProviderId(): String? = modelProviderId
    ?: modelLabel?.split("/")?.takeIf { it.size >= 2 }?.getOrNull(0)

private fun OpenCodeMessage.effectiveModelId(): String? = modelId
    ?: modelLabel?.split("/")?.let { parts ->
        if (parts.size >= 2) parts[1] else parts.firstOrNull()
    }

private fun String.toDisplayModelName(): String {
    val normalized = replace('_', '-')
    if (normalized.startsWith("gpt-", ignoreCase = true)) {
        val parts = normalized.substringAfter('-').split("-").filter { it.isNotBlank() }
        val version = parts.firstOrNull() ?: return "GPT"
        val suffix = parts.drop(1).joinToString(" ") { it.toDisplayToken() }
        return listOf("GPT-$version", suffix.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" ")
    }
    return normalized
        .split('-', '/')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.toDisplayToken() }
}

private fun String.toDisplayToken(): String = when (lowercase(Locale.ROOT)) {
    "ai" -> "AI"
    "api" -> "API"
    "gpt" -> "GPT"
    "llm" -> "LLM"
    else -> replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
    }
}

private fun formatNumber(value: Long?): String = value
    ?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) }
    ?: "--"

private fun formatPercent(value: Int?): String = value?.let { "$it%" } ?: "--"

private fun formatCost(value: Double?): String = value
    ?.let { "US$${String.format(Locale.US, "%.2f", it)}" }
    ?: "--"

private fun formatRatio(value: Long, total: Long): String = if (total > 0) {
    String.format(Locale.US, "%.1f%%", value.toDouble() * 100.0 / total.toDouble())
} else {
    "--"
}

private fun formatContextDateTime(timestamp: Long?): String = timestamp
    ?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(it)) }
    ?: "--"

private fun formatClockTime(timestamp: Long?): String? = timestamp?.let {
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it))
}

internal fun findFirstUserMessageIndex(messages: List<OpenCodeMessage>): Int? =
    messages.indexOfFirst { it.role.equals("user", ignoreCase = true) }.takeIf { it >= 0 }

internal fun resolveLatestMessageItemIndex(
    messageCount: Int,
    showAssistantThinking: Boolean,
): Int? = when {
    showAssistantThinking -> messageCount
    messageCount > 0 -> messageCount - 1
    else -> null
}

internal fun shouldLoadOlderMessagesForEmptyTimeline(
    messageCount: Int,
    hasOlderMessages: Boolean,
): Boolean = messageCount == 0 && hasOlderMessages

internal fun shouldShowMessageJumpControls(
    latestMessageItemIndex: Int?,
    hasOlderMessages: Boolean,
    isTextSelectionActive: Boolean,
): Boolean = !isTextSelectionActive && (latestMessageItemIndex != null || hasOlderMessages)

internal fun assistantTurnDurationMillisByMessageId(messages: List<OpenCodeMessage>): Map<String, Long> {
    val userCreatedAtById = messages.asSequence()
        .filter { it.role.equals("user", ignoreCase = true) }
        .mapNotNull { message -> message.createdAt?.let { createdAt -> message.id to createdAt } }
        .toMap()
    if (userCreatedAtById.isEmpty()) return emptyMap()

    val completedAtByParentId = mutableMapOf<String, Long>()
    messages.asSequence()
        .filter { it.role.equals("assistant", ignoreCase = true) }
        .forEach { message ->
            val parentId = message.parentId ?: return@forEach
            val completedAt = message.completedAt ?: return@forEach
            val existing = completedAtByParentId[parentId]
            if (existing == null || completedAt > existing) completedAtByParentId[parentId] = completedAt
        }
    if (completedAtByParentId.isEmpty()) return emptyMap()

    return messages.asSequence()
        .filter { it.role.equals("assistant", ignoreCase = true) }
        .mapNotNull { message ->
            val parentId = message.parentId ?: return@mapNotNull null
            val startedAt = userCreatedAtById[parentId] ?: return@mapNotNull null
            val completedAt = completedAtByParentId[parentId] ?: return@mapNotNull null
            val durationMillis = completedAt - startedAt
            if (durationMillis >= 0) message.id to durationMillis else null
        }
        .toMap()
}

internal fun formatDurationMillis(durationMillis: Long?): String? {
    val duration = durationMillis ?: return null
    if (duration < 0) return null
    val totalSeconds = (duration.toDouble() / 1_000.0).roundToLong().coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${totalSeconds}s"
}

@Composable
private fun formatDurationMillisText(durationMillis: Long?): String? {
    val duration = durationMillis ?: return null
    if (duration < 0) return null
    val totalSeconds = (duration.toDouble() / 1_000.0).roundToLong().coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        stringResource(R.string.duration_minutes_seconds, minutes, seconds)
    } else {
        stringResource(R.string.duration_seconds, totalSeconds)
    }
}

private fun formatDuration(start: Long?, end: Long?): String? {
    val startedAt = start ?: return null
    val completedAt = end ?: return null
    return formatDurationMillis(completedAt - startedAt)
}

@Composable
private fun formatDurationText(start: Long?, end: Long?): String? {
    val startedAt = start ?: return null
    val completedAt = end ?: return null
    return formatDurationMillisText(completedAt - startedAt)
}

private fun PromptModelSelection.providerIconRes(): Int? = when (providerId.lowercase()) {
    "openai" -> R.drawable.ic_provider_generic
    else -> null
}

private fun PromptModelSelection.findModel(models: List<OpenCodeModel>): OpenCodeModel? = models.firstOrNull {
    it.providerId == providerId && it.modelId == modelId
}
