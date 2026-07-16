package io.github.ycfeng.ocdeck.ui.theme

import androidx.compose.ui.graphics.Color
import io.github.ycfeng.ocdeck.ui.component.SessionRunningIndicatorMinimumAlpha
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeContrastTest {
    @Test
    fun lightPaletteMeetsContrastThresholds() {
        assertPaletteContrast("light", OpenCodeLightPalette)
    }

    @Test
    fun darkPaletteMeetsContrastThresholds() {
        assertPaletteContrast("dark", OpenCodeDarkPalette)
    }

    @Test
    fun materialPrimaryUsesReadableAccent() {
        assertEquals(OpenCodeLightPalette.Accent, OpenCodeLightColors.primary)
        assertEquals(OpenCodeDarkPalette.Accent, OpenCodeDarkColors.primary)
        assertContrast(
            "light primary on canvas",
            OpenCodeLightColors.primary,
            OpenCodeLightPalette.Canvas,
            TextContrast,
        )
        assertContrast(
            "dark primary on panel",
            OpenCodeDarkColors.primary,
            OpenCodeDarkPalette.Panel,
            TextContrast,
        )
        assertContrast(
            "light onPrimary",
            OpenCodeLightColors.onPrimary,
            OpenCodeLightColors.primary,
            TextContrast,
        )
        assertContrast(
            "dark onPrimary",
            OpenCodeDarkColors.onPrimary,
            OpenCodeDarkColors.primary,
            TextContrast,
        )
    }

    private fun assertPaletteContrast(name: String, colors: OpenCodeColors) {
        val canvasAndPanel = listOf(
            "canvas" to colors.Canvas,
            "panel" to colors.Panel,
        )
        val readableText = listOf(
            "text" to colors.Text,
            "mutedText" to colors.MutedText,
            "faintText" to colors.FaintText,
            "messageMeta" to colors.MessageMeta,
            "patchAdd" to colors.PatchAdd,
            "patchDelete" to colors.PatchDelete,
            "patchModified" to colors.PatchModified,
            "accent" to colors.Accent,
            "danger" to colors.Danger,
        )
        canvasAndPanel.forEach { (backgroundName, background) ->
            readableText.forEach { (foregroundName, foreground) ->
                assertContrast(
                    "$name $foregroundName on $backgroundName",
                    foreground,
                    background,
                    TextContrast,
                )
            }
        }

        listOf(
            "mutedText" to colors.MutedText,
            "faintText" to colors.FaintText,
            "messageMeta" to colors.MessageMeta,
        ).forEach { (foregroundName, foreground) ->
            assertContrast(
                "$name $foregroundName on panelMuted",
                foreground,
                colors.PanelMuted,
                TextContrast,
            )
        }
        assertContrast("$name mutedText on surfaceActive", colors.MutedText, colors.SurfaceActive, TextContrast)
        assertContrast("$name mutedText on accentSoft", colors.MutedText, colors.AccentSoft, TextContrast)
        assertContrast("$name selected accent on panelMuted", colors.Accent, colors.PanelMuted, TextContrast)
        assertContrast("$name selected accent on accentSoft", colors.Accent, colors.AccentSoft, TextContrast)
        assertContrast("$name text on alternate table row", colors.Text, colors.TableRowAlternate, TextContrast)
        assertContrast("$name mutedText on alternate table row", colors.MutedText, colors.TableRowAlternate, TextContrast)
        assertContrast("$name subagent text", colors.Text, colors.SubagentBackground, TextContrast)
        assertContrast("$name subagent agent", colors.SubagentAgent, colors.SubagentBackground, TextContrast)
        assertContrast("$name strong content", colors.OnStrong, colors.BorderStrong, TextContrast)
        assertContrast("$name warning content", colors.OnWarning, colors.Warning, TextContrast)

        val graphicalBackgrounds = listOf(
            "canvas" to colors.Canvas,
            "panel" to colors.Panel,
            "panelMuted" to colors.PanelMuted,
            "surfaceActive" to colors.SurfaceActive,
        )
        graphicalBackgrounds.forEach { (backgroundName, background) ->
            assertContrast("$name muted icon on $backgroundName", colors.IconMuted, background, GraphicContrast)
            assertContrast("$name selected border on $backgroundName", colors.SelectionBorder, background, GraphicContrast)
        }
        assertContrast("$name text field border on canvas", colors.ControlBorder, colors.Canvas, GraphicContrast)
        assertContrast("$name text field border on panel", colors.ControlBorder, colors.Panel, GraphicContrast)
        assertContrast("$name model search border on panelMuted", colors.ControlBorder, colors.PanelMuted, GraphicContrast)
        canvasAndPanel.forEach { (backgroundName, background) ->
            assertContrast("$name success status on $backgroundName", colors.Accent, background, GraphicContrast)
            assertContrast("$name warning status on $backgroundName", colors.Warning, background, GraphicContrast)
            assertContrast("$name error status on $backgroundName", colors.Danger, background, GraphicContrast)
        }
        listOf(
            "running build" to colors.RunningBuild,
            "running plan" to colors.RunningPlan,
        ).forEach { (indicatorName, indicatorColor) ->
            assertContrast("$name $indicatorName on panel", indicatorColor, colors.Panel, GraphicContrast)
            assertContrast(
                "$name $indicatorName minimum alpha on panel",
                indicatorColor.copy(alpha = SessionRunningIndicatorMinimumAlpha),
                colors.Panel,
                GraphicContrast,
            )
        }

        val contextColors = listOf(
            "contextInput" to colors.ContextInput,
            "contextOutput" to colors.ContextOutput,
            "contextReasoning" to colors.ContextReasoning,
            "contextOther" to colors.ContextOther,
        )
        listOf("panel" to colors.Panel, "panelMuted" to colors.PanelMuted).forEach { (backgroundName, background) ->
            contextColors.forEach { (foregroundName, foreground) ->
                assertContrast(
                    "$name $foregroundName on $backgroundName",
                    foreground,
                    background,
                    GraphicContrast,
                )
            }
        }

        val syntaxColors = listOf(
            "syntaxDefault" to colors.SyntaxDefault,
            "syntaxComment" to colors.SyntaxComment,
            "syntaxInline" to colors.SyntaxInline,
            "syntaxBlue" to colors.SyntaxBlue,
            "syntaxTeal" to colors.SyntaxTeal,
            "syntaxText" to colors.Text,
            "syntaxDanger" to colors.Danger,
        )
        listOf("panel" to colors.Panel, "panelMuted" to colors.PanelMuted).forEach { (backgroundName, background) ->
            syntaxColors.forEach { (foregroundName, foreground) ->
                assertContrast(
                    "$name $foregroundName on $backgroundName",
                    foreground,
                    background,
                    TextContrast,
                )
            }
        }
        assertContrast("$name inline code on canvas", colors.SyntaxInline, colors.Canvas, TextContrast)

        val brightestImage = Color.White
        val maskedImage = composite(colors.AttachmentScrim, brightestImage)
        assertContrast("$name attachment title", colors.OnStrong, maskedImage, TextContrast)
    }

    private fun assertContrast(name: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            "$name contrast ${"%.3f".format(ratio)} is below $minimum " +
                "(${foreground.toHex()} on ${background.toHex()})",
            ratio >= minimum,
        )
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val opaqueForeground = composite(foreground, background)
        val lighter = max(relativeLuminance(opaqueForeground), relativeLuminance(background))
        val darker = min(relativeLuminance(opaqueForeground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * color.red.toDouble().linearized() +
            0.7152 * color.green.toDouble().linearized() +
            0.0722 * color.blue.toDouble().linearized()

    private fun Double.linearized(): Double = if (this <= 0.04045) {
        this / 12.92
    } else {
        ((this + 0.055) / 1.055).pow(2.4)
    }

    private fun composite(foreground: Color, background: Color): Color {
        val alpha = foreground.alpha
        return Color(
            red = foreground.red * alpha + background.red * (1f - alpha),
            green = foreground.green * alpha + background.green * (1f - alpha),
            blue = foreground.blue * alpha + background.blue * (1f - alpha),
            alpha = 1f,
        )
    }

    private fun Color.toHex(): String = "#%02X%02X%02X%02X".format(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )

    private companion object {
        const val TextContrast = 4.5
        const val GraphicContrast = 3.0
    }
}
