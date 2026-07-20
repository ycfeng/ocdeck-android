package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerFrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcSessionConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcEnsureVisitorResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStopSessionResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorState
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcVisitorReadyResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcVisitorRuntimeState
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.KotlinFrpcStcpVisitorFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import java.net.BindException
import java.net.ConnectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FrpcStcpVisitorManagerTest {
    @Test
    fun returnsReadyTunnelAndInitialHealthEvidence() = runTest {
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory()
        val manager = manager(client, probes)
        val server = stcpServer()

        val result = manager.ensureReadyVisitor(
            server = server,
            credentials = credentials(),
            openCodePassword = "opencode-password",
            configLease = manager.captureConfigLease(server.id),
        )

        assertEquals("session-1", result.tunnel.sessionId)
        assertEquals("ocdeck_remote", result.tunnel.visitorName)
        assertEquals(5096, result.tunnel.localPort)
        assertEquals(1, result.tunnel.desiredRevision)
        assertEquals(1, result.tunnel.controlEpoch)
        assertEquals("https://127.0.0.1:5096/opencode", result.tunnel.effectiveBaseUrl)
        assertEquals("1.2.3", result.readinessHealth?.version)
        assertEquals("", client.startedConfigs.single().authToken)
        assertEquals("127.0.0.1", client.visitorConfigs.single().bindAddr)
        assertEquals(4096, client.visitorConfigs.single().bindPort)
        assertEquals("opencode-password", probes.passwords.single())
    }

    @Test
    fun concurrentCallersShareOneGenerationAndOneProbe() = runTest {
        val probeGate = CompletableDeferred<Unit>()
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory { _, _ ->
            probeGate.await()
            ServerHealthDto(healthy = true, version = "1.2.3")
        }
        val manager = manager(client, probes)
        val server = stcpServer()
        val lease = manager.captureConfigLease(server.id)

        val results = List(20) {
            async {
                manager.ensureReadyVisitor(server, credentials(), null, lease)
            }
        }
        runCurrent()

        assertEquals(1, client.startCount)
        assertEquals(1, client.ensureCount)
        assertEquals(1, client.waitReadyCount)
        assertEquals(1, probes.probeCount)
        assertTrue(results.none { it.isCompleted })

        probeGate.complete(Unit)
        val completed = results.awaitAll()

        assertEquals(1, completed.map { it.generation }.distinct().size)
        assertTrue(completed.all { it.readinessHealth?.version == "1.2.3" })
    }

    @Test
    fun readyGenerationSkipsReadinessProbeForLaterConnections() = runTest {
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory()
        val manager = manager(client, probes)
        val server = stcpServer()
        val lease = manager.captureConfigLease(server.id)

        val first = manager.ensureReadyVisitor(server, credentials(), null, lease)
        val second = manager.ensureReadyVisitor(server, credentials(), null, lease)

        assertEquals(first.generation, second.generation)
        assertEquals(1, client.startCount)
        assertEquals(1, client.ensureCount)
        assertEquals(1, client.waitReadyCount)
        assertEquals(1, client.getStateCount)
        assertEquals(1, probes.probeCount)
        assertNull(second.readinessHealth)
    }

    @Test
    fun nativeStateCheckDoesNotSwallowJvmError() = runTest {
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val manager = manager(client, ScriptedHealthProbeFactory())
        val server = stcpServer()
        val ready = manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
        val fatal = FrpcManagerTestJvmError()
        client.onGetState = { _, _ -> throw fatal }

        try {
            manager.isReadyTransportCurrent(ready.generation, ready.tunnel.controlEpoch)
            throw AssertionError("JVM Error was not propagated")
        } catch (actual: FrpcManagerTestJvmError) {
            assertSame(fatal, actual)
        }
    }

    @Test
    fun nativeStateCheckPrefersNestedJvmErrorOverCancellation() = runTest {
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val manager = manager(client, ScriptedHealthProbeFactory())
        val server = stcpServer()
        val ready = manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
        val fatal = FrpcManagerTestJvmError()
        val cancellation = FrpcManagerTestCancellationException(Unit).apply { initCause(fatal) }
        client.onGetState = { _, _ -> throw cancellation }

        try {
            manager.isReadyTransportCurrent(ready.generation, ready.tunnel.controlEpoch)
            throw AssertionError("Nested JVM Error was not propagated")
        } catch (actual: FrpcManagerTestJvmError) {
            assertSame(fatal, actual)
        }
    }

    @Test
    fun generationWorkerCompletesWaitersAndRethrowsJvmError() = runTest {
        val fatal = FrpcManagerTestJvmError()
        val reported = CompletableDeferred<Throwable>()
        val workerJob = SupervisorJob()
        val workerScope = CoroutineScope(
            workerJob + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> reported.complete(throwable) },
        )
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096).apply {
            onStart = { _, _ -> throw fatal }
        }
        val manager = manager(client, ScriptedHealthProbeFactory(), workerScope)
        val server = stcpServer()

        val waiterFailure = runCatching {
            manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
        }.exceptionOrNull()
        runCurrent()

        assertSame(fatal, waiterFailure)
        assertSame(fatal, reported.await())
        workerJob.cancel()
    }

    @Test
    fun newControlEpochSharesNativeAndApplicationRecovery() = runTest {
        val recoveryProbeGate = CompletableDeferred<Unit>()
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory { attempt, _ ->
            if (attempt == 2) recoveryProbeGate.await()
            ServerHealthDto(healthy = true, version = "1.2.$attempt")
        }
        val manager = manager(client, probes)
        val server = stcpServer()
        val lease = manager.captureConfigLease(server.id)
        val initial = manager.ensureReadyVisitor(server, credentials(), null, lease)
        assertTrue(manager.isReadyTransportCurrent(initial.generation, initial.tunnel.controlEpoch))
        client.currentControlEpoch = 2

        val recovered = List(12) {
            async { manager.ensureReadyVisitor(server, credentials(), null, lease) }
        }
        runCurrent()

        assertFalse(manager.isReadyTransportCurrent(initial.generation, initial.tunnel.controlEpoch))
        assertEquals(2, client.getStateCount)
        assertEquals(2, client.waitReadyCount)
        assertEquals(2, probes.probeCount)
        assertTrue(recovered.none { it.isCompleted })

        recoveryProbeGate.complete(Unit)
        val results = recovered.awaitAll()

        assertTrue(results.all { it.tunnel.controlEpoch == 2L })
        assertTrue(results.all { it.readinessHealth?.version == "1.2.2" })
        assertTrue(manager.isReadyTransportCurrent(results.first().generation, 2L))
        assertFalse(manager.isReadyTransportCurrent(initial.generation, initial.tunnel.controlEpoch))
        assertEquals(1, client.startCount)
        assertEquals(1, client.ensureCount)
    }

    @Test
    fun failedEpochRecoveryIsDiscardedAndNextCallBuildsNewGeneration() = runTest {
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory { attempt, _ ->
            if (attempt == 2) throw IllegalArgumentException("invalid recovery response")
            ServerHealthDto(healthy = true, version = "1.2.$attempt")
        }
        val manager = manager(client, probes)
        val server = stcpServer()
        val lease = manager.captureConfigLease(server.id)
        val first = manager.ensureReadyVisitor(server, credentials(), null, lease)
        client.currentControlEpoch = 2

        val recoveryFailure = runCatching {
            manager.ensureReadyVisitor(server, credentials(), null, lease)
        }.exceptionOrNull()
        runCurrent()
        val rebuilt = manager.ensureReadyVisitor(server, credentials(), null, lease)

        assertTrue(recoveryFailure is IllegalArgumentException)
        assertEquals("session-1", first.tunnel.sessionId)
        assertEquals("session-2", rebuilt.tunnel.sessionId)
        assertNotEquals(first.generation, rebuilt.generation)
        assertEquals(listOf("session-1"), client.stoppedSessions)
    }

    @Test
    fun lateOldEpochRecoveryCannotOverwriteNewGeneration() = runTest {
        val oldRecoveryGate = CompletableDeferred<Unit>()
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory { attempt, _ ->
            if (attempt == 2) oldRecoveryGate.await()
            ServerHealthDto(healthy = true, version = "1.2.$attempt")
        }
        val manager = manager(client, probes)
        val server = stcpServer()
        val oldLease = manager.captureConfigLease(server.id)
        manager.ensureReadyVisitor(server, credentials(), null, oldLease)
        client.currentControlEpoch = 2
        val oldRecovery = async {
            runCatching { manager.ensureReadyVisitor(server, credentials(), null, oldLease) }
        }
        runCurrent()

        manager.invalidateConfiguration(server.id)
        runCurrent()
        client.currentControlEpoch = 3
        val newLease = manager.captureConfigLease(server.id)
        val replacement = manager.ensureReadyVisitor(server, credentials(), null, newLease)

        assertTrue(oldRecovery.await().exceptionOrNull() is FrpcStcpConfigLeaseExpiredException)
        assertEquals("session-2", replacement.tunnel.sessionId)
        assertEquals(3L, replacement.tunnel.controlEpoch)

        oldRecoveryGate.complete(Unit)
        advanceUntilIdle()
        val current = manager.ensureReadyVisitor(server, credentials(), null, newLease)

        assertEquals(replacement.generation, current.generation)
        assertEquals("session-2", current.tunnel.sessionId)
        assertEquals(listOf("session-1"), client.stoppedSessions)
    }

    @Test
    fun retriesTransientHealthFailureBeforePublishingReady() = runTest {
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory { attempt, _ ->
            if (attempt == 1) throw ConnectException("connection refused")
            ServerHealthDto(healthy = true, version = "1.2.3")
        }
        val manager = manager(client, probes)
        val server = stcpServer()

        val result = manager.ensureReadyVisitor(
            server,
            credentials(),
            null,
            manager.captureConfigLease(server.id),
        )

        assertEquals("1.2.3", result.readinessHealth?.version)
        assertEquals(2, probes.probeCount)
        assertTrue(client.stoppedSessions.isEmpty())
    }

    @Test
    fun applicationHealthWaitsForNativeListenerReadiness() = runTest {
        val nativeReadyGate = CompletableDeferred<Unit>()
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096).apply {
            onWaitReady = { _, _, _, _, _ -> nativeReadyGate.await() }
        }
        val probes = ScriptedHealthProbeFactory()
        val manager = manager(client, probes)
        val server = stcpServer()

        val connection = async {
            manager.ensureReadyVisitor(
                server,
                credentials(),
                null,
                manager.captureConfigLease(server.id),
            )
        }
        runCurrent()

        assertEquals(1, client.waitReadyCount)
        assertEquals(0, probes.probeCount)
        assertFalse(connection.isCompleted)

        nativeReadyGate.complete(Unit)
        assertEquals("session-1", connection.await().tunnel.sessionId)
        assertEquals(1, probes.probeCount)
    }

    @Test
    fun nonRetryableFailureIsNotCachedAndNextCallCreatesNewGeneration() = runTest {
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory { attempt, _ ->
            if (attempt == 1) throw IllegalArgumentException("invalid URL")
            ServerHealthDto(healthy = true, version = "1.2.3")
        }
        val manager = manager(client, probes)
        val server = stcpServer()
        val lease = manager.captureConfigLease(server.id)

        val firstFailure = runCatching {
            manager.ensureReadyVisitor(server, credentials(), null, lease)
        }.exceptionOrNull()
        runCurrent()
        val second = manager.ensureReadyVisitor(server, credentials(), null, lease)

        assertTrue(firstFailure is IllegalArgumentException)
        assertEquals(2, client.startCount)
        assertEquals(listOf("session-1"), client.stoppedSessions)
        assertEquals("session-2", second.tunnel.sessionId)
    }

    @Test
    fun oneCancelledWaiterDoesNotCancelSharedReadiness() = runTest {
        val probeGate = CompletableDeferred<Unit>()
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory { _, _ ->
            probeGate.await()
            ServerHealthDto(healthy = true, version = "1.2.3")
        }
        val manager = manager(client, probes)
        val server = stcpServer()
        val lease = manager.captureConfigLease(server.id)
        val cancelled = async { manager.ensureReadyVisitor(server, credentials(), null, lease) }
        val survivor = async { manager.ensureReadyVisitor(server, credentials(), null, lease) }
        runCurrent()

        cancelled.cancel()
        probeGate.complete(Unit)

        assertEquals("session-1", survivor.await().tunnel.sessionId)
        assertTrue(cancelled.isCancelled)
        assertTrue(client.stoppedSessions.isEmpty())
    }

    @Test
    fun invalidationStopsOnlyOldGenerationAndNewLeaseCanReconnect() = runTest {
        val firstProbeGate = CompletableDeferred<Unit>()
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val probes = ScriptedHealthProbeFactory { attempt, _ ->
            if (attempt == 1) firstProbeGate.await()
            ServerHealthDto(healthy = true, version = "1.2.3")
        }
        val manager = manager(client, probes)
        val server = stcpServer()
        val oldLease = manager.captureConfigLease(server.id)
        val oldConnection = async {
            runCatching { manager.ensureReadyVisitor(server, credentials(), null, oldLease) }
        }
        runCurrent()

        manager.invalidateConfiguration(server.id)
        runCurrent()
        val newLease = manager.captureConfigLease(server.id)
        val newConnection = async {
            manager.ensureReadyVisitor(server, credentials(), null, newLease)
        }
        advanceUntilIdle()

        assertTrue(oldConnection.isCompleted)
        assertTrue(oldConnection.await().exceptionOrNull() is FrpcStcpConfigLeaseExpiredException)
        assertEquals("session-2", newConnection.await().tunnel.sessionId)
        assertEquals(listOf("session-1"), client.stoppedSessions)

        firstProbeGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("session-1"), client.stoppedSessions)
    }

    @Test
    fun blockedServerDoesNotPreventAnotherServerFromBecomingReady() = runTest {
        val firstStartGate = CompletableDeferred<Unit>()
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096).apply {
            onStart = { config, _ ->
                if (config.serverAddr == "blocked.example.com") firstStartGate.await()
            }
        }
        val probes = ScriptedHealthProbeFactory()
        val manager = manager(client, probes)
        val blockedServer = stcpServer(id = "blocked", serverAddr = "blocked.example.com")
        val readyServer = stcpServer(id = "ready", serverAddr = "ready.example.com")
        val blocked = async {
            manager.ensureReadyVisitor(
                blockedServer,
                credentials(),
                null,
                manager.captureConfigLease(blockedServer.id),
            )
        }
        runCurrent()

        val ready = async {
            manager.ensureReadyVisitor(
                readyServer,
                credentials(),
                null,
                manager.captureConfigLease(readyServer.id),
            )
        }
        runCurrent()

        assertTrue(ready.isCompleted)
        assertFalse(blocked.isCompleted)
        firstStartGate.complete(Unit)
        assertNotEquals(ready.await().generation.serverId, blocked.await().generation.serverId)
    }

    @Test
    fun convertsBindExceptionAndTypedBindPortConflictToLocalPortError() = runTest {
        val failures = listOf(
            BindException("synthetic bind failure"),
            ManagerMessageAccessFailsException(
                KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT),
            ),
        )

        failures.forEachIndexed { index, failure ->
            val client = ControllableFrpcStcpVisitorClient(localPort = 5096).apply {
                onEnsure = { _, _, _ -> throw failure }
            }
            val manager = manager(client, ScriptedHealthProbeFactory())
            val server = stcpServer(id = "bind-$index")

            val throwable = runCatching {
                manager.ensureReadyVisitor(
                    server,
                    credentials(),
                    null,
                    manager.captureConfigLease(server.id),
                )
            }.exceptionOrNull()
            runCurrent()

            assertTrue(throwable is LocalPortInUseException)
            assertEquals(4096, (throwable as LocalPortInUseException).localPort)
            assertEquals(
                OpenCodeFailure.OperationRejected(OpenCodeOperationRejectionReason.LocalPortInUse),
                throwable.failure,
            )
            assertSame(failure, throwable.cause)
            assertEquals(1, client.ensureCount)
            assertEquals(listOf("session-1"), client.stoppedSessions)
        }
    }

    @Test
    fun predecessorBindRetryBudgetIncludesDelayEnsureAndWait() = runTest {
        val retryBudgetMillis = 100L
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val readyTimeouts = mutableListOf<Long>()
        client.onWaitReady = { _, _, _, timeoutMillis, _ -> readyTimeouts += timeoutMillis }
        val manager = manager(
            client,
            ScriptedHealthProbeFactory(),
            predecessorBindRetryTimeoutMillis = retryBudgetMillis,
        )
        val server = stcpServer()
        manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
        manager.invalidateConfiguration(server.id)
        runCurrent()
        val firstBindFailure = ManagerMessageAccessFailsException(
            KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT),
        )
        val latestBindFailure = ManagerMessageAccessFailsException(
            KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT),
        )
        client.onEnsure = { _, _, call ->
            when (call) {
                2 -> throw firstBindFailure
                3 -> {
                    delay(20)
                    throw latestBindFailure
                }
                4 -> delay(20)
                else -> throw AssertionError("Bind retry continued past its total budget")
            }
        }
        client.onWaitReady = { _, _, _, timeoutMillis, call ->
            if (call != 2) throw AssertionError("Unexpected readiness call after retry timeout")
            readyTimeouts += timeoutMillis
            delay(1_000)
        }

        val startedAt = testScheduler.currentTime
        val throwable = runCatching {
            manager.ensureReadyVisitor(
                server,
                credentials(),
                null,
                manager.captureConfigLease(server.id),
            )
        }.exceptionOrNull()
        val elapsedMillis = testScheduler.currentTime - startedAt
        runCurrent()

        assertTrue(throwable is LocalPortInUseException)
        assertSame(latestBindFailure, throwable?.cause)
        assertTrue(elapsedMillis <= retryBudgetMillis)
        assertEquals(retryBudgetMillis, elapsedMillis)
        val ensureCountAtTimeout = client.ensureCount
        val readyCountAtTimeout = client.waitReadyCount
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ensureCountAtTimeout, client.ensureCount)
        assertEquals(readyCountAtTimeout, client.waitReadyCount)
        assertEquals(4, ensureCountAtTimeout)
        assertEquals(2, readyCountAtTimeout)
        assertEquals(listOf(1_000L, 30L), readyTimeouts)
        assertEquals(listOf("session-1", "session-2"), client.stoppedSessions)
    }

    @Test
    fun retryEnsurePastDeadlineSkipsWaitAndFurtherAttempts() = runTest {
        val retryBudgetMillis = 100L
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val manager = manager(
            client,
            ScriptedHealthProbeFactory(),
            predecessorBindRetryTimeoutMillis = retryBudgetMillis,
        )
        val server = stcpServer()
        manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
        manager.invalidateConfiguration(server.id)
        runCurrent()
        val bindFailure = ManagerMessageAccessFailsException(
            KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT),
        )
        client.onEnsure = { _, _, call ->
            when (call) {
                2 -> throw bindFailure
                3 -> withContext(NonCancellable) { delay(150) }
                else -> throw AssertionError("Bind retry continued after its deadline")
            }
        }
        client.onWaitReady = { _, _, _, _, _ ->
            throw AssertionError("Readiness must not run after ensure crosses the retry deadline")
        }

        val startedAt = testScheduler.currentTime
        val throwable = runCatching {
            manager.ensureReadyVisitor(
                server,
                credentials(),
                null,
                manager.captureConfigLease(server.id),
            )
        }.exceptionOrNull()
        val elapsedMillis = testScheduler.currentTime - startedAt
        runCurrent()

        assertTrue(throwable is LocalPortInUseException)
        assertSame(bindFailure, throwable?.cause)
        assertTrue(elapsedMillis > retryBudgetMillis)
        assertEquals(3, client.ensureCount)
        assertEquals(1, client.waitReadyCount)
        val ensureCountAtTimeout = client.ensureCount
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ensureCountAtTimeout, client.ensureCount)
        assertEquals(1, client.waitReadyCount)
    }

    @Test
    fun predecessorRetryPropagatesCancellationIdentity() = runTest {
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val manager = manager(client, ScriptedHealthProbeFactory())
        val server = stcpServer()
        manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
        manager.invalidateConfiguration(server.id)
        runCurrent()
        val bindFailure = ManagerMessageAccessFailsException(
            KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT),
        )
        val cancellation = FrpcManagerTestCancellationException(Unit)
        client.onEnsure = { _, _, call ->
            if (call == 2) throw bindFailure
            throw cancellation
        }

        val throwable = runCatching {
            manager.ensureReadyVisitor(
                server,
                credentials(),
                null,
                manager.captureConfigLease(server.id),
            )
        }.exceptionOrNull()
        runCurrent()

        assertSame(cancellation, throwable)
    }

    @Test
    fun predecessorRetryPropagatesJvmErrorIdentity() = runTest {
        val reported = CompletableDeferred<Throwable>()
        val workerJob = SupervisorJob()
        val workerScope = CoroutineScope(
            workerJob + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> reported.complete(throwable) },
        )
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val manager = manager(client, ScriptedHealthProbeFactory(), workerScope)
        val server = stcpServer()
        manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
        manager.invalidateConfiguration(server.id)
        runCurrent()
        val bindFailure = ManagerMessageAccessFailsException(
            KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT),
        )
        val fatal = FrpcManagerTestJvmError()
        client.onEnsure = { _, _, call ->
            if (call == 2) throw bindFailure
            throw fatal
        }

        val throwable = runCatching {
            manager.ensureReadyVisitor(
                server,
                credentials(),
                null,
                manager.captureConfigLease(server.id),
            )
        }.exceptionOrNull()
        runCurrent()

        assertSame(fatal, throwable)
        assertSame(fatal, reported.await())
        workerJob.cancel()
    }

    @Test
    fun predecessorRetryPrefersNestedJvmErrorOverCancellation() = runTest {
        val reported = CompletableDeferred<Throwable>()
        val workerJob = SupervisorJob()
        val workerScope = CoroutineScope(
            workerJob + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> reported.complete(throwable) },
        )
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val manager = manager(client, ScriptedHealthProbeFactory(), workerScope)
        val server = stcpServer()
        manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
        manager.invalidateConfiguration(server.id)
        runCurrent()
        val bindFailure = ManagerMessageAccessFailsException(
            KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT),
        )
        val fatal = FrpcManagerTestJvmError()
        val cancellation = FrpcManagerTestCancellationException(Unit).apply { initCause(fatal) }
        client.onEnsure = { _, _, call ->
            if (call == 2) throw bindFailure
            throw cancellation
        }

        val throwable = runCatching {
            manager.ensureReadyVisitor(
                server,
                credentials(),
                null,
                manager.captureConfigLease(server.id),
            )
        }.exceptionOrNull()
        runCurrent()

        assertSame(fatal, throwable)
        assertSame(fatal, reported.await())
        workerJob.cancel()
    }

    @Test
    fun cleanupPrefersNestedJvmErrorAndStillUnblocksReplacement() = runTest {
        val reported = CompletableDeferred<Throwable>()
        val workerJob = SupervisorJob()
        val workerScope = CoroutineScope(
            workerJob + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, throwable -> reported.complete(throwable) },
        )
        val fatal = FrpcManagerTestJvmError()
        val cancellation = FrpcManagerTestCancellationException(Unit).apply { initCause(fatal) }
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096).apply {
            onStopSession = { _, call -> if (call == 1) throw cancellation }
        }
        val manager = manager(client, ScriptedHealthProbeFactory(), workerScope)
        val server = stcpServer()
        val initial = manager.ensureReadyVisitor(
            server,
            credentials(),
            null,
            manager.captureConfigLease(server.id),
        )

        manager.invalidateConfiguration(server.id)
        runCurrent()

        assertSame(fatal, reported.await())
        val replacement = manager.ensureReadyVisitor(
            server,
            credentials(),
            null,
            manager.captureConfigLease(server.id),
        )
        assertNotEquals(initial.generation, replacement.generation)
        workerJob.cancel()
    }

    @Test
    fun zeroPredecessorBindRetryBudgetDoesNotRetry() = runTest {
        val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
        val manager = manager(
            client,
            ScriptedHealthProbeFactory(),
            predecessorBindRetryTimeoutMillis = 0,
        )
        val server = stcpServer()
        manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
        manager.invalidateConfiguration(server.id)
        runCurrent()
        val bindFailure = ManagerMessageAccessFailsException(
            KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT),
        )
        client.onEnsure = { _, _, _ -> throw bindFailure }

        val startedAt = testScheduler.currentTime
        val throwable = runCatching {
            manager.ensureReadyVisitor(
                server,
                credentials(),
                null,
                manager.captureConfigLease(server.id),
            )
        }.exceptionOrNull()
        val elapsedMillis = testScheduler.currentTime - startedAt
        runCurrent()

        assertTrue(throwable is LocalPortInUseException)
        assertSame(bindFailure, throwable?.cause)
        assertEquals(0L, elapsedMillis)
        assertEquals(2, client.ensureCount)
        assertEquals(1, client.waitReadyCount)
    }

    @Test
    fun predecessorRetryIgnoresNonBindTypedMessageAndCyclicFailures() = runTest {
        val cycleStart = IllegalStateException()
        val cycleNext = IllegalArgumentException()
        cycleStart.initCause(cycleNext)
        cycleNext.initCause(cycleStart)
        val failures = listOf(
            ManagerMessageAccessFailsException(
                KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.LISTENER_BIND_FAILED),
            ),
            ManagerMessageAccessFailsException(
                KotlinFrpcStcpVisitorException(KotlinFrpcStcpVisitorFailure.CONTROL_FAILED),
            ),
            IllegalStateException("address already in use"),
            cycleStart,
        )

        failures.forEachIndexed { index, failure ->
            val client = ControllableFrpcStcpVisitorClient(localPort = 5096)
            val manager = manager(client, ScriptedHealthProbeFactory())
            val server = stcpServer(id = "non-bind-$index")
            manager.ensureReadyVisitor(server, credentials(), null, manager.captureConfigLease(server.id))
            manager.invalidateConfiguration(server.id)
            runCurrent()
            client.onEnsure = { _, _, _ -> throw failure }

            val startedAt = testScheduler.currentTime
            val throwable = runCatching {
                manager.ensureReadyVisitor(
                    server,
                    credentials(),
                    null,
                    manager.captureConfigLease(server.id),
                )
            }.exceptionOrNull()
            val elapsedMillis = testScheduler.currentTime - startedAt
            runCurrent()

            assertFalse(throwable is LocalPortInUseException)
            assertEquals(failure.javaClass, throwable?.javaClass)
            assertEquals(0L, elapsedMillis)
            assertEquals(2, client.startCount)
            assertEquals(2, client.ensureCount)
            assertEquals(1, client.waitReadyCount)
            assertEquals(listOf("session-1", "session-2"), client.stoppedSessions)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        client: ControllableFrpcStcpVisitorClient,
        probes: ScriptedHealthProbeFactory,
        scope: CoroutineScope = backgroundScope,
        predecessorBindRetryTimeoutMillis: Long = 100,
        monotonicTimeMillis: () -> Long = { testScheduler.currentTime },
    ): FrpcStcpVisitorManager = FrpcStcpVisitorManager(
        client = client,
        healthProbeFactory = probes,
        readinessPolicy = FrpcStcpReadinessPolicy(
            nativeReadyTimeoutMillis = 1_000,
            totalTimeoutMillis = 10_000,
            attemptTimeoutMillis = 1_000,
            initialRetryDelayMillis = 10,
            maxRetryDelayMillis = 100,
            predecessorBindRetryTimeoutMillis = predecessorBindRetryTimeoutMillis,
        ),
        scope = scope,
        monotonicTimeMillis = monotonicTimeMillis,
    )

    private fun credentials() = FrpcStcpVisitorCredentials(
        authToken = null,
        secretKey = "stcp-secret",
    )

    private fun stcpServer(
        id: String = "remote",
        serverAddr: String = "frps.example.com",
    ): ServerConfig = ServerConfig(
        id = id,
        name = "Remote",
        baseUrl = "https://opencode.example.test:8443/opencode/",
        frpcStcpVisitor = ServerFrpcStcpVisitorConfig(
            serverAddr = serverAddr,
            serverPort = 7000,
            serverName = "opencode_stcp",
            secretKeyKey = "server-$id-frpc-stcp-secret-key",
            bindPort = 4096,
        ),
    )
}

private class FrpcManagerTestJvmError : Error()

private class FrpcManagerTestCancellationException(
    @Suppress("UNUSED_PARAMETER") identity: Unit,
) : kotlinx.coroutines.CancellationException()

private class ManagerMessageAccessFailsException(cause: Throwable) : RuntimeException(null, cause) {
    override val message: String?
        get() = throw AssertionError("Throwable.message must not be read")
}

private class ScriptedHealthProbeFactory(
    private val onProbe: suspend (attempt: Int, server: ServerConfig) -> ServerHealthDto = { _, _ ->
        ServerHealthDto(healthy = true, version = "1.2.3")
    },
) : OpenCodeHealthProbeFactory {
    var probeCount = 0
        private set
    val passwords = mutableListOf<String?>()

    override fun create(
        server: ServerConfig,
        password: String?,
        effectiveBaseUrl: String,
    ): OpenCodeHealthProbe {
        passwords += password
        return OpenCodeHealthProbe {
            probeCount += 1
            onProbe(probeCount, server)
        }
    }
}

private class ControllableFrpcStcpVisitorClient(
    private val localPort: Int,
) : FrpcStcpVisitorClient {
    var startCount = 0
        private set
    var ensureCount = 0
        private set
    var waitReadyCount = 0
        private set
    var getStateCount = 0
        private set
    val startedConfigs = mutableListOf<FrpcSessionConfig>()
    val visitorConfigs = mutableListOf<FrpcStcpVisitorConfig>()
    val stoppedSessions = mutableListOf<String>()
    var onStart: suspend (FrpcSessionConfig, Int) -> Unit = { _, _ -> }
    var onEnsure: suspend (String, FrpcStcpVisitorConfig, Int) -> Unit = { _, _, _ -> }
    var onWaitReady: suspend (String, String, Long, Long, Int) -> Unit = { _, _, _, _, _ -> }
    var onGetState: suspend (String, Int) -> Unit = { _, _ -> }
    var onStopSession: suspend (String, Int) -> Unit = { _, _ -> }
    var currentControlEpoch = 1L
    var sessionPhase = "open"
    var visitorPhase = "ready"
    var listenerBound = true
    var reportedDesiredRevision: Long? = null
    var reportedBoundControlEpoch: Long? = null
    private var currentDesiredRevision = 0L
    private var currentVisitorName: String? = null

    override suspend fun startSession(config: FrpcSessionConfig): String {
        startCount += 1
        startedConfigs += config
        onStart(config, startCount)
        return "session-$startCount"
    }

    override suspend fun ensureVisitor(
        sessionId: String,
        visitor: FrpcStcpVisitorConfig,
    ): FrpcEnsureVisitorResult {
        ensureCount += 1
        visitorConfigs += visitor
        onEnsure(sessionId, visitor, ensureCount)
        currentDesiredRevision = ensureCount.toLong()
        currentVisitorName = visitor.name
        return FrpcEnsureVisitorResult(
            bindPort = localPort,
            desiredRevision = currentDesiredRevision,
        )
    }

    override suspend fun waitVisitorReady(
        sessionId: String,
        visitorName: String,
        desiredRevision: Long,
        timeoutMillis: Long,
    ): FrpcVisitorReadyResult {
        waitReadyCount += 1
        onWaitReady(sessionId, visitorName, desiredRevision, timeoutMillis, waitReadyCount)
        return FrpcVisitorReadyResult(
            name = visitorName,
            desiredRevision = desiredRevision,
            phase = "ready",
            listenerBound = true,
            boundControlEpoch = currentControlEpoch,
        )
    }

    override suspend fun stopVisitor(sessionId: String, visitorName: String) = Unit

    override suspend fun stopSession(sessionId: String, timeoutMillis: Long): FrpcStopSessionResult {
        stoppedSessions += sessionId
        onStopSession(sessionId, stoppedSessions.size)
        return FrpcStopSessionResult(sessionId = sessionId, phase = "closed")
    }

    override suspend fun getState(sessionId: String): FrpcStcpVisitorState {
        getStateCount += 1
        onGetState(sessionId, getStateCount)
        val visitorName = currentVisitorName
        return FrpcStcpVisitorState(
            sessionId = sessionId,
            phase = sessionPhase,
            controlEpoch = currentControlEpoch,
            visitors = if (visitorName == null) {
                emptyMap()
            } else {
                mapOf(
                    visitorName to FrpcVisitorRuntimeState(
                        desiredRevision = reportedDesiredRevision ?: currentDesiredRevision,
                        phase = visitorPhase,
                        listenerBound = listenerBound,
                        boundControlEpoch = reportedBoundControlEpoch ?: currentControlEpoch,
                    ),
                )
            },
        )
    }
}
