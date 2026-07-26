package io.github.ycfeng.ocdeck.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.util.concurrent.atomic.AtomicBoolean

class DefaultNetworkMonitor(
    context: Context,
    private val onDefaultNetworkChanged: () -> Unit,
) : AutoCloseable {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val tracker = DefaultNetworkChangeTracker(connectivityManager.activeNetwork)
    private val started = AtomicBoolean(false)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (tracker.onAvailable(network)) onDefaultNetworkChanged()
        }

        override fun onLost(network: Network) {
            if (tracker.onLost(network)) onDefaultNetworkChanged()
        }
    }

    fun start() {
        if (started.compareAndSet(false, true)) {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
    }

    override fun close() {
        if (started.compareAndSet(true, false)) {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }

    internal fun captureSnapshot(): DefaultNetworkMonitorSnapshot = tracker.captureSnapshot(
        started = started.get(),
    )
}

internal class DefaultNetworkChangeTracker<T>(initialNetwork: T?) {
    private val lock = Any()
    private var currentNetwork = initialNetwork
    private var availableCallbackCount = 0L
    private var lostCallbackCount = 0L
    private var dispatchedChangeCount = 0L

    fun onAvailable(network: T): Boolean = synchronized(lock) {
        availableCallbackCount = availableCallbackCount.saturatingIncrement()
        if (currentNetwork == network) {
            false
        } else {
            currentNetwork = network
            dispatchedChangeCount = dispatchedChangeCount.saturatingIncrement()
            true
        }
    }

    fun onLost(network: T): Boolean = synchronized(lock) {
        lostCallbackCount = lostCallbackCount.saturatingIncrement()
        if (currentNetwork == network) {
            currentNetwork = null
            dispatchedChangeCount = dispatchedChangeCount.saturatingIncrement()
            true
        } else {
            false
        }
    }

    fun captureSnapshot(started: Boolean = true): DefaultNetworkMonitorSnapshot = synchronized(lock) {
        DefaultNetworkMonitorSnapshot(
            started = started,
            availableCallbackCount = availableCallbackCount,
            lostCallbackCount = lostCallbackCount,
            dispatchedChangeCount = dispatchedChangeCount,
        )
    }
}

internal data class DefaultNetworkMonitorSnapshot(
    val started: Boolean,
    val availableCallbackCount: Long,
    val lostCallbackCount: Long,
    val dispatchedChangeCount: Long,
)

private fun Long.saturatingIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1L
