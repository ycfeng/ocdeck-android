package io.github.ycfeng.ocdeck.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.ycfeng.ocdeck.data.settings.AppColorSchemePreference

object OpenCodePalette {
    val Canvas: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.Canvas
    val Panel: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.Panel
    val PanelMuted: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.PanelMuted
    val SurfaceActive: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SurfaceActive
    val Border: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.Border
    val BorderStrong: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.BorderStrong
    val ControlBorder: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.ControlBorder
    val SelectionBorder: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SelectionBorder
    val Text: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.Text
    val MutedText: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.MutedText
    val FaintText: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.FaintText
    val IconMuted: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.IconMuted
    val IconStrong: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.IconStrong
    val IconStrongDisabled: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.IconStrongDisabled
    val IconInvert: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.IconInvert
    val OnStrong: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.OnStrong
    val Accent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.Accent
    val AccentSoft: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.AccentSoft
    val SelectionHandle: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SelectionHandle
    val SelectionBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SelectionBackground
    val Warning: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.Warning
    val OnWarning: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.OnWarning
    val Danger: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.Danger
    val MessageMeta: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.MessageMeta
    val PatchAdd: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.PatchAdd
    val PatchDelete: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.PatchDelete
    val PatchModified: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.PatchModified
    val ContextInput: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.ContextInput
    val ContextOutput: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.ContextOutput
    val ContextReasoning: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.ContextReasoning
    val ContextOther: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.ContextOther
    val SyntaxDefault: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SyntaxDefault
    val SyntaxComment: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SyntaxComment
    val SyntaxInline: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SyntaxInline
    val SyntaxBlue: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SyntaxBlue
    val SyntaxTeal: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SyntaxTeal
    val TableRowAlternate: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.TableRowAlternate
    val AttachmentScrim: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.AttachmentScrim
    val SubagentBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SubagentBackground
    val SubagentAgent: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.SubagentAgent
    val RunningBuild: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.RunningBuild
    val RunningPlan: Color
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCodeColors.current.RunningPlan
}

@Immutable
data class OpenCodeColors(
    val Canvas: Color,
    val Panel: Color,
    val PanelMuted: Color,
    val SurfaceActive: Color,
    val Border: Color,
    val BorderStrong: Color,
    val ControlBorder: Color,
    val SelectionBorder: Color,
    val Text: Color,
    val MutedText: Color,
    val FaintText: Color,
    val IconMuted: Color,
    val IconStrong: Color,
    val IconStrongDisabled: Color,
    val IconInvert: Color,
    val OnStrong: Color,
    val Accent: Color,
    val AccentSoft: Color,
    val SelectionHandle: Color,
    val SelectionBackground: Color,
    val Warning: Color,
    val OnWarning: Color,
    val Danger: Color,
    val MessageMeta: Color,
    val PatchAdd: Color,
    val PatchDelete: Color,
    val PatchModified: Color,
    val ContextInput: Color,
    val ContextOutput: Color,
    val ContextReasoning: Color,
    val ContextOther: Color,
    val SyntaxDefault: Color,
    val SyntaxComment: Color,
    val SyntaxInline: Color,
    val SyntaxBlue: Color,
    val SyntaxTeal: Color,
    val TableRowAlternate: Color,
    val AttachmentScrim: Color,
    val SubagentBackground: Color,
    val SubagentAgent: Color,
    val RunningBuild: Color,
    val RunningPlan: Color,
)

internal val OpenCodeLightPalette = OpenCodeColors(
    Canvas = Color(0xFFFAFAFA),
    Panel = Color(0xFFFFFFFF),
    PanelMuted = Color(0xFFF4F4F2),
    SurfaceActive = Color(0xFFE2E2E2),
    Border = Color(0xFFDBDBDB),
    BorderStrong = Color(0xFF1D1917),
    ControlBorder = Color(0xFF77736F),
    SelectionBorder = Color(0xFF77736F),
    Text = Color(0xFF171717),
    MutedText = Color(0xFF646464),
    FaintText = Color(0xFF646464),
    IconMuted = Color(0xFF808080),
    IconStrong = Color(0xFF1D1917),
    IconStrongDisabled = Color(0xFFADACAB),
    IconInvert = Color(0xFFFFFFFF),
    OnStrong = Color(0xFFFFFFFF),
    Accent = Color(0xFF55751F),
    AccentSoft = Color(0xFFEAF5D6),
    SelectionHandle = Color(0xFF55751F),
    SelectionBackground = Color(0x3355751F),
    Warning = Color(0xFF9A5C00),
    OnWarning = Color(0xFFFFFFFF),
    Danger = Color(0xFFB42318),
    MessageMeta = Color(0xFF646464),
    PatchAdd = Color(0xFF3F6B35),
    PatchDelete = Color(0xFFB42318),
    PatchModified = Color(0xFF646464),
    ContextInput = Color(0xFF3F6B35),
    ContextOutput = Color(0xFF76527F),
    ContextReasoning = Color(0xFF4F6678),
    ContextOther = Color(0xFF6B6B6B),
    SyntaxDefault = Color(0xFF656565),
    SyntaxComment = Color(0xFF656565),
    SyntaxInline = Color(0xFF006D66),
    SyntaxBlue = Color(0xFF285EA8),
    SyntaxTeal = Color(0xFF006F73),
    TableRowAlternate = Color(0xFFF9F9F8),
    AttachmentScrim = Color(0xAD000000),
    SubagentBackground = Color(0xFFF8F8F8),
    SubagentAgent = Color(0xFF006F73),
    RunningBuild = Color(0xFF1D4ED8),
    RunningPlan = Color(0xFF7E3A8F),
)

internal val OpenCodeDarkPalette = OpenCodeColors(
    Canvas = Color(0xFF080808),
    Panel = Color(0xFF191919),
    PanelMuted = Color(0xFF232323),
    SurfaceActive = Color(0xFF282727),
    Border = Color(0xFF282828),
    BorderStrong = Color(0xFF3A3A3A),
    ControlBorder = Color(0xFF7F8178),
    SelectionBorder = Color(0xFF7F8178),
    Text = Color(0xFFEDEDED),
    MutedText = Color(0xFFA0A0A0),
    FaintText = Color(0xFFA0A0A0),
    IconMuted = Color(0xFF858585),
    IconStrong = Color(0xFFEDE8E4),
    IconStrongDisabled = Color(0xFF3E3E3E),
    IconInvert = Color(0xFF121212),
    OnStrong = Color(0xFFFFFFFF),
    Accent = Color(0xFFA3C75C),
    AccentSoft = Color(0xFF1D2A12),
    SelectionHandle = Color(0xFF5FD2D6),
    SelectionBackground = Color(0x665FD2D6),
    Warning = Color(0xFFD6A356),
    OnWarning = Color(0xFF171717),
    Danger = Color(0xFFF97066),
    MessageMeta = Color(0xFFA0A0A0),
    PatchAdd = Color(0xFF9AC58C),
    PatchDelete = Color(0xFFF97066),
    PatchModified = Color(0xFFA0A0A0),
    ContextInput = Color(0xFF9AC58C),
    ContextOutput = Color(0xFFC3A0CC),
    ContextReasoning = Color(0xFF8FAEC4),
    ContextOther = Color(0xFFA3A3A3),
    SyntaxDefault = Color(0xFFB8B8B8),
    SyntaxComment = Color(0xFF9A9A9A),
    SyntaxInline = Color(0xFF78D5C9),
    SyntaxBlue = Color(0xFF9ABEF2),
    SyntaxTeal = Color(0xFF78C7CB),
    TableRowAlternate = Color(0xFF141414),
    AttachmentScrim = Color(0xAD000000),
    SubagentBackground = Color(0xFF151515),
    SubagentAgent = Color(0xFF78C7CB),
    RunningBuild = Color(0xFF8CB4FF),
    RunningPlan = Color(0xFFD69BDD),
)

private val LocalOpenCodeColors = staticCompositionLocalOf { OpenCodeLightPalette }

internal val OpenCodeLightColors = lightColorScheme(
    primary = OpenCodeLightPalette.Accent,
    onPrimary = OpenCodeLightPalette.IconInvert,
    secondary = OpenCodeLightPalette.Accent,
    onSecondary = OpenCodeLightPalette.IconInvert,
    background = OpenCodeLightPalette.Canvas,
    onBackground = OpenCodeLightPalette.Text,
    surface = OpenCodeLightPalette.Panel,
    onSurface = OpenCodeLightPalette.Text,
    surfaceVariant = OpenCodeLightPalette.PanelMuted,
    onSurfaceVariant = OpenCodeLightPalette.MutedText,
    outline = OpenCodeLightPalette.Border,
    error = OpenCodeLightPalette.Danger,
    onError = OpenCodeLightPalette.OnStrong,
)

internal val OpenCodeDarkColors = darkColorScheme(
    primary = OpenCodeDarkPalette.Accent,
    onPrimary = OpenCodeDarkPalette.IconInvert,
    secondary = OpenCodeDarkPalette.Accent,
    onSecondary = OpenCodeDarkPalette.Canvas,
    background = OpenCodeDarkPalette.Canvas,
    onBackground = OpenCodeDarkPalette.Text,
    surface = OpenCodeDarkPalette.Panel,
    onSurface = OpenCodeDarkPalette.Text,
    surfaceVariant = OpenCodeDarkPalette.PanelMuted,
    onSurfaceVariant = OpenCodeDarkPalette.MutedText,
    outline = OpenCodeDarkPalette.Border,
    error = OpenCodeDarkPalette.Danger,
    onError = OpenCodeDarkPalette.Canvas,
)

private val OpenCodeTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun OpenCodeTheme(
    colorSchemePreference: AppColorSchemePreference = AppColorSchemePreference.System,
    content: @Composable () -> Unit,
) {
    val systemDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    val darkTheme = when (colorSchemePreference) {
        AppColorSchemePreference.System -> systemDarkTheme
        AppColorSchemePreference.Light -> false
        AppColorSchemePreference.Dark -> true
    }
    val palette = if (darkTheme) OpenCodeDarkPalette else OpenCodeLightPalette
    val colorScheme = if (darkTheme) OpenCodeDarkColors else OpenCodeLightColors
    val textSelectionColors = TextSelectionColors(
        handleColor = palette.SelectionHandle,
        backgroundColor = palette.SelectionBackground,
    )

    ApplySystemBars(palette, darkTheme)

    CompositionLocalProvider(
        LocalOpenCodeColors provides palette,
        LocalTextSelectionColors provides textSelectionColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OpenCodeTypography,
            content = content,
        )
    }
}

@Composable
private fun ApplySystemBars(palette: OpenCodeColors, darkTheme: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.statusBarColor = palette.Canvas.toArgb()
        window.navigationBarColor = palette.Canvas.toArgb()
        @Suppress("DEPRECATION")
        view.systemUiVisibility = if (darkTheme) {
            view.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            val navigationFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                0
            }
            view.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or navigationFlag
        }
    }
}
