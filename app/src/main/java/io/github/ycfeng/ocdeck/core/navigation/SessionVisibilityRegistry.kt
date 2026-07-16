package io.github.ycfeng.ocdeck.core.navigation

import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore

class SessionVisibilityRegistry(
    private val store: InMemoryOpenCodeStore,
) {
    private val destinations = linkedMapOf<Long, SessionDestinationState>()
    private var nextLeaseId = 0L
    private var appForeground = false
    private var activeTarget: SessionVisibilityTarget? = null

    @Synchronized
    fun createLease(
        serverId: String,
        directory: String,
        sessionId: String?,
        workspace: String? = null,
    ): SessionVisibilityLease {
        val leaseId = ++nextLeaseId
        destinations[leaseId] = SessionDestinationState(
            target = SessionVisibilityTarget(serverId, directory, workspace, sessionId?.takeIf { it.isNotBlank() }),
            destinationVisible = false,
        )
        return SessionVisibilityLease(this, leaseId)
    }

    @Synchronized
    fun onAppForeground() {
        appForeground = true
        updateActiveTarget()
    }

    @Synchronized
    fun onAppBackground() {
        appForeground = false
        updateActiveTarget()
    }

    @Synchronized
    internal fun setDestinationVisible(leaseId: Long, visible: Boolean) {
        val state = destinations[leaseId] ?: return
        destinations[leaseId] = state.copy(destinationVisible = visible)
        updateActiveTarget()
    }

    @Synchronized
    internal fun updateSession(leaseId: Long, sessionId: String?) {
        val state = destinations[leaseId] ?: return
        destinations[leaseId] = state.copy(
            target = state.target.copy(sessionId = sessionId?.takeIf { it.isNotBlank() }),
        )
        updateActiveTarget()
    }

    @Synchronized
    internal fun release(leaseId: Long) {
        if (destinations.remove(leaseId) != null) updateActiveTarget()
    }

    private fun updateActiveTarget() {
        val nextTarget = if (appForeground) {
            destinations.values
                .lastOrNull { it.destinationVisible && it.target.sessionId != null }
                ?.target
        } else {
            null
        }
        if (nextTarget == activeTarget) return

        activeTarget?.let { target ->
            store.setActiveSession(target.serverId, target.directory, null, target.workspace)
        }
        activeTarget = nextTarget
        nextTarget?.let { target ->
            val sessionId = requireNotNull(target.sessionId)
            store.setActiveSession(target.serverId, target.directory, sessionId, target.workspace)
            store.markSessionNotificationsViewed(target.serverId, target.directory, sessionId, target.workspace)
        }
    }
}

class SessionVisibilityLease internal constructor(
    private val registry: SessionVisibilityRegistry,
    private val leaseId: Long,
) {
    fun setDestinationVisible(visible: Boolean) {
        registry.setDestinationVisible(leaseId, visible)
    }

    fun updateSession(sessionId: String?) {
        registry.updateSession(leaseId, sessionId)
    }

    fun release() {
        registry.release(leaseId)
    }
}

private data class SessionDestinationState(
    val target: SessionVisibilityTarget,
    val destinationVisible: Boolean,
)

private data class SessionVisibilityTarget(
    val serverId: String,
    val directory: String,
    val workspace: String?,
    val sessionId: String?,
) {
    override fun toString(): String =
        "SessionVisibilityTarget(serverId=<redacted>, directory=<redacted>, " +
            "workspace=${if (workspace == null) "null" else "<redacted>"}, " +
            "sessionId=${if (sessionId == null) "null" else "<redacted>"})"
}
