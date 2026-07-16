package io.github.ycfeng.ocdeck.core.navigation

import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.OpenCodeEventReducer
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVisibilityRegistryTest {
    private val serverId = "local"
    private val directory = "/repo"

    @Test
    fun sessionBecomesActiveAndViewedOnlyWhileAppAndDestinationAreVisible() {
        val store = storeWithUnviewedSessionNotification("ses1")
        val registry = SessionVisibilityRegistry(store)
        val lease = registry.createLease(serverId, directory, "ses1")

        lease.setDestinationVisible(true)
        assertNull(store.currentProject(serverId, directory).activeSessionId)
        assertTrue(store.currentProject(serverId, directory).notifications.sessionUnseenCount("ses1") > 0)

        registry.onAppForeground()
        assertEquals("ses1", store.currentProject(serverId, directory).activeSessionId)
        assertEquals(0, store.currentProject(serverId, directory).notifications.sessionUnseenCount("ses1"))

        lease.setDestinationVisible(false)
        assertNull(store.currentProject(serverId, directory).activeSessionId)
    }

    @Test
    fun backgroundClearsActiveSessionAndForegroundRestoresOnlyVisibleDestination() {
        val store = InMemoryOpenCodeStore(PathNormalizer())
        val registry = SessionVisibilityRegistry(store)
        val lease = registry.createLease(serverId, directory, "ses1")

        registry.onAppForeground()
        assertNull(store.currentProject(serverId, directory).activeSessionId)

        lease.setDestinationVisible(true)
        assertEquals("ses1", store.currentProject(serverId, directory).activeSessionId)

        registry.onAppBackground()
        assertNull(store.currentProject(serverId, directory).activeSessionId)

        registry.onAppForeground()
        assertEquals("ses1", store.currentProject(serverId, directory).activeSessionId)

        lease.release()
        assertNull(store.currentProject(serverId, directory).activeSessionId)
    }

    @Test
    fun materializedSessionUpdatesTheVisibleLeaseWithoutViewModelLifetimeSemantics() {
        val store = InMemoryOpenCodeStore(PathNormalizer())
        val registry = SessionVisibilityRegistry(store)
        val lease = registry.createLease(serverId, directory, null)

        registry.onAppForeground()
        lease.setDestinationVisible(true)
        assertNull(store.currentProject(serverId, directory).activeSessionId)

        lease.updateSession("ses2")
        assertEquals("ses2", store.currentProject(serverId, directory).activeSessionId)

        lease.updateSession(null)
        assertNull(store.currentProject(serverId, directory).activeSessionId)
    }

    private fun storeWithUnviewedSessionNotification(sessionId: String): InMemoryOpenCodeStore {
        val store = InMemoryOpenCodeStore(
            pathNormalizer = PathNormalizer(),
            eventReducer = OpenCodeEventReducer(nowMillis = { 42L }),
        )
        val event = Json.parseToJsonElement(
            """{"type":"session.idle","properties":{"sessionID":"$sessionId"}}""",
        ).jsonObject
        store.reduceProjectEvent(serverId, directory, event)
        return store
    }
}
