package io.github.ycfeng.ocdeck.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStoreTest {
    @Test
    fun appendTracksSessionAndProjectUnseenState() {
        val store = NotificationStore()
            .append(TurnCompleteNotification(directory = "/repo", sessionId = "ses1", timeMillis = 1L, viewed = false))
            .append(ErrorNotification(directory = "/repo", sessionId = "ses2", timeMillis = 2L, viewed = false, summary = "failed"))

        assertEquals(1, store.sessionUnseenCount("ses1"))
        assertEquals(2, store.projectUnseenCount("/repo"))
        assertFalse(store.sessionUnseenHasError("ses1"))
        assertTrue(store.sessionUnseenHasError("ses2"))
        assertTrue(store.projectUnseenHasError("/repo"))
    }

    @Test
    fun markSessionViewedKeepsItemsButClearsSessionUnseenState() {
        val store = NotificationStore()
            .append(TurnCompleteNotification(directory = "/repo", sessionId = "ses1", timeMillis = 1L, viewed = false))
            .append(ErrorNotification(directory = "/repo", sessionId = "ses2", timeMillis = 2L, viewed = false, summary = "failed"))

        val next = store.markSessionViewed("ses1")

        assertEquals(2, next.items.size)
        assertEquals(0, next.sessionUnseenCount("ses1"))
        assertEquals(1, next.projectUnseenCount("/repo"))
    }

    @Test
    fun markProjectViewedClearsAllProjectUnseenState() {
        val store = NotificationStore()
            .append(TurnCompleteNotification(directory = "/repo", sessionId = "ses1", timeMillis = 1L, viewed = false))
            .append(ErrorNotification(directory = "/repo", sessionId = "ses2", timeMillis = 2L, viewed = false, summary = "failed"))
            .append(TurnCompleteNotification(directory = "/other", sessionId = "ses3", timeMillis = 3L, viewed = false))

        val next = store.markProjectViewed("/repo")

        assertEquals(0, next.projectUnseenCount("/repo"))
        assertEquals(1, next.projectUnseenCount("/other"))
        assertFalse(next.projectUnseenHasError("/repo"))
    }

    @Test
    fun appendPrunesExpiredNotificationsAndKeepsLatestFiveHundred() {
        val now = 31L * 24L * 60L * 60L * 1000L
        val old = TurnCompleteNotification(directory = "/repo", sessionId = "old", timeMillis = 0L, viewed = false)
        val seeded = NotificationStore().append(old, nowMillis = now)

        val store = (1..501).fold(seeded) { current, index ->
            current.append(
                TurnCompleteNotification(
                    directory = "/repo",
                    sessionId = "ses$index",
                    timeMillis = now + index,
                    viewed = false,
                ),
                nowMillis = now + index,
            )
        }

        assertEquals(500, store.items.size)
        assertFalse(store.items.any { it.sessionId == "old" })
        assertFalse(store.items.any { it.sessionId == "ses1" })
        assertTrue(store.items.any { it.sessionId == "ses501" })
    }
}
