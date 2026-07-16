package io.github.ycfeng.ocdeck.domain.prompt

import io.github.ycfeng.ocdeck.core.util.PathNormalizer

class PromptOperationGate(
    private val pathNormalizer: PathNormalizer,
) {
    private val lock = Any()
    private val activeKeys = mutableSetOf<PromptOperationKey>()

    fun tryAcquire(
        serverId: String,
        directory: String,
        sessionId: String,
        workspace: String? = null,
    ): Lease? {
        val key = PromptOperationKey(
            serverId = serverId,
            normalizedDirectory = pathNormalizer.comparisonKey(pathNormalizer.normalize(directory)),
            workspace = normalizeOptionalPromptWorkspace(workspace, pathNormalizer)
                ?.let(pathNormalizer::comparisonKey),
            sessionId = sessionId,
        )
        return synchronized(lock) {
            if (!activeKeys.add(key)) return@synchronized null
            Lease(this, key, mutableSetOf(key))
        }
    }

    private fun addSessionAlias(lease: Lease, sessionId: String): Boolean = synchronized(lock) {
        if (lease.closed) return@synchronized false
        val key = lease.primaryKey.copy(sessionId = sessionId)
        if (key in lease.keys) return@synchronized true
        if (!activeKeys.add(key)) return@synchronized false
        lease.keys += key
        true
    }

    private fun release(lease: Lease) {
        synchronized(lock) {
            if (lease.closed) return
            lease.closed = true
            activeKeys.removeAll(lease.keys)
            lease.keys.clear()
        }
    }

    class Lease internal constructor(
        private val gate: PromptOperationGate,
        internal val primaryKey: PromptOperationKey,
        internal val keys: MutableSet<PromptOperationKey>,
    ) {
        internal var closed: Boolean = false

        fun addSessionAlias(sessionId: String): Boolean = gate.addSessionAlias(this, sessionId)

        fun close() = gate.release(this)
    }
}

internal fun normalizeOptionalPromptWorkspace(
    workspace: String?,
    pathNormalizer: PathNormalizer,
): String? = workspace
    ?.trim(Char::isPromptOperationWhitespace)
    ?.takeIf(String::isNotEmpty)
    ?.let(pathNormalizer::normalize)

private fun Char.isPromptOperationWhitespace(): Boolean = isWhitespace() || Character.isSpaceChar(this)

internal data class PromptOperationKey(
    val serverId: String,
    val normalizedDirectory: String,
    val workspace: String?,
    val sessionId: String,
) {
    override fun toString(): String =
        "PromptOperationKey(serverId=<redacted>, normalizedDirectory=<redacted>, " +
            "workspace=${if (workspace == null) "null" else "<redacted>"}, sessionId=<redacted>)"
}
