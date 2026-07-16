package io.github.ycfeng.ocdeck.core.util

internal class LatestRequestGate {
    private val lock = Any()
    private var latestRequestId = 0L

    fun begin(): Long = synchronized(lock) {
        ++latestRequestId
    }

    fun isCurrent(requestId: Long): Boolean = synchronized(lock) {
        requestId == latestRequestId
    }
}
