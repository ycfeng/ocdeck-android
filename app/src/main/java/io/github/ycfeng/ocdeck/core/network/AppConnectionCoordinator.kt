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

internal data class ForegroundHealthConnection(
    val transportIdentity: ServerTransportIdentity,
    val checkHealth: suspend () -> Unit,
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
) {
    private val lock = Any()
    private val flights = mutableMapOf<String, Job>()

    fun onForeground() {
        controller.desiredServerIds().forEach(::startServerCheck)
    }

    internal fun activeFlightCount(): Int = synchronized(lock) { flights.size }

    private fun startServerCheck(serverId: String) {
        var jobToStart: Job? = null
        synchronized(lock) {
            if (flights[serverId]?.isActive == true) return
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    healthChecker.check(serverId)
                        .onSuccess { identity -> controller.onForegroundConnectionReady(serverId, identity) }
                } finally {
                    synchronized(lock) {
                        if (flights[serverId] === job) flights.remove(serverId)
                    }
                }
            }
            flights[serverId] = job
            jobToStart = job
        }
        jobToStart?.start()
    }
}
