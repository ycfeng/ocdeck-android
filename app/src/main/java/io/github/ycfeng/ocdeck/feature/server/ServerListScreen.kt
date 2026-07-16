package io.github.ycfeng.ocdeck.feature.server

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.safeServerBaseUrlForDisplay
import io.github.ycfeng.ocdeck.ui.component.OpenCodeCard
import io.github.ycfeng.ocdeck.ui.component.OpenCodeConfirmDialog
import io.github.ycfeng.ocdeck.ui.component.OpenCodeHeaderIconButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodePrimaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSecondaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSectionLabel
import io.github.ycfeng.ocdeck.ui.component.OpenCodeTopBar
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.asString
import kotlinx.coroutines.launch

@Composable
fun ServerListScreen(
    viewModel: ServerListViewModel,
    onAddServer: () -> Unit,
    onEditServer: (String) -> Unit,
    onOpenServer: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val autoScrollThresholdPx = with(density) { ServerDragAutoScrollThreshold.toPx() }
    val autoScrollStepPx = with(density) { ServerDragAutoScrollStep.toPx() }
    var displayedServers by remember { mutableStateOf(state.servers) }
    var dragState by remember { mutableStateOf<ServerDragState?>(null) }
    var pendingDeleteServer by remember { mutableStateOf<ServerConfig?>(null) }
    var lastBackPressMillis by remember { mutableStateOf(0L) }
    val exitToastText = stringResource(R.string.servers_exit_toast)
    val moveServerUpLabel = stringResource(R.string.a11y_move_server_up)
    val moveServerDownLabel = stringResource(R.string.a11y_move_server_down)

    LaunchedEffect(state.servers) {
        if (dragState == null) {
            displayedServers = state.servers
        }
    }

    BackHandler {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressMillis <= ExitBackPressIntervalMillis) {
            context.findActivity()?.finish()
        } else {
            lastBackPressMillis = now
            Toast.makeText(context, exitToastText, Toast.LENGTH_SHORT).show()
        }
    }

    pendingDeleteServer?.let { server ->
        val displayName = if (safeServerBaseUrlForDisplay(server.baseUrl) != null) {
            server.name
        } else {
            stringResource(R.string.servers_invalid_name)
        }
        OpenCodeConfirmDialog(
            title = stringResource(R.string.servers_delete_title),
            message = stringResource(R.string.servers_delete_message, displayName),
            confirmText = stringResource(R.string.servers_delete_confirm),
            onDismiss = { pendingDeleteServer = null },
            onConfirm = {
                viewModel.deleteServer(server)
                pendingDeleteServer = null
            },
        )
    }

    Scaffold(
        containerColor = OpenCodePalette.Canvas,
        topBar = {
            OpenCodeTopBar(
                title = stringResource(R.string.servers_title),
                actions = {
                    OpenCodeHeaderIconButton(
                        iconRes = R.drawable.ic_opencode_settings_gear,
                        contentDescription = stringResource(R.string.a11y_settings),
                        onClick = onOpenSettings,
                    )
                },
            )
        },
        bottomBar = {
            Surface(color = OpenCodePalette.Canvas) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 17.dp)
                        .padding(top = 8.dp, bottom = 16.dp),
                ) {
                    OpenCodePrimaryButton(
                        text = stringResource(R.string.servers_add),
                        onClick = onAddServer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    )
                }
            }
        },
    ) { padding ->
        when {
            !state.isLoaded -> Unit
            state.servers.isEmpty() -> ServerWelcomeContent(
                error = state.error,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 17.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(stringResource(R.string.servers_headline), style = MaterialTheme.typography.headlineMedium)
                }
                item {
                    OpenCodeSectionLabel(stringResource(R.string.servers_subtitle))
                }
                state.error?.let { error ->
                    item {
                        Text(error.asString(), color = MaterialTheme.colorScheme.error)
                    }
                }
                itemsIndexed(displayedServers, key = { _, server -> server.id }) { index, server ->
                    val currentDragState = dragState
                    val isDragging = currentDragState?.serverId == server.id
                    val serverIds = displayedServers.mapTo(mutableSetOf()) { it.id }
                    val accessibilityReorderActions = buildList {
                        if (index > 0) {
                            add(CustomAccessibilityAction(moveServerUpLabel) {
                                val reorderedServers = displayedServers.move(index, index - 1)
                                displayedServers = reorderedServers
                                viewModel.saveServerOrder(reorderedServers.map { it.id }) {
                                    displayedServers = state.servers
                                }
                                true
                            })
                        }
                        if (index < displayedServers.lastIndex) {
                            add(CustomAccessibilityAction(moveServerDownLabel) {
                                val reorderedServers = displayedServers.move(index, index + 1)
                                displayedServers = reorderedServers
                                viewModel.saveServerOrder(reorderedServers.map { it.id }) {
                                    displayedServers = state.servers
                                }
                                true
                            })
                        }
                    }
                    ServerCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (isDragging) Modifier else Modifier.animateItem())
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = currentDragState?.offsetY?.takeIf { isDragging } ?: 0f }
                            .semantics(mergeDescendants = true) { customActions = accessibilityReorderActions },
                        server = server,
                        healthText = (state.health[server.id] ?: UiText.Resource(R.string.servers_health_unchecked)).asString(),
                        isChecking = state.checkingServerId == server.id,
                        isDeleting = state.deletingServerId == server.id,
                        isDragging = isDragging,
                        dragHandleModifier = if (displayedServers.size > 1) {
                            Modifier.pointerInput(server.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragState = ServerDragState(server.id)
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val activeDragState = dragState
                                        val draggedItem = activeDragState?.let { listState.findVisibleItem(it.serverId) }
                                        if (activeDragState != null && draggedItem != null) {
                                            val updatedDragState = activeDragState.copy(offsetY = activeDragState.offsetY + dragAmount.y)
                                            dragState = updatedDragState
                                            val draggedTop = draggedItem.offset.toFloat() + updatedDragState.offsetY
                                            val draggedBottom = draggedTop + draggedItem.size.toFloat()
                                            val targetItem = listState.findDragTarget(
                                                draggedServerId = activeDragState.serverId,
                                                draggedTop = draggedTop,
                                                draggedBottom = draggedBottom,
                                                serverIds = serverIds,
                                            )
                                            if (targetItem != null) {
                                                val fromIndex = displayedServers.indexOfFirst { it.id == activeDragState.serverId }
                                                val toIndex = displayedServers.indexOfFirst { it.id == targetItem.key }
                                                if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                                                    val newDraggedItemOffset = targetItem.draggedItemOffsetAfterMove(
                                                        draggedItem = draggedItem,
                                                        movingDown = fromIndex < toIndex,
                                                    )
                                                    displayedServers = displayedServers.move(fromIndex, toIndex)
                                                    dragState = updatedDragState.copy(
                                                        offsetY = updatedDragState.offsetY + draggedItem.offset.toFloat() - newDraggedItemOffset,
                                                    )
                                                }
                                            }
                                            val autoScroll = listState.calculateAutoScroll(
                                                draggedTop = draggedTop,
                                                draggedBottom = draggedBottom,
                                                thresholdPx = autoScrollThresholdPx,
                                                stepPx = autoScrollStepPx,
                                            )
                                            if (autoScroll != 0f) {
                                                coroutineScope.launch { listState.scrollBy(autoScroll) }
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        dragState = null
                                        val orderedIds = displayedServers.map { it.id }
                                        if (orderedIds != state.servers.map { it.id }) {
                                            viewModel.saveServerOrder(orderedIds) {
                                                displayedServers = state.servers
                                            }
                                        }
                                    },
                                    onDragCancel = {
                                        dragState = null
                                        displayedServers = state.servers
                                    },
                                )
                            }
                        } else {
                            Modifier
                        },
                        onCheck = { viewModel.checkHealth(server) },
                        onOpen = { onOpenServer(server.id) },
                        onEdit = { onEditServer(server.id) },
                        onRequestDelete = { pendingDeleteServer = server },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerWelcomeContent(
    error: UiText?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        item {
            Image(
                painter = painterResource(R.drawable.ocdeck_app_logo),
                contentDescription = null,
                modifier = Modifier.size(88.dp),
            )
        }
        item {
            Text(
                text = stringResource(R.string.servers_welcome_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = stringResource(R.string.servers_welcome_message),
                style = MaterialTheme.typography.bodyLarge,
                color = OpenCodePalette.MutedText,
                textAlign = TextAlign.Center,
            )
        }
        error?.let {
            item {
                Text(
                    text = it.asString(),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val ExitBackPressIntervalMillis = 2_000L
private val ServerDragAutoScrollThreshold = 64.dp
private val ServerDragAutoScrollStep = 24.dp

private data class ServerDragState(
    val serverId: String,
    val offsetY: Float = 0f,
) {
    override fun toString(): String =
        "ServerDragState(serverId=<redacted>, offsetY=$offsetY)"
}

private fun LazyListState.findVisibleItem(key: String): LazyListItemInfo? = layoutInfo.visibleItemsInfo
    .firstOrNull { it.key == key }

private fun LazyListState.findDragTarget(
    draggedServerId: String,
    draggedTop: Float,
    draggedBottom: Float,
    serverIds: Set<String>,
): LazyListItemInfo? {
    val draggedCenter = (draggedTop + draggedBottom) / 2f
    return layoutInfo.visibleItemsInfo.firstOrNull { item ->
        val itemKey = item.key as? String
        itemKey != null &&
            itemKey != draggedServerId &&
            itemKey in serverIds &&
            draggedCenter >= item.offset.toFloat() &&
            draggedCenter <= (item.offset + item.size).toFloat()
    }
}

private fun LazyListItemInfo.draggedItemOffsetAfterMove(
    draggedItem: LazyListItemInfo,
    movingDown: Boolean,
): Float = if (movingDown) {
    offset.toFloat() + size.toFloat() - draggedItem.size.toFloat()
} else {
    offset.toFloat()
}

private fun LazyListState.calculateAutoScroll(
    draggedTop: Float,
    draggedBottom: Float,
    thresholdPx: Float,
    stepPx: Float,
): Float {
    val viewportStart = layoutInfo.viewportStartOffset.toFloat() + thresholdPx
    val viewportEnd = layoutInfo.viewportEndOffset.toFloat() - thresholdPx
    return when {
        draggedBottom > viewportEnd -> stepPx
        draggedTop < viewportStart -> -stepPx
        else -> 0f
    }
}

private fun <T> List<T>.move(fromIndex: Int, toIndex: Int): List<T> {
    if (fromIndex == toIndex || fromIndex !in indices || toIndex !in indices) {
        return this
    }
    return toMutableList().apply {
        add(toIndex, removeAt(fromIndex))
    }
}

@Composable
private fun ServerCard(
    modifier: Modifier = Modifier,
    server: ServerConfig,
    healthText: String,
    isChecking: Boolean,
    isDeleting: Boolean,
    isDragging: Boolean,
    dragHandleModifier: Modifier,
    onCheck: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val safeBaseUrl = safeServerBaseUrlForDisplay(server.baseUrl)
    OpenCodeCard(
        modifier = modifier,
        color = if (isDragging) OpenCodePalette.PanelMuted else OpenCodePalette.Panel,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 96.dp)
                        .then(dragHandleModifier),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (safeBaseUrl == null) stringResource(R.string.servers_invalid_name) else server.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        safeBaseUrl ?: stringResource(R.string.servers_url_hidden),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenCodePalette.MutedText,
                    )
                    server.sshTunnel?.let { tunnel ->
                        Text(
                            stringResource(
                                R.string.servers_summary_ssh_tunnel,
                                tunnel.username,
                                tunnel.host,
                                tunnel.port,
                                tunnel.localPort,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = OpenCodePalette.MutedText,
                        )
                    }
                    server.frpcStcpVisitor?.let { visitor ->
                        Text(
                            stringResource(
                                R.string.servers_summary_frpc_stcp,
                                visitor.serverAddr,
                                visitor.serverPort,
                                visitor.serverName,
                                visitor.bindPort,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = OpenCodePalette.MutedText,
                        )
                    }
                    Text(healthText, style = MaterialTheme.typography.bodySmall, color = OpenCodePalette.FaintText)
                }
                Row(
                    modifier = Modifier.align(Alignment.TopEnd),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    IconButton(onClick = onEdit) {
                        Icon(
                            painter = painterResource(R.drawable.ic_opencode_edit),
                            contentDescription = stringResource(R.string.a11y_edit_server),
                            tint = OpenCodePalette.FaintText,
                        )
                    }
                    IconButton(
                        onClick = onRequestDelete,
                        enabled = !isDeleting,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_opencode_delete),
                            contentDescription = stringResource(R.string.a11y_delete_server),
                            tint = if (isDeleting) OpenCodePalette.FaintText else OpenCodePalette.Danger,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeSecondaryButton(
                    text = if (isChecking) stringResource(R.string.servers_checking) else stringResource(R.string.servers_health_check),
                    enabled = !isChecking,
                    onClick = onCheck,
                    modifier = Modifier.weight(1f),
                )
                OpenCodePrimaryButton(text = stringResource(R.string.action_open), onClick = onOpen, modifier = Modifier.weight(1f))
            }
        }
    }
}
