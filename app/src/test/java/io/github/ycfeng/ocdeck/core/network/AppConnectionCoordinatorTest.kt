package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppConnectionCoordinatorTest {
    @Test
    fun defaultHealthCheckerDiscardsAResultAndRechecksB() = runTest {
        val a = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val b = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val identities = ArrayDeque(listOf(a, b, b))
        val checked = mutableListOf<ServerTransportIdentity>()
        val checker = DefaultForegroundHealthChecker {
            val identity = identities.removeFirst()
            ForegroundHealthConnection(identity) { checked += identity }
        }

        val result = checker.check("server")

        assertEquals(b, result.getOrThrow())
        assertEquals(listOf(a, b), checked)
    }

    @Test
    fun defaultHealthCheckerRetriesBWhenAHealthFailsAfterTransportChanges() = runTest {
        val a = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val b = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val identities = ArrayDeque(listOf(a, b, b))
        val checked = mutableListOf<ServerTransportIdentity>()
        val checker = DefaultForegroundHealthChecker {
            val identity = identities.removeFirst()
            ForegroundHealthConnection(identity) {
                checked += identity
                if (identity == a) throw IllegalStateException("old health connection failed")
            }
        }

        val result = checker.check("server")

        assertEquals(b, result.getOrThrow())
        assertEquals(listOf(a, b), checked)
    }

    @Test
    fun defaultHealthCheckerPropagatesFailureWhenTransportIsUnchanged() = runTest {
        val identity = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val original = IllegalStateException("health failed")
        var connectionCalls = 0
        var healthCalls = 0
        val checker = DefaultForegroundHealthChecker {
            connectionCalls += 1
            ForegroundHealthConnection(identity) {
                healthCalls += 1
                throw original
            }
        }

        val failure = checker.check("server").exceptionOrNull()

        assertSame(original, failure)
        assertEquals(2, connectionCalls)
        assertEquals(1, healthCalls)
    }

    @Test
    fun defaultHealthCheckerDoesNotConvertJvmErrorToResultFailure() = runTest {
        val fatal = AppConnectionTestJvmError()
        val checker = DefaultForegroundHealthChecker { throw fatal }

        try {
            checker.check("server")
            throw AssertionError("JVM Error was not propagated")
        } catch (actual: AppConnectionTestJvmError) {
            assertSame(fatal, actual)
        }
    }

    @Test
    fun rapidForegroundCallsShareOneServerCheck() = runTest {
        val gate = CompletableDeferred<Unit>()
        var checks = 0
        val controller = RecordingForegroundController(setOf("server"))
        val coordinator = AppConnectionCoordinator(
            controller = controller,
            healthChecker = ForegroundHealthChecker {
                checks += 1
                gate.await()
                Result.success(testTransport())
            },
            scope = backgroundScope,
        )

        coordinator.onForeground()
        coordinator.onForeground()
        runCurrent()

        assertEquals(1, checks)
        assertEquals(1, coordinator.activeFlightCount())

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("server"), controller.readyServers)
        assertEquals(0, coordinator.activeFlightCount())
    }

    @Test
    fun oneServerCheckRebuildsAllDesiredSlotsWhenControlEpochChanges() = runTest {
        val oldIdentity = testTransport(configEpoch = 1L, controlEpoch = 1L)
        val newIdentity = testTransport(configEpoch = 1L, controlEpoch = 2L)
        val provider = MutableSseConnectionProvider(oldIdentity)
        val factory = RecordingSseEventSourceFactory()
        val environment = eventClientEnvironment(backgroundScope, provider, factory)
        environment.client.connectProject("server", "E:/work/one")
        environment.client.connectProject("server", "E:/work/two")
        environment.client.connectGlobal("server")
        runCurrent()
        factory.sources.forEach(RecordingSseEventSource::open)
        runCurrent()
        val oldGenerations = listOf(
            environment.client.projectLifecycleState("server", "E:/work/one")!!.generation,
            environment.client.projectLifecycleState("server", "E:/work/two")!!.generation,
            environment.client.globalLifecycleState("server")!!.generation,
        )

        val gate = CompletableDeferred<Unit>()
        var checks = 0
        val coordinator = AppConnectionCoordinator(
            controller = environment.client,
            healthChecker = ForegroundHealthChecker {
                checks += 1
                gate.await()
                Result.success(newIdentity)
            },
            scope = backgroundScope,
        )
        provider.identity = newIdentity

        coordinator.onForeground()
        coordinator.onForeground()
        runCurrent()
        assertEquals(1, checks)
        gate.complete(Unit)
        runCurrent()

        assertEquals(6, factory.sources.size)
        val states = listOf(
            environment.client.projectLifecycleState("server", "E:/work/one")!!,
            environment.client.projectLifecycleState("server", "E:/work/two")!!,
            environment.client.globalLifecycleState("server")!!,
        )
        assertTrue(states.zip(oldGenerations).all { (state, oldGeneration) -> state.generation > oldGeneration })
        assertTrue(states.all { it.transportIdentity == newIdentity })
    }

    @Test
    fun foregroundOnOpenProjectsKeepsSourcesAndStartsOneCalibrationPerProject() = runTest {
        val identity = testTransport()
        val provider = MutableSseConnectionProvider(identity)
        val factory = RecordingSseEventSourceFactory()
        val loader = ScriptedProjectSnapshotLoader { serverId, directory, workspace, _ ->
            Result.success(loadedSnapshot(serverId, directory, workspace, identity))
        }
        val environment = eventClientEnvironment(backgroundScope, provider, factory, loader)
        environment.client.connectProject("server", "E:/work/one")
        environment.client.connectProject("server", "E:/work/two")
        runCurrent()
        factory.sources.forEach(RecordingSseEventSource::open)
        runCurrent()
        assertEquals(2, loader.calls)

        val coordinator = AppConnectionCoordinator(
            controller = environment.client,
            healthChecker = ForegroundHealthChecker { Result.success(identity) },
            scope = backgroundScope,
        )
        coordinator.onForeground()
        runCurrent()

        assertEquals(2, factory.sources.size)
        assertEquals(4, loader.calls)
    }
}

private class AppConnectionTestJvmError : Error()

private class RecordingForegroundController(
    private val desired: Set<String>,
) : ForegroundConnectionController {
    val readyServers = mutableListOf<String>()

    override fun desiredServerIds(): Set<String> = desired

    override fun onForegroundConnectionReady(serverId: String, transportIdentity: ServerTransportIdentity) {
        readyServers += serverId
    }
}
