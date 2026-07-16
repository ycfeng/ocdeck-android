package io.github.ycfeng.ocdeck.ui.component

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette

@Composable
fun OpenCodeConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    isConfirming: Boolean = false,
    errorMessage: String? = null,
    confirmingText: String? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OpenCodeDialog(
        title = title,
        isBusy = isConfirming,
        onDismiss = onDismiss,
    ) {
        Text(
            text = message,
            modifier = Modifier.fillMaxWidth(),
            color = OpenCodePalette.Text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                lineHeight = 19.5.sp,
                fontWeight = FontWeight.Normal,
            ),
        )
        errorMessage?.let { error ->
            Text(
                text = error,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
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
                enabled = !isConfirming,
                onClick = onDismiss,
            )
            OpenCodeDialogButton(
                text = if (isConfirming) {
                    confirmingText ?: stringResource(R.string.action_processing)
                } else {
                    confirmText
                },
                primary = true,
                enabled = !isConfirming,
                onClick = onConfirm,
            )
        }
    }
}

@Composable
fun OpenCodeDialog(
    title: String,
    isBusy: Boolean = false,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        val maxDialogHeight = (LocalConfiguration.current.screenHeightDp.dp - DialogVerticalMargin * 2)
            .coerceAtLeast(1.dp)
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0.3f)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .heightIn(max = maxDialogHeight),
                shape = RoundedCornerShape(10.dp),
                color = OpenCodePalette.Panel,
                border = BorderStroke(1.dp, OpenCodePalette.Border),
                shadowElevation = 14.dp,
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            color = OpenCodePalette.Text,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp,
                                lineHeight = 28.8.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CloseButton(
                            enabled = !isBusy,
                            onClick = onDismiss,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 10.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

private val DialogVerticalMargin = 16.dp

@Composable
private fun CloseButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(Color.Transparent, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_opencode_close),
                contentDescription = stringResource(R.string.a11y_close),
                modifier = Modifier.size(16.dp),
                tint = if (enabled) OpenCodePalette.FaintText else OpenCodePalette.FaintText.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
fun OpenCodeDialogButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Surface(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(6.dp),
        color = if (primary && enabled) OpenCodePalette.BorderStrong else Color.Transparent,
        border = BorderStroke(1.dp, if (primary) OpenCodePalette.Border else Color.Transparent),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = when {
                    primary && enabled -> Color.White
                    enabled -> OpenCodePalette.Text
                    else -> OpenCodePalette.FaintText
                },
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Medium,
                ),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
