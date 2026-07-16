package io.github.ycfeng.ocdeck.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.store.NotificationDotKind
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette

@Composable
fun OpenCodeNotificationDot(
    kind: NotificationDotKind,
    modifier: Modifier = Modifier,
    size: Dp = 6.dp,
) {
    val color = kind.colorOrNull() ?: return
    Box(
        modifier = modifier
            .size(size)
            .background(color = color, shape = CircleShape),
    )
}

@Composable
fun NotificationDotKind.notificationStateDescription(): String? = when (this) {
    NotificationDotKind.None -> null
    NotificationDotKind.Unseen -> stringResource(R.string.a11y_notification_unseen)
    NotificationDotKind.Error -> stringResource(R.string.a11y_notification_error)
    NotificationDotKind.Permission -> stringResource(R.string.a11y_notification_permission)
}

@Composable
private fun NotificationDotKind.colorOrNull(): Color? = when (this) {
    NotificationDotKind.None -> null
    NotificationDotKind.Unseen -> OpenCodePalette.Accent
    NotificationDotKind.Error -> OpenCodePalette.Danger
    NotificationDotKind.Permission -> OpenCodePalette.Warning
}
