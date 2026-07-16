package io.github.ycfeng.ocdeck.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette
import kotlinx.coroutines.launch

@Composable
fun OpenCodeTopBar(
    navigationLabel: String? = null,
    navigationIconRes: Int? = null,
    navigationContentDescription: String? = navigationLabel,
    onNavigationClick: (() -> Unit)? = null,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = OpenCodePalette.Canvas) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Layout(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
                content = {
                    Row {
                        if (navigationLabel != null && onNavigationClick != null) {
                            if (navigationIconRes != null) {
                                OpenCodeHeaderIconButton(
                                    iconRes = navigationIconRes,
                                    contentDescription = navigationContentDescription ?: navigationLabel,
                                    onClick = onNavigationClick,
                                )
                            } else {
                                OpenCodeHeaderButton(text = navigationLabel, onClick = onNavigationClick)
                            }
                        }
                    }
                    if (title != null) {
                        Text(
                            text = title,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelLarge,
                            color = OpenCodePalette.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        Spacer(Modifier)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
                },
            ) { measurables, constraints ->
                val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                val navigation = measurables[0].measure(looseConstraints)
                val actionButtons = measurables[2].measure(looseConstraints)
                val sideWidth = maxOf(navigation.width, actionButtons.width)
                val titleWidth = (constraints.maxWidth - sideWidth * 2).coerceAtLeast(0)
                val titleContent = measurables[1].measure(
                    looseConstraints.copy(maxWidth = titleWidth),
                )

                layout(constraints.maxWidth, constraints.maxHeight) {
                    navigation.placeRelative(0, (constraints.maxHeight - navigation.height) / 2)
                    titleContent.placeRelative(
                        (constraints.maxWidth - titleContent.width) / 2,
                        (constraints.maxHeight - titleContent.height) / 2,
                    )
                    actionButtons.placeRelative(
                        constraints.maxWidth - actionButtons.width,
                        (constraints.maxHeight - actionButtons.height) / 2,
                    )
                }
            }
            HorizontalDivider(color = OpenCodePalette.Border)
        }
    }
}

@Composable
fun OpenCodeHeaderIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(32.dp)
                .height(24.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color.Transparent,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .then(iconModifier),
                    tint = if (enabled) OpenCodePalette.IconMuted else OpenCodePalette.IconMuted.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
fun OpenCodeRefreshIconButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val clickRotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val rotation = if (isRefreshing) {
        val transition = rememberInfiniteTransition(label = "refresh")
        val refreshingRotation by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = RefreshAnimationMillis, easing = LinearEasing),
            ),
            label = "refreshRotation",
        )
        refreshingRotation
    } else {
        clickRotation.value
    }

    OpenCodeHeaderIconButton(
        iconRes = R.drawable.ic_opencode_refresh,
        contentDescription = if (isRefreshing) stringResource(R.string.a11y_refreshing) else stringResource(R.string.a11y_refresh),
        onClick = {
            onClick()
            scope.launch {
                clickRotation.snapTo(0f)
                clickRotation.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(durationMillis = RefreshClickAnimationMillis, easing = LinearEasing),
                )
                clickRotation.snapTo(0f)
            }
        },
        modifier = modifier,
        iconModifier = Modifier.graphicsLayer { rotationZ = rotation },
        enabled = enabled,
    )
}

@Composable
fun OpenCodeHeaderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.height(32.dp),
            shape = RoundedCornerShape(6.dp),
            color = Color.Transparent,
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) OpenCodePalette.Text else OpenCodePalette.MutedText,
                    maxLines = 1,
                )
            }
        }
    }
}

private const val RefreshAnimationMillis = 900
private const val RefreshClickAnimationMillis = 520
private const val SwitchAnimationMillis = 180

@Composable
fun OpenCodeCard(
    modifier: Modifier = Modifier,
    color: Color = OpenCodePalette.Panel,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = color,
        border = BorderStroke(1.dp, OpenCodePalette.Border),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
fun OpenCodePill(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = if (selected) OpenCodePalette.PanelMuted else OpenCodePalette.Panel,
        border = BorderStroke(1.dp, if (selected) OpenCodePalette.SelectionBorder else OpenCodePalette.Border),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 28.dp)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
fun OpenCodeSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val targetTrackColor = when {
        checked -> OpenCodePalette.SelectionBorder
        enabled -> OpenCodePalette.Panel
        else -> OpenCodePalette.Panel.copy(alpha = 0.72f)
    }
    val targetTrackBorderColor = when {
        checked -> OpenCodePalette.SelectionBorder
        enabled -> OpenCodePalette.IconMuted
        else -> OpenCodePalette.Border.copy(alpha = 0.72f)
    }
    val targetThumbBorderColor = when {
        checked -> OpenCodePalette.SelectionBorder
        enabled -> OpenCodePalette.IconMuted
        else -> OpenCodePalette.Border.copy(alpha = 0.72f)
    }
    val trackColor by animateColorAsState(
        targetValue = targetTrackColor,
        animationSpec = tween(durationMillis = SwitchAnimationMillis),
        label = "switchTrackColor",
    )
    val trackBorderColor by animateColorAsState(
        targetValue = targetTrackBorderColor,
        animationSpec = tween(durationMillis = SwitchAnimationMillis),
        label = "switchTrackBorderColor",
    )
    val thumbBorderColor by animateColorAsState(
        targetValue = targetThumbBorderColor,
        animationSpec = tween(durationMillis = SwitchAnimationMillis),
        label = "switchThumbBorderColor",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 0.dp,
        animationSpec = tween(durationMillis = SwitchAnimationMillis),
        label = "switchThumbOffset",
    )
    val toggleModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(toggleModifier),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(50.dp)
                .height(28.dp),
            shape = RoundedCornerShape(5.dp),
            color = trackColor,
            border = BorderStroke(1.dp, trackBorderColor),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(28.dp)
                    .padding(2.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Surface(
                    modifier = Modifier
                        .offset(x = thumbOffset)
                        .size(24.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = OpenCodePalette.Panel,
                    border = BorderStroke(1.dp, thumbBorderColor),
                    tonalElevation = 0.dp,
                    shadowElevation = 2.dp,
                ) {}
            }
        }
    }
}

@Composable
fun OpenCodeChipButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodePill(selected = selected) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) OpenCodePalette.Text else OpenCodePalette.MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun OpenCodePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        modifier = modifier
            .sizeIn(minWidth = 48.dp)
            .heightIn(min = 48.dp),
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = OpenCodePalette.BorderStrong,
            contentColor = OpenCodePalette.OnStrong,
            disabledContainerColor = OpenCodePalette.PanelMuted,
            disabledContentColor = OpenCodePalette.MutedText,
        ),
        border = BorderStroke(1.dp, if (enabled) OpenCodePalette.BorderStrong else OpenCodePalette.Border),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun OpenCodeSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        modifier = modifier
            .sizeIn(minWidth = 48.dp)
            .heightIn(min = 48.dp),
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = OpenCodePalette.Panel,
            contentColor = OpenCodePalette.Text,
            disabledContentColor = OpenCodePalette.MutedText,
        ),
        border = BorderStroke(1.dp, OpenCodePalette.Border),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun OpenCodePlainTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = 40.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    singleLine: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(color = OpenCodePalette.Text),
) {
    val effectiveMinHeight = if (singleLine && minHeight < 48.dp) 48.dp else minHeight
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = openCodeTextFieldCursorBrush(),
        decorationBox = { innerTextField ->
            OpenCodePlainTextFieldDecoration(
                showPlaceholder = value.isBlank(),
                placeholder = placeholder,
                minHeight = effectiveMinHeight,
                contentPadding = contentPadding,
                contentAlignment = contentAlignment,
                textStyle = textStyle,
                innerTextField = innerTextField,
            )
        },
    )
}

@Composable
fun OpenCodePlainTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = 40.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    singleLine: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium.copy(color = OpenCodePalette.Text),
) {
    val effectiveMinHeight = if (singleLine && minHeight < 48.dp) 48.dp else minHeight
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = openCodeTextFieldCursorBrush(),
        decorationBox = { innerTextField ->
            OpenCodePlainTextFieldDecoration(
                showPlaceholder = value.text.isBlank(),
                placeholder = placeholder,
                minHeight = effectiveMinHeight,
                contentPadding = contentPadding,
                contentAlignment = contentAlignment,
                textStyle = textStyle,
                innerTextField = innerTextField,
            )
        },
    )
}

@Composable
private fun OpenCodePlainTextFieldDecoration(
    showPlaceholder: Boolean,
    placeholder: String,
    minHeight: Dp,
    contentPadding: PaddingValues,
    contentAlignment: Alignment,
    textStyle: TextStyle,
    innerTextField: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .background(OpenCodePalette.Panel, RoundedCornerShape(8.dp))
            .border(1.dp, OpenCodePalette.ControlBorder, RoundedCornerShape(8.dp))
            .padding(contentPadding),
        contentAlignment = contentAlignment,
    ) {
        if (showPlaceholder) {
            Text(
                text = placeholder,
                style = textStyle,
                color = OpenCodePalette.MutedText,
            )
        }
        innerTextField()
    }
}

@Composable
fun OpenCodeSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = OpenCodePalette.MutedText,
    )
}
