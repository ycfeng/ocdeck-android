package io.github.ycfeng.ocdeck.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.ui.theme.OpenCodePalette

@Composable
fun OpenCodeSessionRunningIndicator(
    agent: String?,
    modifier: Modifier = Modifier,
) {
    val defaultColor = OpenCodePalette.Text
    val runningContentDescription = stringResource(R.string.session_running)
    Box(
        modifier = modifier
            .size(24.dp)
            .semantics { contentDescription = runningContentDescription },
        contentAlignment = Alignment.Center,
    ) {
        RunningSessionSpinner(color = agent.runningSpinnerColor(defaultColor))
    }
}

fun String?.isOpenCodeWorkingSessionStatus(): Boolean = !isNullOrBlank() && !equals("idle", ignoreCase = true)

@Composable
private fun RunningSessionSpinner(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "session-running-spinner")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "session-running-spinner-progress",
    )

    Canvas(modifier = modifier.size(15.dp)) {
        val unit = size.minDimension / 15f
        val dotSize = 3f * unit
        val step = 4f * unit
        val radius = 1f * unit

        for (index in 0 until 16) {
            if (index.isHiddenSpinnerDot()) continue

            val outerDot = !index.isCenterSpinnerDot()
            drawRoundRect(
                color = color.copy(alpha = spinnerDotAlpha(index, outerDot, progress)),
                topLeft = Offset(
                    x = index % 4 * step,
                    y = index / 4 * step,
                ),
                size = Size(dotSize, dotSize),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

private val SpinnerPulseOffsets = floatArrayOf(
    0.00f, 0.15f, 0.72f, 0.00f,
    0.44f, 0.03f, 0.57f, 0.28f,
    0.81f, 0.36f, 0.12f, 0.65f,
    0.00f, 0.51f, 0.91f, 0.00f,
)

private val SpinnerPulseSpeeds = floatArrayOf(
    1.00f, 0.92f, 1.17f, 1.00f,
    1.08f, 0.86f, 1.21f, 0.97f,
    1.14f, 1.03f, 0.89f, 1.24f,
    1.00f, 0.95f, 1.11f, 1.00f,
)

private fun spinnerDotAlpha(index: Int, outerDot: Boolean, progress: Float): Float {
    val phase = (progress * SpinnerPulseSpeeds[index] + SpinnerPulseOffsets[index]) % 1f
    val pulse = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
    val minAlpha = if (outerDot) SessionRunningIndicatorMinimumAlpha else 0.82f
    val maxAlpha = 1f
    return minAlpha + (maxAlpha - minAlpha) * pulse
}

private fun Int.isHiddenSpinnerDot(): Boolean = this == 0 || this == 3 || this == 12 || this == 15

private fun Int.isCenterSpinnerDot(): Boolean = this == 5 || this == 6 || this == 9 || this == 10

@Composable
private fun String?.runningSpinnerColor(defaultColor: Color): Color = when (this?.lowercase()) {
    "plan" -> OpenCodePalette.RunningPlan
    "build" -> OpenCodePalette.RunningBuild
    else -> defaultColor
}

internal const val SessionRunningIndicatorMinimumAlpha = 0.72f
