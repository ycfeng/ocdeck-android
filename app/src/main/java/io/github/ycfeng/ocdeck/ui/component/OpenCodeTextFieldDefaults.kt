package io.github.ycfeng.ocdeck.ui.component

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette

@Composable
@ReadOnlyComposable
fun openCodeTextFieldCursorColor(): Color = if (OpenCodePalette.Canvas.luminance() < 0.5f) {
    Color.White
} else {
    OpenCodePalette.BorderStrong
}

@Composable
@ReadOnlyComposable
fun openCodeTextFieldCursorBrush(): Brush = SolidColor(openCodeTextFieldCursorColor())

@Composable
fun openCodeOutlinedTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    cursorColor = openCodeTextFieldCursorColor(),
    focusedBorderColor = OpenCodePalette.Accent,
    unfocusedBorderColor = OpenCodePalette.ControlBorder,
    disabledBorderColor = OpenCodePalette.ControlBorder,
    errorBorderColor = OpenCodePalette.Danger,
)
