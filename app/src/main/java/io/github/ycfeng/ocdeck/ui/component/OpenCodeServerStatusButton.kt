package io.github.ycfeng.ocdeck.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.safeServerBaseUrlForDisplay
import io.github.ycfeng.ocdeck.domain.model.OpenCodeLsp
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMcp
import io.github.ycfeng.ocdeck.domain.model.OpenCodePlugin
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette

@Composable
fun OpenCodeServerStatusButton(
    project: OpenCodeProjectState,
    serverConfig: ServerConfig?,
    serverVersion: String?,
    mcpActionName: String?,
    onOpenServers: () -> Unit,
    onMcpToggle: (OpenCodeMcp) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val popupOffsetY = with(LocalDensity.current) { ServerStatusPopupOffsetY.roundToPx() }
    val statusContentDescription = stringResource(R.string.a11y_status)
    val expansionStateDescription = stringResource(if (expanded) R.string.status_expanded else R.string.status_collapsed)

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .semantics {
                    contentDescription = statusContentDescription
                    role = Role.Button
                    stateDescription = expansionStateDescription
                }
                .clickable { expanded = !expanded },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .width(36.dp)
                    .height(28.dp),
                shape = RoundedCornerShape(6.dp),
                color = if (expanded) OpenCodePalette.PanelMuted else Color.Transparent,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_opencode_status),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (expanded) OpenCodePalette.Text else OpenCodePalette.IconMuted,
                    )
                }
            }
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, popupOffsetY),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                ServerStatusPopover(
                    project = project,
                    serverConfig = serverConfig,
                    serverVersion = serverVersion,
                    mcpActionName = mcpActionName,
                    onOpenServers = {
                        expanded = false
                        onOpenServers()
                    },
                    onMcpToggle = onMcpToggle,
                )
            }
        }
    }
}

@Composable
private fun ServerStatusPopover(
    project: OpenCodeProjectState,
    serverConfig: ServerConfig?,
    serverVersion: String?,
    mcpActionName: String?,
    onOpenServers: () -> Unit,
    onMcpToggle: (OpenCodeMcp) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(ServerStatusTab.Servers) }
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val width = (screenWidth - ServerStatusPopoverHorizontalClearance)
        .coerceAtLeast(0.dp)
        .coerceAtMost(ServerStatusPopoverWidth)
    val maxHeight = (configuration.screenHeightDp.dp - ServerStatusPopoverVerticalClearance)
        .coerceAtLeast(0.dp)
        .coerceAtMost(ServerStatusPopoverMaxHeight)
    val connectedMcpCount = project.mcps.count { it.status.equals("connected", ignoreCase = true) }

    Surface(
        modifier = Modifier.width(width),
        shape = RoundedCornerShape(10.dp),
        color = OpenCodePalette.Panel,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
        tonalElevation = 0.dp,
        shadowElevation = ServerStatusPopoverShadowElevation,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .horizontalScroll(rememberScrollState())
                    .selectableGroup()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ServerStatusTabButton(
                    label = stringResource(R.string.server_status_servers_label, if (serverConfig == null) 0 else 1),
                    selected = selectedTab == ServerStatusTab.Servers,
                    onClick = { selectedTab = ServerStatusTab.Servers },
                )
                ServerStatusTabButton(
                    label = countLabel(connectedMcpCount, "MCP"),
                    selected = selectedTab == ServerStatusTab.Mcp,
                    onClick = { selectedTab = ServerStatusTab.Mcp },
                )
                ServerStatusTabButton(
                    label = countLabel(project.lsps.size, "LSP"),
                    selected = selectedTab == ServerStatusTab.Lsp,
                    onClick = { selectedTab = ServerStatusTab.Lsp },
                )
                ServerStatusTabButton(
                    label = countLabel(project.plugins.size, stringResource(R.string.server_status_plugins_label)),
                    selected = selectedTab == ServerStatusTab.Plugins,
                    onClick = { selectedTab = ServerStatusTab.Plugins },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            ) {
                when (selectedTab) {
                    ServerStatusTab.Servers -> ServerStatusServersTab(
                        serverConfig = serverConfig,
                        serverVersion = serverVersion,
                        onOpenServers = onOpenServers,
                    )
                    ServerStatusTab.Mcp -> ServerStatusMcpTab(
                        mcps = project.mcps,
                        mcpActionName = mcpActionName,
                        onMcpToggle = onMcpToggle,
                    )
                    ServerStatusTab.Lsp -> ServerStatusLspTab(project.lsps)
                    ServerStatusTab.Plugins -> ServerStatusPluginsTab(project.plugins)
                }
            }
        }
    }
}

@Composable
private fun ServerStatusTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .selectable(
                selected = selected,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = if (selected) OpenCodePalette.Text else OpenCodePalette.MutedText,
            maxLines = 1,
        )
    }
}

@Composable
private fun ServerStatusServersTab(
    serverConfig: ServerConfig?,
    serverVersion: String?,
    onOpenServers: () -> Unit,
) {
    val safeServerUrl = serverConfig?.baseUrl?.let(::safeServerBaseUrlForDisplay)
    val serverDisplayName = when {
        safeServerUrl != null -> safeServerUrl.toServerDisplayName()
        serverConfig != null -> stringResource(R.string.servers_url_hidden)
        else -> stringResource(R.string.server_status_no_server)
    }
    val displayVersion = formatVersion(serverVersion)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenCodePalette.Panel, RoundedCornerShape(4.dp))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(Color.Transparent, RoundedCornerShape(6.dp))
                .clearAndSetSemantics {
                    contentDescription = listOfNotNull(serverDisplayName, displayVersion).joinToString(", ")
                }
                .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = serverDisplayName,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                    color = OpenCodePalette.Text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                displayVersion?.let { version ->
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                        color = OpenCodePalette.MutedText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_opencode_check_linear),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = OpenCodePalette.Text,
            )
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onOpenServers),
            shape = RoundedCornerShape(6.dp),
            color = OpenCodePalette.PanelMuted,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.server_status_manage_servers),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Medium),
                    color = OpenCodePalette.Text,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ServerStatusMcpTab(
    mcps: List<OpenCodeMcp>,
    mcpActionName: String?,
    onMcpToggle: (OpenCodeMcp) -> Unit,
) {
    if (mcps.isEmpty()) {
        ServerStatusEmptyText(stringResource(R.string.server_status_no_mcps))
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        mcps.forEach { mcp ->
            val pending = mcp.name == mcpActionName
            val needsAuth = mcp.status.equals("needs_auth", ignoreCase = true)
            val enabled = !pending && !needsAuth
            val checked = mcp.status.equals("connected", ignoreCase = true)
            val helper = mcpHelperText(mcp.status, pending)
            val mcpDescription = listOfNotNull(mcp.name, helper).joinToString(", ")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = mcpDescription
                    }
                    .toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = { onMcpToggle(mcp) },
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ServerStatusDot(status = mcp.status)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = mcp.name,
                        modifier = Modifier.clearAndSetSemantics { },
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                        color = if (enabled || needsAuth) OpenCodePalette.Text else OpenCodePalette.MutedText,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (helper != null) {
                        Text(
                            text = helper,
                            modifier = Modifier.clearAndSetSemantics { },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                            color = OpenCodePalette.MutedText,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                OpenCodeSwitch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

@Composable
private fun ServerStatusLspTab(lsps: List<OpenCodeLsp>) {
    if (lsps.isEmpty()) {
        ServerStatusEmptyText(stringResource(R.string.server_status_lsp_auto_detected))
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        lsps.forEach { lsp ->
            val lspName = lsp.name ?: lsp.id
            val statusLabel = lsp.status?.toStatusLabel()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clearAndSetSemantics {
                        contentDescription = listOfNotNull(lspName, statusLabel).joinToString(", ")
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ServerStatusDot(status = lsp.status)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = lspName,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                        color = OpenCodePalette.Text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    statusLabel?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = OpenCodePalette.MutedText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerStatusPluginsTab(plugins: List<OpenCodePlugin>) {
    if (plugins.isEmpty()) {
        ServerStatusEmptyText(stringResource(R.string.server_status_plugins_configured))
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        plugins.forEach { plugin ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clearAndSetSemantics { contentDescription = plugin.name }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ServerStatusDot(status = "connected")
                Text(
                    text = plugin.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                    color = OpenCodePalette.Text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ServerStatusEmptyText(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 25.2.sp),
            color = OpenCodePalette.MutedText,
        )
    }
}

@Composable
private fun ServerStatusDot(status: String?) {
    val normalized = status.orEmpty().lowercase()
    val color = when (normalized) {
        "connected" -> OpenCodePalette.Accent
        "failed", "error" -> OpenCodePalette.Danger
        "needs_auth", "needs_client_registration" -> OpenCodePalette.Warning
        else -> Color.Transparent
    }
    val border = when (normalized) {
        "connected", "failed", "error", "needs_auth", "needs_client_registration" -> null
        else -> BorderStroke(1.dp, OpenCodePalette.IconMuted)
    }
    Surface(
        modifier = Modifier.size(8.dp),
        shape = CircleShape,
        color = color,
        border = border,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {}
}

private enum class ServerStatusTab {
    Servers,
    Mcp,
    Lsp,
    Plugins,
}

private fun countLabel(count: Int, label: String): String = if (count > 0) "$count $label" else label

private fun String.toServerDisplayName(): String = removePrefix("http://")
    .removePrefix("https://")
    .trimEnd('/')
    .ifBlank { this }

private fun formatVersion(version: String?): String? = version
    ?.takeIf { it.isNotBlank() }
    ?.let { if (it.startsWith("v", ignoreCase = true)) it else "v$it" }

@Composable
private fun mcpHelperText(status: String?, pending: Boolean): String? {
    if (pending) return stringResource(R.string.server_status_updating)
    return when (status.orEmpty().lowercase()) {
        "needs_auth" -> stringResource(R.string.server_status_needs_auth)
        "needs_client_registration" -> stringResource(R.string.server_status_needs_client_registration)
        "failed" -> stringResource(R.string.server_status_failed)
        "disabled" -> stringResource(R.string.server_status_disabled)
        else -> null
    }
}

@Composable
private fun String.toStatusLabel(): String = when (lowercase()) {
    "connected" -> stringResource(R.string.server_status_connected)
    "error" -> stringResource(R.string.server_status_error)
    else -> this
}

private val ServerStatusPopoverWidth = 360.dp
private val ServerStatusPopupOffsetY = 48.dp
private val ServerStatusPopoverHorizontalClearance = 40.dp
private val ServerStatusPopoverVerticalClearance = 80.dp
private val ServerStatusPopoverMaxHeight = 560.dp
private val ServerStatusPopoverShadowElevation = 14.dp
