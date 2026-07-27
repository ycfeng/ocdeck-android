package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.data.server.ServerRepository
import io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity
import io.github.ycfeng.ocdeck.data.server.resolveServerHealth
import io.github.ycfeng.ocdeck.data.server.withStableServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

interface ForegroundConnectionController {
    fun desiredServerIds(): Set<String>
    fun onForegroundConnectionReady(serverId: String, transportIdentity: ServerTransportIdentity)
}

fun interface ForegroundHealthChecker {
    suspend fun check(serverId: String): Result<ServerTransportIdentity>
}

fun interface DefaultNetworkChangeHandler {
    suspend fun reconnectAfterDefaultNetworkChange()
}

internal data class ForegroundHealthConnection(
    val transportIdentity: ServerTransportIdentity,
    val checkHealth: suspend () -> Unit,
)

internal data class AppConnectionRecoverySnapshot(
    val networkRevision: Long,
    val serverCheckStartedCount: Long,
    val serverCheckFailureCount: Long,
    val followUpQueuedCount: Long,
    val followUpStartedCount: Long,
    val activeFlightCount: Int,
    val pendingFollowUpCount: Int,
)

class DefaultForegroundHealthChecker internal constructor(
    private val getConnection: suspend (String) -> ForegroundHealthConnection,
) : ForegroundHealthChecker {
    constructor(serverRepository: ServerRepository) : this(
        getConnection = { serverId ->
            val connection = serverRepository.getConnection(serverId)
            ForegroundHealthConnection(connection.transportIdentity) {
                resolveServerHealth(connection.readinessHealth, connection.api::getGlobalHealth)
            }
        },
    )

    override suspend fun check(serverId: String): Result<ServerTransportIdentity> = try {
        val stable = withStableServerConnection(
            getConnection = { getConnection(serverId) },
            transportIdentity = { it.transportIdentity },
        ) { connection ->
            connection.checkHealth()
        }
        Result.success(stable.transportIdentity)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (exception: Exception) {
        exception.fatalCauseOrNull()?.let { throw it }
        Result.failure(exception)
    }
}

class AppConnectionCoordinator(
    private val controller: ForegroundConnectionController,
    private val healthChecker: ForegroundHealthChecker,
    private val scope: CoroutineScope,
    private val defaultNetworkChangeHandler: DefaultNetworkChangeHandler = DefaultNetworkChangeHandler {},
) {
    private val lock = Any()
    private val flights = mutableMapOf<String, Job>()
    private val pendingFollowUps = mutableMapOf<String, Long>()
    private var networkRecoveryFlight: Job? = null
    private var networkRecoveryDirty = false
    private var networkRevision = 0L
    private var serverCheckStartedCount = 0L
    private var serverCheckFailureCount = 0L
    private var followUpQueuedCount = 0L
    private var followUpStartedCount = 0L

    fun onForeground() {
        val revision = synchronized(lock) { networkRevision }
        controller.desiredServerIds().forEach { serverId ->
            startServerCheck(serverId, revision, queueFollowUp = false)
        }
    }

    fun onDefaultNetworkChanged() {
        synchronized(lock) {
            networkRevision = if (networkRevision == Long.MAX_VALUE) 1L else networkRevision + 1L
            networkRecoveryDirty = true
        }
        startNetworkRecovery()
    }

    internal fun activeFlightCount(): Int = synchronized(lock) { flights.size }

    internal fun captureRecoverySnapshot(): AppConnectionRecoverySnapshot = synchronized(lock) {
        AppConnectionRecoverySnapshot(
            networkRevision = networkRevision,
            serverCheckStartedCount = serverCheckStartedCount,
            serverCheckFailureCount = serverCheckFailureCount,
            followUpQueuedCount = followUpQueuedCount,
            followUpStartedCount = followUpStartedCount,
            activeFlightCount = flights.size,
            pendingFollowUpCount = pendingFollowUps.size,
        )
    }

    private fun startServerCheck(
        serverId: String,
        revision: Long,
        queueFollowUp: Boolean,
        boundedFollowUp: Boolean = false,
    ) {
        var jobToStart: Job? = null
        synchronized(lock) {
            if (flights[serverId] != null) {
                if (queueFollowUp) {
                    queueFollowUpLocked(serverId, revision)
                }
                return
            }
            serverCheckStartedCount = serverCheckStartedCount.saturatingIncrement()
            if (boundedFollowUp) followUpStartedCount = followUpStartedCount.saturatingIncrement()
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    healthChecker.check(serverId)
                        .onSuccess { identity ->
                            val isCurrent = synchronized(lock) { networkRevision == revision }
                            if (isCurrent) controller.onForegroundConnectionReady(serverId, identity)
                        }
                        .onFailure {
                            synchronized(lock) {
                                serverCheckFailureCount = serverCheckFailureCount.saturatingIncrement()
                                if (queueFollowUp) {
                                    if (networkRevision == revision) {
                                        queueFollowUpLocked(serverId, revision)
                                    }
                                }
                            }
                        }
                } finally {
                    val followUpRevision = synchronized(lock) {
                        if (flights[serverId] !== job) {
                            null
                        } else {
                            flights.remove(serverId)
                            pendingFollowUps.remove(serverId)
                        }
                    }
                    followUpRevision?.let {
                        startServerCheck(serverId, it, queueFollowUp = false, boundedFollowUp = true)
                    }
                }
            }
            flights[serverId] = job
            jobToStart = job
        }
        jobToStart?.start()
    }

    private fun queueFollowUpLocked(serverId: String, revision: Long) {
        val previous = pendingFollowUps[serverId]
        if (previous == null || revision > previous) {
            pendingFollowUps[serverId] = revision
            followUpQueuedCount = followUpQueuedCount.saturatingIncrement()
        }
    }

    private fun startNetworkRecovery() {
        var jobToStart: Job? = null
        synchronized(lock) {
            if (networkRecoveryFlight != null) return
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    runNetworkRecovery(job)
                } finally {
                    val restart = synchronized(lock) {
                        if (networkRecoveryFlight !== job) {
                            false
                        } else {
                            networkRecoveryFlight = null
                            networkRecoveryDirty
                        }
                    }
                    if (restart) startNetworkRecovery()
                }
            }
            networkRecoveryFlight = job
            jobToStart = job
        }
        jobToStart?.start()
    }

    private suspend fun runNetworkRecovery(job: Job) {
        while (true) {
            val revision = synchronized(lock) {
                if (networkRecoveryFlight !== job || !networkRecoveryDirty) {
                    if (networkRecoveryFlight === job) networkRecoveryFlight = null
                    return
                }
                networkRecoveryDirty = false
                networkRevision
            }
            try {
                defaultNetworkChangeHandler.reconnectAfterDefaultNetworkChange()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                exception.fatalCauseOrNull()?.let { throw it }
            }
            controller.desiredServerIds().forEach { serverId ->
                startServerCheck(serverId, revision, queueFollowUp = true)
            }
        }
    }
}

private fun Long.saturatingIncrement(): Long = if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1L
