package io.github.ycfeng.ocdeck.data.server

import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorGeneration
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailure
import io.github.ycfeng.ocdeck.core.network.OpenCodeRequestException

data class ServerTransportIdentity(
    val configEpoch: Long,
    val stcpGeneration: FrpcStcpVisitorGeneration? = null,
    val stcpControlEpoch: Long? = null,
) {
    fun isOlderThan(other: ServerTransportIdentity): Boolean = monotonicCompareTo(other) == -1

    fun monotonicCompareTo(other: ServerTransportIdentity): Int? {
        configEpoch.compareTo(other.configEpoch).takeIf { it != 0 }?.let { return it.sign() }

        val generationComparison = compareStcpGeneration(stcpGeneration, other.stcpGeneration)
        generationComparison?.takeIf { it != 0 }?.let { return it }
        val generationsComparable = (stcpGeneration == null && other.stcpGeneration == null) || generationComparison != null
        if (!generationsComparable) return null

        if (stcpControlEpoch != null && other.stcpControlEpoch != null) {
            return stcpControlEpoch.compareTo(other.stcpControlEpoch).sign()
        }
        return if (this == other) 0 else null
    }

    override fun toString(): String =
        "ServerTransportIdentity(configEpoch=$configEpoch, stcpGenerationPresent=${stcpGeneration != null}, " +
            "stcpControlEpoch=$stcpControlEpoch)"
}

class ServerTransportUnstableException : OpenCodeRequestException(OpenCodeFailure.TransportChanged)

internal data class StableTransportResult<T>(
    val value: T,
    val transportIdentity: ServerTransportIdentity,
) {
    override fun toString(): String =
        "StableTransportResult(valuePresent=true, transportIdentity=$transportIdentity)"
}

internal suspend fun <C, T> withStableServerConnection(
    getConnection: suspend () -> C,
    transportIdentity: (C) -> ServerTransportIdentity,
    maxAttempts: Int = 3,
    load: suspend (C) -> T,
): StableTransportResult<T> {
    require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
    var connection = getConnection()
    repeat(maxAttempts) { attempt ->
        val identity = transportIdentity(connection)
        val value = try {
            load(connection)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            val failureVerifiedConnection = try {
                getConnection()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw exception
            }
            val failureVerifiedIdentity = transportIdentity(failureVerifiedConnection)
            if (failureVerifiedIdentity == identity || failureVerifiedIdentity.isOlderThan(identity)) {
                throw exception
            }
            if (attempt == maxAttempts - 1) throw ServerTransportUnstableException()
            connection = failureVerifiedConnection
            return@repeat
        }
        val verifiedConnection = getConnection()
        val verifiedIdentity = transportIdentity(verifiedConnection)
        if (identity == verifiedIdentity) return StableTransportResult(value, verifiedIdentity)
        if (verifiedIdentity.isOlderThan(identity) || attempt == maxAttempts - 1) {
            throw ServerTransportUnstableException()
        }
        connection = verifiedConnection
    }
    throw ServerTransportUnstableException()
}

private fun compareStcpGeneration(
    left: FrpcStcpVisitorGeneration?,
    right: FrpcStcpVisitorGeneration?,
): Int? {
    if (left == null || right == null || left.serverId != right.serverId) return null
    left.configEpoch.compareTo(right.configEpoch).takeIf { it != 0 }?.let { return it.sign() }
    return left.ordinal.compareTo(right.ordinal).sign()
}

private fun Int.sign(): Int = when {
    this < 0 -> -1
    this > 0 -> 1
    else -> 0
}
