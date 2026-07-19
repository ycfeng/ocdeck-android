package io.github.ycfeng.ocdeck.ui.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSourceTrackerTest {
    @Test
    fun remainsActiveUntilEveryDistinctSourceEnds() {
        val tracker = ActiveSourceTracker<Any>()
        val first = Any()
        val second = Any()

        tracker.begin(first)
        tracker.begin(first)
        tracker.begin(second)
        tracker.end(first)

        assertTrue(tracker.isActive.value)

        tracker.end(Any())
        assertTrue(tracker.isActive.value)

        tracker.end(second)
        assertFalse(tracker.isActive.value)
    }
}
