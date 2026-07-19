package io.github.ycfeng.ocdeck.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalLazyListReorderTest {
    @Test
    fun autoScrollUsesNearestOverflowWhenEdgeZonesMeet() {
        assertEquals(
            -24f,
            calculateVerticalReorderAutoScroll(
                viewportStart = 0f,
                viewportEnd = 80f,
                draggedTop = -8f,
                draggedBottom = 40f,
                thresholdPx = 56f,
                stepPx = 24f,
            ),
        )
        assertEquals(
            24f,
            calculateVerticalReorderAutoScroll(
                viewportStart = 0f,
                viewportEnd = 80f,
                draggedTop = 40f,
                draggedBottom = 88f,
                thresholdPx = 56f,
                stepPx = 24f,
            ),
        )
    }

    @Test
    fun autoScrollStopsWhenDraggedItemIsOutsideEdgeZones() {
        assertEquals(
            0f,
            calculateVerticalReorderAutoScroll(
                viewportStart = 0f,
                viewportEnd = 200f,
                draggedTop = 70f,
                draggedBottom = 130f,
                thresholdPx = 40f,
                stepPx = 16f,
            ),
        )
    }
}
