package io.github.ycfeng.ocdeck.ui.component

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRunningIndicatorTest {
    @Test
    fun gridHidesOnlyCornersAndKeepsFourCenterDots() {
        val hiddenDots = (0 until 16).filter { it.isHiddenSpinnerDot() }
        val centerDots = (0 until 16).filter { it.isCenterSpinnerDot() }

        assertEquals(listOf(0, 3, 12, 15), hiddenDots)
        assertEquals(listOf(5, 6, 9, 10), centerDots)
        assertEquals(12, 16 - hiddenDots.size)
    }

    @Test
    fun visibleDotsUseIndependentUpstreamStyleTiming() {
        val visibleMotions = (0 until 16)
            .filterNot { it.isHiddenSpinnerDot() }
            .map(SessionRunningIndicatorDotMotions::get)

        visibleMotions.forEach { motion ->
            assertTrue(motion.durationMillis in 1_000..2_000)
            assertTrue(motion.phaseOffsetMillis in 0..1_500)
            assertEquals(0, SessionRunningIndicatorAnimationLoopMillis % motion.durationMillis)
        }
        assertTrue(visibleMotions.toSet().size >= 8)
    }

    @Test
    fun everyVisibleDotChangesWithoutLeavingAccessibleBounds() {
        val sampleTimes = (0..SessionRunningIndicatorAnimationLoopMillis step 137).map(Int::toFloat)

        (0 until 16).filterNot { it.isHiddenSpinnerDot() }.forEach { index ->
            val initialFrame = sessionRunningIndicatorDotFrame(index, 0f)
            var changed = false

            sampleTimes.forEach { elapsedMillis ->
                val frame = sessionRunningIndicatorDotFrame(index, elapsedMillis)
                assertTrue("dot $index alpha ${frame.alpha}", frame.alpha in SessionRunningIndicatorMinimumAlpha..1f)
                assertTrue("dot $index scale ${frame.scale}", frame.scale in SessionRunningIndicatorMinimumScale..1f)
                changed = changed ||
                    abs(frame.alpha - initialFrame.alpha) > 0.001f ||
                    abs(frame.scale - initialFrame.scale) > 0.001f
            }

            assertTrue("dot $index never changes", changed)
        }
    }

    @Test
    fun animationLoopIsContinuousAndStartsWithVariedFrames() {
        val visibleIndices = (0 until 16).filterNot { it.isHiddenSpinnerDot() }
        val initialScales = visibleIndices
            .map { sessionRunningIndicatorDotFrame(it, 0f).scale }
            .map { (it * 1_000).toInt() }
            .toSet()

        assertTrue(initialScales.size >= 5)
        visibleIndices.forEach { index ->
            val start = sessionRunningIndicatorDotFrame(index, 0f)
            val end = sessionRunningIndicatorDotFrame(
                index = index,
                elapsedMillis = SessionRunningIndicatorAnimationLoopMillis.toFloat(),
            )
            assertEquals(start.alpha, end.alpha, 0.0001f)
            assertEquals(start.scale, end.scale, 0.0001f)
        }
    }
}
