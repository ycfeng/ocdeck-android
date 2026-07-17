package io.github.ycfeng.ocdeck.ui.component

import androidx.compose.animation.core.CubicBezierEasing
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
import kotlin.math.abs

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
    val elapsedMillis by transition.animateFloat(
        initialValue = 0f,
        targetValue = SessionRunningIndicatorAnimationLoopMillis.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = SessionRunningIndicatorAnimationLoopMillis,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "session-running-spinner-elapsed",
    )

    Canvas(modifier = modifier.size(15.dp)) {
        val unit = size.minDimension / 15f
        val dotSize = 3f * unit
        val step = 4f * unit
        val radius = 1f * unit

        for (index in 0 until 16) {
            if (index.isHiddenSpinnerDot()) continue

            val frame = sessionRunningIndicatorDotFrame(index, elapsedMillis)
            val animatedDotSize = dotSize * frame.scale
            val dotInset = (dotSize - animatedDotSize) / 2f
            drawRoundRect(
                color = color.copy(alpha = frame.alpha),
                topLeft = Offset(
                    x = index % 4 * step + dotInset,
                    y = index / 4 * step + dotInset,
                ),
                size = Size(animatedDotSize, animatedDotSize),
                cornerRadius = CornerRadius(radius * frame.scale, radius * frame.scale),
            )
        }
    }
}

internal data class SessionRunningIndicatorDotMotion(
    val durationMillis: Int,
    val phaseOffsetMillis: Int,
)

internal data class SessionRunningIndicatorDotFrame(
    val alpha: Float,
    val scale: Float,
)

// Every duration divides the shared timeline so restart returns each dot to the same phase.
internal val SessionRunningIndicatorDotMotions = listOf(
    SessionRunningIndicatorDotMotion(1_000, 0),
    SessionRunningIndicatorDotMotion(1_200, 150),
    SessionRunningIndicatorDotMotion(1_500, 900),
    SessionRunningIndicatorDotMotion(1_000, 0),
    SessionRunningIndicatorDotMotion(2_000, 600),
    SessionRunningIndicatorDotMotion(1_200, 0),
    SessionRunningIndicatorDotMotion(1_500, 750),
    SessionRunningIndicatorDotMotion(1_000, 250),
    SessionRunningIndicatorDotMotion(1_500, 1_200),
    SessionRunningIndicatorDotMotion(2_000, 400),
    SessionRunningIndicatorDotMotion(1_200, 1_000),
    SessionRunningIndicatorDotMotion(1_500, 150),
    SessionRunningIndicatorDotMotion(1_000, 0),
    SessionRunningIndicatorDotMotion(1_000, 700),
    SessionRunningIndicatorDotMotion(2_000, 1_450),
    SessionRunningIndicatorDotMotion(1_000, 0),
)

internal fun sessionRunningIndicatorDotFrame(
    index: Int,
    elapsedMillis: Float,
): SessionRunningIndicatorDotFrame {
    val motion = SessionRunningIndicatorDotMotions[index]
    val phase = ((elapsedMillis + motion.phaseOffsetMillis) % motion.durationMillis) / motion.durationMillis
    val linearPulse = 1f - abs(phase * 2f - 1f)
    val pulse = SessionRunningIndicatorPulseEasing.transform(linearPulse.coerceIn(0f, 1f))
    val centerDot = index.isCenterSpinnerDot()
    val minAlpha = if (centerDot) SessionRunningIndicatorCenterMinimumAlpha else SessionRunningIndicatorMinimumAlpha
    val maxAlpha = if (centerDot) 1f else SessionRunningIndicatorOuterMaximumAlpha
    return SessionRunningIndicatorDotFrame(
        alpha = minAlpha + (maxAlpha - minAlpha) * pulse,
        scale = SessionRunningIndicatorMinimumScale + (1f - SessionRunningIndicatorMinimumScale) * pulse,
    )
}

internal fun Int.isHiddenSpinnerDot(): Boolean = this == 0 || this == 3 || this == 12 || this == 15

internal fun Int.isCenterSpinnerDot(): Boolean = this == 5 || this == 6 || this == 9 || this == 10

@Composable
private fun String?.runningSpinnerColor(defaultColor: Color): Color = when (this?.lowercase()) {
    "plan" -> OpenCodePalette.RunningPlan
    "build" -> OpenCodePalette.RunningBuild
    else -> defaultColor
}

internal const val SessionRunningIndicatorMinimumAlpha = 0.72f
internal const val SessionRunningIndicatorMinimumScale = 0.56f
internal const val SessionRunningIndicatorAnimationLoopMillis = 60_000

private const val SessionRunningIndicatorCenterMinimumAlpha = 0.82f
private const val SessionRunningIndicatorOuterMaximumAlpha = 0.90f
private val SessionRunningIndicatorPulseEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
