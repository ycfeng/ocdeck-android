package io.github.ycfeng.ocdeck.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkMonitorTest {
    @Test
    fun initialAndRepeatedDefaultNetworkAreIgnored() {
        val tracker = DefaultNetworkChangeTracker("network-a")

        assertFalse(tracker.onAvailable("network-a"))
        assertFalse(tracker.onAvailable("network-a"))
    }

    @Test
    fun newDefaultNetworkTriggersOnce() {
        val tracker = DefaultNetworkChangeTracker("network-a")

        assertTrue(tracker.onAvailable("network-b"))
        assertFalse(tracker.onAvailable("network-b"))
    }

    @Test
    fun losingAndRestoringTheCurrentDefaultNetworkTriggersRecovery() {
        val tracker = DefaultNetworkChangeTracker("network-a")

        assertTrue(tracker.onLost("network-a"))

        assertTrue(tracker.onAvailable("network-a"))
    }

    @Test
    fun staleLossDoesNotReplaceTheNewDefaultNetwork() {
        val tracker = DefaultNetworkChangeTracker("network-a")

        assertTrue(tracker.onAvailable("network-b"))
        assertFalse(tracker.onLost("network-a"))
        assertFalse(tracker.onAvailable("network-b"))
    }

    @Test
    fun snapshotContainsOnlyCallbackAndDispatchCounts() {
        val tracker = DefaultNetworkChangeTracker("network-a")

        assertFalse(tracker.onAvailable("network-a"))
        assertTrue(tracker.onLost("network-a"))
        assertTrue(tracker.onAvailable("network-b"))

        assertEquals(
            DefaultNetworkMonitorSnapshot(
                started = true,
                availableCallbackCount = 2L,
                lostCallbackCount = 1L,
                dispatchedChangeCount = 2L,
            ),
            tracker.captureSnapshot(),
        )
    }
}
