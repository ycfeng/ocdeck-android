package io.github.ycfeng.ocdeck.data.server

import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorGeneration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTransportIdentityTest {
    @Test
    fun monotonicComparisonUsesConfigThenComparableStcpFields() {
        val configOne = identity(configEpoch = 1L)
        val configTwo = identity(configEpoch = 2L)
        val generationOne = identity(configEpoch = 2L, generationOrdinal = 1L, controlEpoch = 9L)
        val generationTwo = identity(configEpoch = 2L, generationOrdinal = 2L, controlEpoch = 1L)
        val controlOne = identity(configEpoch = 2L, generationOrdinal = 2L, controlEpoch = 1L)
        val controlTwo = identity(configEpoch = 2L, generationOrdinal = 2L, controlEpoch = 2L)

        assertTrue(configOne.isOlderThan(configTwo))
        assertTrue(generationOne.isOlderThan(generationTwo))
        assertTrue(controlOne.isOlderThan(controlTwo))
        assertFalse(controlTwo.isOlderThan(controlOne))
    }

    @Test
    fun directAndStcpWithSameConfigAreNotFalselyOrdered() {
        val direct = identity(configEpoch = 1L)
        val stcp = identity(configEpoch = 1L, generationOrdinal = 1L, controlEpoch = 1L)

        assertNull(direct.monotonicCompareTo(stcp))
        assertNull(stcp.monotonicCompareTo(direct))
        assertFalse(direct.isOlderThan(stcp))
        assertFalse(stcp.isOlderThan(direct))
    }

    @Test
    fun stableLoadDiscardsADataAndRetriesWithB() = runTest {
        val a = Candidate("A", identity(configEpoch = 1L, generationOrdinal = 1L, controlEpoch = 1L))
        val b = Candidate("B", identity(configEpoch = 1L, generationOrdinal = 1L, controlEpoch = 2L))
        val connections = ArrayDeque(listOf(a, b, b))
        val loaded = mutableListOf<String>()

        val result = withStableServerConnection(
            getConnection = { connections.removeFirst() },
            transportIdentity = Candidate::identity,
        ) { connection ->
            loaded += connection.label
            "data-${connection.label}"
        }

        assertEquals(listOf("A", "B"), loaded)
        assertEquals("data-B", result.value)
        assertEquals(b.identity, result.transportIdentity)
    }

    @Test
    fun continuouslyChangingTransportFailsAfterFiniteAttempts() = runTest {
        val candidates = ArrayDeque(
            listOf(
                Candidate("A", identity(1L, 1L, 1L)),
                Candidate("B", identity(1L, 1L, 2L)),
                Candidate("C", identity(1L, 1L, 3L)),
                Candidate("D", identity(1L, 1L, 4L)),
            ),
        )
        val loaded = mutableListOf<String>()

        val failure = runCatching {
            withStableServerConnection(
                getConnection = { candidates.removeFirst() },
                transportIdentity = Candidate::identity,
                maxAttempts = 3,
            ) { connection ->
                loaded += connection.label
                connection.label
            }
        }.exceptionOrNull()

        assertTrue(failure is ServerTransportUnstableException)
        assertEquals(listOf("A", "B", "C"), loaded)
    }

    @Test
    fun failedOldConnectionRetriesWhenVerificationFindsNewerConnection() = runTest {
        val a = Candidate("A", identity(1L, 1L, 1L))
        val b = Candidate("B", identity(1L, 1L, 2L))
        val connections = ArrayDeque(listOf(a, b, b))
        val loaded = mutableListOf<String>()

        val result = withStableServerConnection(
            getConnection = { connections.removeFirst() },
            transportIdentity = Candidate::identity,
        ) { connection ->
            loaded += connection.label
            if (connection == a) throw IllegalStateException("old connection failed")
            "data-${connection.label}"
        }

        assertEquals(listOf("A", "B"), loaded)
        assertEquals("data-B", result.value)
        assertEquals(b.identity, result.transportIdentity)
    }

    @Test
    fun failedUnchangedConnectionPropagatesOriginalFailureWithoutRetryingLoad() = runTest {
        val a = Candidate("A", identity(1L, 1L, 1L))
        val connections = ArrayDeque(listOf(a, a))
        val original = IllegalStateException("same connection failed")
        var loadCalls = 0

        val failure = runCatching {
            withStableServerConnection(
                getConnection = { connections.removeFirst() },
                transportIdentity = Candidate::identity,
            ) {
                loadCalls += 1
                throw original
            }
        }.exceptionOrNull()

        assertSame(original, failure)
        assertEquals(1, loadCalls)
    }

    @Test
    fun continuouslyChangingFailedConnectionsStopAtAttemptLimit() = runTest {
        val candidates = ArrayDeque(
            listOf(
                Candidate("A", identity(1L, 1L, 1L)),
                Candidate("B", identity(1L, 1L, 2L)),
                Candidate("C", identity(1L, 1L, 3L)),
                Candidate("D", identity(1L, 1L, 4L)),
            ),
        )
        val loaded = mutableListOf<String>()

        val failure = runCatching {
            withStableServerConnection(
                getConnection = { candidates.removeFirst() },
                transportIdentity = Candidate::identity,
                maxAttempts = 3,
            ) { connection ->
                loaded += connection.label
                throw IllegalStateException("${connection.label} failed")
            }
        }.exceptionOrNull()

        assertTrue(failure is ServerTransportUnstableException)
        assertEquals(listOf("A", "B", "C"), loaded)
    }

    @Test
    fun stableLoadDoesNotCatchJvmError() = runTest {
        val candidate = Candidate("A", identity(1L, 1L, 1L))
        val fatal = TestJvmError()
        var connectionCalls = 0

        val failure = runCatching {
            withStableServerConnection(
                getConnection = {
                    connectionCalls += 1
                    candidate
                },
                transportIdentity = Candidate::identity,
            ) {
                throw fatal
            }
        }.exceptionOrNull()

        assertSame(fatal, failure)
        assertEquals(1, connectionCalls)
    }

    private data class Candidate(
        val label: String,
        val identity: ServerTransportIdentity,
    )

    private class TestJvmError : Error()

    private fun identity(
        configEpoch: Long,
        generationOrdinal: Long? = null,
        controlEpoch: Long? = null,
    ) = ServerTransportIdentity(
        configEpoch = configEpoch,
        stcpGeneration = generationOrdinal?.let {
            FrpcStcpVisitorGeneration(
                serverId = "server",
                configEpoch = configEpoch,
                ordinal = it,
            )
        },
        stcpControlEpoch = controlEpoch,
    )
}
