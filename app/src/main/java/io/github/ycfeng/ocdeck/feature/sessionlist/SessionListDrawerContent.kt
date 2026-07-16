package io.github.ycfeng.ocdeck.feature.sessionlist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.store.NotificationDotKind
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.ProjectRef
import io.github.ycfeng.ocdeck.ui.component.OpenCodeNotificationDot
import io.github.ycfeng.ocdeck.ui.component.OpenCodePrimaryButton
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSectionLabel
import io.github.ycfeng.ocdeck.ui.component.OpenCodeSessionRunningIndicator
import io.github.ycfeng.ocdeck.ui.component.isOpenCodeWorkingSessionStatus
import io.github.ycfeng.ocdeck.ui.component.notificationStateDescription
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import java.text.BreakIterator
import java.util.Locale

@Composable
internal fun SessionListDrawerContent(
    state: OpenCodeProjectState,
    activeSessionId: String?,
    projects: List<ProjectRef>,
    onSelectProject: (String) -> Unit,
    onOpenProjectPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onLoadMoreSessions: () -> Unit,
    onRetrySessionListWindow: () -> Unit,
    onArchiveSession: (OpenCodeSession) -> Unit,
    onEditProject: () -> Unit,
    onClearNotifications: () -> Unit,
    onCloseProject: () -> Unit,
) {
    val projectDisplayName = projects
        .firstOrNull { it.normalizedDirectory == state.normalizedDirectory }
        ?.displayName
        ?: state.normalizedDirectory.displayName()
    val rootSessions = remember(state.sessions) { state.sessions.filter { it.parentId.isNullOrBlank() } }
    val visibleRootSessions = rootSessions.take(state.sessionListWindow.visibleRootLimit)
    val visibleSessionRows = remember(state.sessions, visibleRootSessions, activeSessionId) {
        drawerSessionRows(
            sessions = state.sessions,
            visibleRootSessions = visibleRootSessions,
            activeSessionId = activeSessionId,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(OpenCodePalette.Panel),
    ) {
        DrawerRail(
            projects = projects,
            activeProjectDirectory = state.normalizedDirectory,
            notificationDotKind = state.projectNotificationDotKind(),
            onSelectProject = onSelectProject,
            onOpenProjectPicker = onOpenProjectPicker,
            onOpenSettings = onOpenSettings,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 13.dp, end = 12.dp, top = 5.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(projectDisplayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.normalizedDirectory, style = MaterialTheme.typography.bodySmall, color = OpenCodePalette.MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ProjectMoreMenuButton(
                    hasUnseenNotifications = state.notifications.projectUnseenCount(state.normalizedDirectory) > 0,
                    onEditProject = onEditProject,
                    onClearNotifications = onClearNotifications,
                    onCloseProject = onCloseProject,
                )
            }
            OpenCodePrimaryButton(text = stringResource(R.string.session_new), onClick = onNewSession, modifier = Modifier.fillMaxWidth())
            HorizontalDivider(color = OpenCodePalette.Border)
            OpenCodeSectionLabel(stringResource(R.string.session_section))
            LazyColumn(
                modifier = Modifier.selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(visibleSessionRows, key = { it.session.id }) { row ->
                    val session = row.session
                    val status = state.statuses[session.id]?.type ?: "idle"
                    val isWorking = status.isOpenCodeWorkingSessionStatus()
                    val notificationDotKind = state.sessionNotificationDotKind(session.id)
                    val notificationStateDescription = notificationDotKind.notificationStateDescription()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = 4.dp + (16 * row.level).dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .semantics {
                                    notificationStateDescription?.let { stateDescription = it }
                                }
                                .selectable(
                                    selected = session.id == activeSessionId,
                                    role = Role.Tab,
                                    onClick = { onOpenSession(session.id) },
                                )
                                .padding(start = 4.dp, end = 4.dp),
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
                                text = session.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (row.level == 0) {
                            SidebarArchiveButton(onClick = { onArchiveSession(session) })
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }
                    }
                }
                item(key = "session-list-window-footer") {
                    SessionListWindowFooter(
                        window = state.sessionListWindow,
                        hasMoreLoadedRoots = rootSessions.size > state.sessionListWindow.visibleRootLimit,
                        isProjectLoading = state.isLoading,
                        onLoadMore = onLoadMoreSessions,
                        onRetry = onRetrySessionListWindow,
                    )
                }
            }
        }
    }
}

private data class DrawerSessionRow(
    val session: OpenCodeSession,
    val level: Int,
)

private fun drawerSessionRows(
    sessions: List<OpenCodeSession>,
    visibleRootSessions: List<OpenCodeSession>,
    activeSessionId: String?,
): List<DrawerSessionRow> {
    val path = activeSessionId
        ?.let { activeId -> activeSessionPath(sessions, activeId) }
        .orEmpty()
    val childrenOnActivePath = path.drop(1)
    return buildList {
        visibleRootSessions.forEach { root ->
            add(DrawerSessionRow(root, level = 0))
            if (path.firstOrNull()?.id == root.id) {
                childrenOnActivePath.forEachIndexed { index, child ->
                    add(DrawerSessionRow(child, level = index + 1))
                }
            }
        }
    }
}

private fun activeSessionPath(sessions: List<OpenCodeSession>, activeSessionId: String): List<OpenCodeSession> {
    val byId = sessions.associateBy { it.id }
    val reversedPath = mutableListOf<OpenCodeSession>()
    val visited = mutableSetOf<String>()
    var current = byId[activeSessionId]
    while (current != null && visited.add(current.id)) {
        reversedPath += current
        current = current.parentId?.let(byId::get)
    }
    return reversedPath.asReversed()
}

private fun OpenCodeProjectState.projectNotificationDotKind(): NotificationDotKind = when {
    permissionCount > 0 -> NotificationDotKind.Permission
    notifications.projectUnseenHasError(normalizedDirectory) -> NotificationDotKind.Error
    notifications.projectUnseenCount(normalizedDirectory) > 0 -> NotificationDotKind.Unseen
    else -> NotificationDotKind.None
}

private fun OpenCodeProjectState.sessionNotificationDotKind(sessionId: String): NotificationDotKind = when {
    permissionsBySession[sessionId].orEmpty().isNotEmpty() -> NotificationDotKind.Permission
    notifications.sessionUnseenHasError(sessionId) -> NotificationDotKind.Error
    notifications.sessionUnseenCount(sessionId) > 0 -> NotificationDotKind.Unseen
    else -> NotificationDotKind.None
}

@Composable
private fun ProjectMoreMenuButton(
    hasUnseenNotifications: Boolean,
    onEditProject: () -> Unit,
    onClearNotifications: () -> Unit,
    onCloseProject: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val density = LocalDensity.current
    val menuOffset = with(density) { IntOffset(x = 12.dp.roundToPx(), y = 48.dp.roundToPx()) }
    val moreOptionsContentDescription = stringResource(R.string.a11y_more_options)
    val expansionStateDescription = stringResource(if (expanded) R.string.status_expanded else R.string.status_collapsed)

    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = moreOptionsContentDescription
                    stateDescription = expansionStateDescription
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = { expanded = !expanded },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = RoundedCornerShape(6.dp),
                color = if (expanded || isPressed) OpenCodePalette.PanelMuted else Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_opencode_dot_grid),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = OpenCodePalette.Text,
                    )
                }
            }
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = menuOffset,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    modifier = Modifier.width(168.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = OpenCodePalette.Panel,
                    border = BorderStroke(1.dp, OpenCodePalette.Border.copy(alpha = 0.5f)),
                    shadowElevation = 10.dp,
                ) {
                    Column(modifier = Modifier.padding(4.dp)) {
                        ProjectMenuItem(text = stringResource(R.string.action_edit)) {
                            expanded = false
                            onEditProject()
                        }
                        ProjectMenuItem(
                            text = stringResource(R.string.action_clear_notifications),
                            enabled = hasUnseenNotifications,
                        ) {
                            expanded = false
                            onClearNotifications()
                        }
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = OpenCodePalette.Border,
                        )
                        ProjectMenuItem(text = stringResource(R.string.action_close)) {
                            expanded = false
                            onCloseProject()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectMenuItem(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(4.dp),
        color = if (enabled && isPressed) OpenCodePalette.PanelMuted else Color.Transparent,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = text,
                color = if (enabled) OpenCodePalette.Text else OpenCodePalette.FaintText,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 13.sp,
                    lineHeight = 19.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SidebarArchiveButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(24.dp),
            shape = RoundedCornerShape(6.dp),
            color = if (isPressed) OpenCodePalette.PanelMuted else Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_opencode_archive),
                    contentDescription = stringResource(R.string.a11y_archive),
                    modifier = Modifier.size(16.dp),
                    tint = OpenCodePalette.Text,
                )
            }
        }
    }
}

@Composable
private fun DrawerRail(
    projects: List<ProjectRef>,
    activeProjectDirectory: String,
    notificationDotKind: NotificationDotKind,
    onSelectProject: (String) -> Unit,
    onOpenProjectPicker: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .width(64.dp)
            .fillMaxHeight()
            .background(OpenCodePalette.PanelMuted)
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        val projectContentHeight = RailButtonSize * projects.size.toFloat() +
            RailItemSpacing * (projects.size - 1).coerceAtLeast(0).toFloat()
        val availableProjectHeight = (maxHeight - RailFixedContentHeight).coerceAtLeast(0.dp)
        val projectListHeight = minOf(
            projectContentHeight,
            maxHeight * ProjectRailMaxHeightFraction,
            availableProjectHeight,
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (projectListHeight > 0.dp) {
                LazyColumn(
                    modifier = Modifier
                        .height(projectListHeight)
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(RailItemSpacing),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(
                        items = projects,
                        key = { project -> project.normalizedDirectory },
                    ) { project ->
                        val selected = project.normalizedDirectory == activeProjectDirectory
                        RailButton(
                            text = projectInitial(project.displayName),
                            accessibilityLabel = stringResource(
                                R.string.a11y_open_project_named,
                                project.displayName,
                                project.normalizedDirectory,
                            ),
                            selected = selected,
                            selectionRole = Role.Tab,
                            notificationDotKind = if (selected) notificationDotKind else NotificationDotKind.None,
                            onClick = { onSelectProject(project.normalizedDirectory) },
                        )
                    }
                }
                Spacer(Modifier.height(RailItemSpacing))
            }
            RailButton(
                iconRes = R.drawable.ic_opencode_sidebar_plus,
                accessibilityLabel = stringResource(R.string.a11y_open_project),
                iconSize = 22.dp,
                onClick = onOpenProjectPicker,
            )
            Spacer(Modifier.weight(1f))
            RailButton(
                iconRes = R.drawable.ic_opencode_settings_gear,
                accessibilityLabel = stringResource(R.string.a11y_settings),
                iconSize = 20.dp,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun RailButton(
    text: String? = null,
    iconRes: Int? = null,
    accessibilityLabel: String? = text,
    iconSize: Dp = 20.dp,
    selected: Boolean = false,
    selectionRole: Role? = null,
    notificationDotKind: NotificationDotKind = NotificationDotKind.None,
    onClick: () -> Unit,
) {
    val notificationStateDescription = notificationDotKind.notificationStateDescription()
    val interactionModifier = if (selectionRole != null) {
        Modifier.selectable(
            selected = selected,
            role = selectionRole,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(
            role = Role.Button,
            onClick = onClick,
        )
    }
    Box(
        modifier = Modifier
            .size(RailButtonSize)
            .semantics(mergeDescendants = true) {
                accessibilityLabel?.let { contentDescription = it }
                notificationStateDescription?.let { stateDescription = it }
            }
            .then(interactionModifier),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(40.dp)
                .height(40.dp),
            shape = RoundedCornerShape(10.dp),
            color = OpenCodePalette.Panel,
            border = BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) OpenCodePalette.SelectionBorder else OpenCodePalette.Border,
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (iconRes != null) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = OpenCodePalette.Text,
                    )
                } else if (text != null) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = OpenCodePalette.Text,
                    )
                }
            }
        }
        OpenCodeNotificationDot(
            kind = notificationDotKind,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 6.dp),
        )
    }
}

internal fun projectInitial(displayName: String): String {
    val value = displayName.trim().ifBlank { return "O" }
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(value)
    val end = iterator.following(0).takeUnless { it == BreakIterator.DONE } ?: value.length
    return value.substring(0, end).uppercase(Locale.ROOT)
}

private fun String.displayName(): String = trimEnd('/', '\\')
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .ifBlank { this }

private val RailButtonSize = 48.dp
private val RailItemSpacing = 12.dp
private val RailFixedContentHeight = RailItemSpacing + RailButtonSize + RailButtonSize
private const val ProjectRailMaxHeightFraction = 0.75f
