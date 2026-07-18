package io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control

import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.FrpContractFixtures
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.crypto.FrpTimestampAuth
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.crypto.FrpV1Cfb
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.FrpMessage
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.FrpWireV1
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.FrpWireV2
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.Login
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.LoginResp
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.NewWorkConn
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.Ping
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.Pong
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.ReqWorkConn
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.StartWorkConn
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpMuxConnection
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpMuxConnector
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpMuxStream
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpTransportException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpTransportFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class FrpControlClientTest {
    @Test
    fun firstLoginFailureIsTerminalAndDoesNotExposeServerText() = runBlocking {
        val marker = "unsafe-login-detail-marker"
        val controlStream = ScriptedMuxStream(
            FrpWireV1.encodeMessage(LoginResp(error = marker)),
        )
        val mux = FakeMuxConnection(listOf(controlStream))
        val connector = RecordingConnector { _ -> mux }
        val parentScope = newParentScope()
        val client = newClient(
            config = basicConfig(),
            connector = connector,
            parentScope = parentScope,
            generation = 11L,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Failed }
            client.awaitStopped()

            val state = client.state.value as FrpControlState.Failed
            assertEquals(11L, state.generation)
            assertEquals(FrpControlFailure.LOGIN_REJECTED, state.failure)
            assertEquals(1, connector.connectCount.get())
            assertEquals(1, mux.closeCount.get())
            assertFalse(state.toString().contains(marker))
            assertFalse(client.toString().contains(marker))
            assertFalse(client.toString().contains(FrpContractFixtures.syntheticToken))
        } finally {
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun connectorJvmErrorKeepsIdentityAndPublishesOnlyTypedState() = runBlocking {
        val error = AssertionError("control-error-identity-marker")
        val captured = CompletableDeferred<Throwable>()
        val parentScope = newParentScope(
            CoroutineExceptionHandler { _, failure -> captured.complete(failure) },
        )
        val client = newClient(
            config = basicConfig(),
            connector = FrpMuxConnector { throw error },
            parentScope = parentScope,
            generation = 12L,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            assertSame(error, withTimeout(5_000L) { captured.await() })
            client.awaitStopped()

            val state = client.state.value as FrpControlState.Failed
            assertEquals(FrpControlFailure.TRANSPORT_FAILED, state.failure)
            assertFalse(state.toString().contains(error.message.orEmpty()))
        } finally {
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun reconnectCarriesPriorRunIdAndAdvancesAuthoritativeIdentity() = runBlocking {
        val fixture = v1Fixture()
        val firstControl = stableV1ControlStream()
        val secondControl = stableV1ControlStream()
        val firstMux = FakeMuxConnection(listOf(firstControl))
        val secondMux = FakeMuxConnection(listOf(secondControl))
        val connector = RecordingConnector { index -> if (index == 0) firstMux else secondMux }
        val delayer = ManualDelayer()
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                reconnectInitialDelayMillis = 100L,
                reconnectMaximumDelayMillis = 20_000L,
            ),
            connector = connector,
            parentScope = parentScope,
            generation = 21L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            delayer = delayer,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Open }
            val firstOpen = client.state.value as FrpControlState.Open
            assertEquals(FrpControlIdentity(21L, 1L, 1L), firstOpen.identity)

            firstMux.fail(FrpTransportException(FrpTransportFailure.READ_FAILED))
            waitUntil { client.state.value is FrpControlState.Retrying }
            val retrying = client.state.value as FrpControlState.Retrying
            assertEquals(1, retrying.attempt)
            assertEquals(100L, retrying.delayMillis)
            assertEquals(FrpControlFailure.TRANSPORT_FAILED, retrying.failure)
            delayer.takeCall(100L).release.complete(Unit)

            waitUntil {
                val state = client.state.value
                state is FrpControlState.Open && state.identity.controlEpoch == 2L
            }
            val secondOpen = client.state.value as FrpControlState.Open
            assertEquals(FrpControlIdentity(21L, 2L, 2L), secondOpen.identity)
            waitUntil { secondControl.writtenBytes().size >= FrpContractFixtures.bytes("wire-v1-login-token").size }
            val secondLogin = FrpWireV1.readMessage(
                ByteArrayInputStream(secondControl.writtenBytes()),
            ) as Login
            assertEquals("run_fixture_001", secondLogin.runId)
            assertEquals(1, secondLogin.poolCount)
            assertEquals(
                FrpTimestampAuth.key(FrpContractFixtures.syntheticToken, secondLogin.timestamp),
                secondLogin.privilegeKey,
            )
            assertEquals(2, connector.connectCount.get())
        } finally {
            client.close()
            withTimeout(5_000L) { client.awaitStopped() }
            parentScope.cancel()
        }
    }

    @Test
    fun successfulOpenResetsBackoffAcrossIndependentDisconnects() = runBlocking {
        val fixture = v1Fixture()
        val firstMux = FakeMuxConnection(listOf(stableV1ControlStream()))
        val secondMux = FakeMuxConnection(listOf(stableV1ControlStream()))
        val connector = RecordingConnector { index -> if (index == 0) firstMux else secondMux }
        val delayer = ManualDelayer()
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                reconnectInitialDelayMillis = 100L,
                reconnectMaximumDelayMillis = 20_000L,
            ),
            connector = connector,
            parentScope = parentScope,
            generation = 211L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            delayer = delayer,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Open }
            firstMux.fail(FrpTransportException(FrpTransportFailure.READ_FAILED))
            waitUntil { client.state.value is FrpControlState.Retrying }
            val firstRetry = client.state.value as FrpControlState.Retrying
            assertEquals(1, firstRetry.attempt)
            assertEquals(100L, firstRetry.delayMillis)
            delayer.takeCall(100L).release.complete(Unit)

            waitUntil {
                val state = client.state.value
                state is FrpControlState.Open && state.identity.controlEpoch == 2L
            }
            secondMux.fail(FrpTransportException(FrpTransportFailure.READ_FAILED))
            waitUntil { client.state.value is FrpControlState.Retrying }
            val secondRetry = client.state.value as FrpControlState.Retrying

            assertEquals(1, secondRetry.attempt)
            assertEquals(100L, secondRetry.delayMillis)
            assertEquals(2, connector.connectCount.get())
        } finally {
            client.close()
            withTimeout(5_000L) { client.awaitStopped() }
            parentScope.cancel()
        }
    }

    @Test
    fun reconnectBackoffCapsAtTwentySeconds() = runBlocking {
        val fixture = v1Fixture()
        val muxes = Collections.synchronizedList(mutableListOf<FakeMuxConnection>())
        val connector = RecordingConnector { _ ->
            FakeMuxConnection(
                streams = listOf(ScriptedMuxStream(FrpContractFixtures.bytes("wire-v1-login-response"))),
                initialFailure = FrpTransportException(FrpTransportFailure.READ_FAILED),
            ).also(muxes::add)
        }
        val delayer = RecordingStopDelayer(stopAfter = 7)
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                reconnectInitialDelayMillis = 1_000L,
                reconnectMaximumDelayMillis = 20_000L,
            ),
            connector = connector,
            parentScope = parentScope,
            generation = 22L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            delayer = delayer,
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                beforeOpenPublish = {
                    waitUntil {
                        synchronized(muxes) { muxes.lastOrNull()?.closeCount?.get() == 1 }
                    }
                },
            ),
        )
        try {
            client.start()
            waitUntil { delayer.snapshot().size == 7 }
            waitUntil { client.state.value is FrpControlState.Closed }
            client.awaitStopped()

            assertEquals(
                listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 20_000L, 20_000L),
                delayer.snapshot(),
            )
            assertEquals(7, connector.connectCount.get())
        } finally {
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun heartbeatUsesPongLivenessAndReconnectsOnTypedTimeout() = runBlocking {
        val fixture = v1Fixture()
        val controlStream = ScriptedMuxStream(FrpContractFixtures.bytes("wire-v1-login-response"))
        val mux = FakeMuxConnection(listOf(controlStream))
        val delayer = ManualDelayer()
        val monotonicTime = AtomicLong(0L)
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                heartbeatIntervalMillis = 100L,
                heartbeatTimeoutMillis = 150L,
                reconnectInitialDelayMillis = 100L,
                reconnectMaximumDelayMillis = 20_000L,
            ),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 31L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            monotonicTimeSource = FrpMonotonicTimeSource { monotonicTime.get() },
            delayer = delayer,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Open }
            waitUntil {
                decodeV1ClientControlMessages(controlStream.writtenBytes()).any { it is Ping }
            }
            val firstRound = delayer.takeCalls(count = 2)
            assertEquals(listOf(100L, 150L), firstRound.map(DelayCall::millis).sorted())

            monotonicTime.set(100L)
            val encryptedPong = encryptV1ServerMessages(listOf(Pong()))
            controlStream.appendInput(encryptedPong)
            waitUntil {
                controlStream.totalRead.get() >=
                    FrpContractFixtures.bytes("wire-v1-login-response").size + encryptedPong.size
            }
            delay(25L)
            firstRound.forEach { it.release.complete(Unit) }
            val secondRound = delayer.takeCalls(count = 2, expectedMillis = 100L)

            monotonicTime.set(300L)
            secondRound.forEach { it.release.complete(Unit) }
            waitUntil { client.state.value is FrpControlState.Retrying }
            val retrying = client.state.value as FrpControlState.Retrying
            assertEquals(FrpControlFailure.HEARTBEAT_TIMEOUT, retrying.failure)

            val clientMessages = decodeV1ClientControlMessages(controlStream.writtenBytes())
            val pings = clientMessages.filterIsInstance<Ping>()
            assertTrue(pings.isNotEmpty())
            assertTrue(pings.all { ping ->
                ping.timestamp == fixture.login.timestamp &&
                    ping.privilegeKey == FrpTimestampAuth.key(
                        FrpContractFixtures.syntheticToken,
                        ping.timestamp,
                    )
            })
        } finally {
            client.close()
            withTimeout(5_000L) { client.awaitStopped() }
            parentScope.cancel()
        }
    }

    @Test
    fun v1AndV2WorkHandshakesUseCurrentRunEpochAndTransport() = runBlocking {
        listOf(v1Fixture(), v2AesFixture()).forEachIndexed { index, fixture ->
            val start = StartWorkConn(
                proxyName = "proxy-${fixture.protocol.name}",
                srcAddr = "source-fixture",
                srcPort = 1234,
                dstAddr = "destination-fixture",
                dstPort = 4321,
            )
            val workInput = when (fixture.protocol) {
                FrpWireProtocol.V1 -> FrpWireV1.encodeMessage(start)
                FrpWireProtocol.V2 -> FrpWireV2.encodeMessage(start)
            }
            val controlStream = ScriptedMuxStream(fixture.fullServerInput)
            val workStream = ScriptedMuxStream(workInput)
            val mux = FakeMuxConnection(listOf(controlStream, workStream))
            val observed = CompletableDeferred<ObservedWork>()
            val parentScope = newParentScope()
            val generation = 41L + index
            val client = newClient(
                config = fixture.config,
                connector = RecordingConnector { _ -> mux },
                parentScope = parentScope,
                generation = generation,
                workHandler = FrpWorkConnectionHandler { connection ->
                    observed.complete(ObservedWork(connection.identity, connection.start, connection.toString()))
                    connection.close()
                },
                unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
                secureRandom = fixture.secureRandom(),
            )
            try {
                client.start()
                val work = withTimeout(5_000L) { observed.await() }
                assertEquals(FrpControlIdentity(generation, 1L, 1L), work.identity)
                assertEquals(start, work.start)
                assertFalse(work.summary.contains(start.proxyName))
                assertFalse(work.summary.contains(start.srcAddr))

                val newWork = decodeWorkRequest(fixture.protocol, workStream.writtenBytes())
                assertEquals("run_fixture_001", newWork.runId)
                assertEquals(fixture.login.timestamp, newWork.timestamp)
                assertEquals(
                    FrpTimestampAuth.key(FrpContractFixtures.syntheticToken, newWork.timestamp),
                    newWork.privilegeKey,
                )
                waitUntil { workStream.resetCount.get() == 1 }
            } finally {
                client.close()
                withTimeout(5_000L) { client.awaitStopped() }
                parentScope.cancel()
            }
        }
    }

    @Test
    fun longLivedWorkHandlersRunConcurrentlyAndAreJoinedOnShutdown() = runBlocking {
        val fixture = v1Fixture()
        val requests = encryptV1ServerMessages(listOf(ReqWorkConn(), ReqWorkConn()))
        val controlStream = ScriptedMuxStream(
            FrpContractFixtures.bytes("wire-v1-login-response") + requests,
        )
        val firstWork = ScriptedMuxStream(FrpWireV1.encodeMessage(StartWorkConn(proxyName = "first")))
        val secondWork = ScriptedMuxStream(FrpWireV1.encodeMessage(StartWorkConn(proxyName = "second")))
        val mux = FakeMuxConnection(listOf(controlStream, firstWork, secondWork))
        val startedHandlers = Channel<String>(capacity = 2)
        val activeHandlers = AtomicInteger(0)
        val maximumHandlers = AtomicInteger(0)
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                workWorkerCount = 1,
                maxActiveWorkConnections = 2,
            ),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 411L,
            workHandler = FrpWorkConnectionHandler { connection ->
                val active = activeHandlers.incrementAndGet()
                maximumHandlers.updateAndGet { current -> maxOf(current, active) }
                startedHandlers.send(connection.start.proxyName)
                try {
                    awaitCancellation()
                } finally {
                    activeHandlers.decrementAndGet()
                }
            },
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            val delivered = withTimeout(5_000L) {
                setOf(startedHandlers.receive(), startedHandlers.receive())
            }

            assertEquals(setOf("first", "second"), delivered)
            assertEquals(2, maximumHandlers.get())
            assertEquals(2, client.diagnostics().activeWorkConnections)

            client.close()
            withTimeout(5_000L) { client.awaitStopped() }

            assertEquals(0, activeHandlers.get())
            assertEquals(0, client.diagnostics().activeWorkConnections)
            assertEquals(1, firstWork.resetCount.get())
            assertEquals(1, secondWork.resetCount.get())
        } finally {
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun workHandshakeTimeoutClosesOnlyThatStreamAndNextWorkStillSucceeds() = runBlocking {
        val fixture = v1Fixture()
        val requests = encryptV1ServerMessages(listOf(ReqWorkConn(), ReqWorkConn()))
        val controlStream = ScriptedMuxStream(
            FrpContractFixtures.bytes("wire-v1-login-response") + requests,
        )
        val stalledWork = ScriptedMuxStream()
        val nextWork = ScriptedMuxStream(FrpWireV1.encodeMessage(StartWorkConn(proxyName = "next")))
        val mux = FakeMuxConnection(listOf(controlStream, stalledWork, nextWork))
        val delivered = CompletableDeferred<String>()
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                workWorkerCount = 1,
                maxActiveWorkConnections = 1,
                workHandshakeTimeoutMillis = 50L,
            ),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 412L,
            workHandler = FrpWorkConnectionHandler { connection ->
                delivered.complete(connection.start.proxyName)
                connection.close()
            },
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            assertEquals("next", withTimeout(5_000L) { delivered.await() })
            waitUntil { stalledWork.resetCount.get() == 1 && nextWork.resetCount.get() == 1 }

            assertTrue(client.state.value is FrpControlState.Open)
            assertEquals(1L, client.diagnostics().workFailureCount)
            assertEquals(0, client.diagnostics().activeWorkConnections)
        } finally {
            client.close()
            withTimeout(5_000L) { client.awaitStopped() }
            parentScope.cancel()
        }
    }

    @Test
    fun handlerLocalTimeoutCountsOneWorkFailureAndKeepsControlOpen() = runBlocking {
        val fixture = v1Fixture()
        val requests = encryptV1ServerMessages(listOf(ReqWorkConn(), ReqWorkConn()))
        val controlStream = ScriptedMuxStream(
            FrpContractFixtures.bytes("wire-v1-login-response") + requests,
        )
        val firstWork = ScriptedMuxStream(FrpWireV1.encodeMessage(StartWorkConn(proxyName = "first")))
        val secondWork = ScriptedMuxStream(FrpWireV1.encodeMessage(StartWorkConn(proxyName = "second")))
        val mux = FakeMuxConnection(listOf(controlStream, firstWork, secondWork))
        val handlerCalls = AtomicInteger(0)
        val delivered = CompletableDeferred<String>()
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                workWorkerCount = 1,
                maxActiveWorkConnections = 1,
            ),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 413L,
            workHandler = FrpWorkConnectionHandler { connection ->
                if (handlerCalls.incrementAndGet() == 1) {
                    withTimeout(50L) { awaitCancellation() }
                } else {
                    delivered.complete(connection.start.proxyName)
                    connection.close()
                }
            },
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            assertEquals("second", withTimeout(5_000L) { delivered.await() })
            waitUntil {
                firstWork.resetCount.get() == 1 &&
                    secondWork.resetCount.get() == 1 &&
                    client.diagnostics().activeWorkConnections == 0
            }

            assertTrue(client.state.value is FrpControlState.Open)
            assertEquals(2, handlerCalls.get())
            assertEquals(1L, client.diagnostics().workFailureCount)
        } finally {
            client.close()
            withTimeout(5_000L) { client.awaitStopped() }
            parentScope.cancel()
        }
    }

    @Test
    fun boundedWorkQueueTerminatesOnlyTheOverflowingSession() = runBlocking {
        val fixture = v1Fixture()
        val requests = encryptV1ServerMessages(List(4) { ReqWorkConn() })
        val controlStream = ScriptedMuxStream(
            FrpContractFixtures.bytes("wire-v1-login-response") + requests,
        )
        val mux = FakeMuxConnection(listOf(controlStream))
        val delayer = ManualDelayer()
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                workQueueCapacity = 1,
                workWorkerCount = 1,
                reconnectInitialDelayMillis = 100L,
                reconnectMaximumDelayMillis = 20_000L,
            ),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 51L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            delayer = delayer,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Retrying }
            val state = client.state.value as FrpControlState.Retrying
            assertEquals(FrpControlFailure.QUEUE_OVERFLOW, state.failure)
            assertEquals(1, mux.closeCount.get())
            assertEquals(0, client.diagnostics().activeWorkConnections)
        } finally {
            client.close()
            withTimeout(5_000L) { client.awaitStopped() }
            parentScope.cancel()
        }
    }

    @Test
    fun closeDuringConnectorDialCannotPublishALateOpenState() = runBlocking {
        val fixture = v1Fixture()
        val controlStream = stableV1ControlStream()
        val mux = FakeMuxConnection(listOf(controlStream))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connector = FrpMuxConnector {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
            mux
        }
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config,
            connector = connector,
            parentScope = parentScope,
            generation = 61L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
        )
        val states = Collections.synchronizedList(mutableListOf<FrpControlState>())
        val collector = parentScope.launch { client.state.collect(states::add) }
        try {
            client.start()
            withTimeout(5_000L) { started.await() }
            client.close()
            release.complete(Unit)
            withTimeout(5_000L) { client.awaitStopped() }

            assertTrue(client.state.value is FrpControlState.Closed)
            assertTrue(states.none { it is FrpControlState.Open })
            assertEquals(1, mux.closeCount.get())
        } finally {
            collector.cancel()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun outerLoginTimeoutCancellationKeepsIdentityAndClosesEstablishedResources() = runBlocking {
        val controlStream = ScriptedMuxStream()
        val mux = FakeMuxConnection(listOf(controlStream))
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(
            parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> },
        )
        val client = newClient(
            config = basicConfig().copy(loginTimeoutMillis = 60_000L),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 611L,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        val completion = CompletableDeferred<Throwable?>()
        try {
            val runner = client.start()
            runner.invokeOnCompletion { completion.complete(it) }
            withTimeout(5_000L) { controlStream.readStarted.await() }
            val timeout = newTimeoutCancellation()

            parentJob.cancel(timeout)
            val failure = withTimeout(5_000L) { completion.await() }
            client.awaitStopped()

            assertOriginalCancellation(timeout, failure)
            assertEquals(1, mux.closeCount.get())
            waitUntil { controlStream.resetCount.get() == 1 }
            assertTrue(client.state.value is FrpControlState.Closed)
        } finally {
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun loginBudgetTimeoutMapsToTypedFailureAndClosesEstablishedResources() = runBlocking {
        val controlStream = ScriptedMuxStream()
        val mux = FakeMuxConnection(listOf(controlStream))
        val parentScope = newParentScope()
        val client = newClient(
            config = basicConfig().copy(loginTimeoutMillis = 25L),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 613L,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Failed }
            client.awaitStopped()

            val state = client.state.value as FrpControlState.Failed
            assertEquals(FrpControlFailure.LOGIN_TIMEOUT, state.failure)
            assertEquals(1, mux.closeCount.get())
            waitUntil { controlStream.resetCount.get() == 1 }
        } finally {
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun loginTimeoutWaitsForMuxCleanupJvmErrorBeforeStopping() = runBlocking {
        val error = AssertionError("login cleanup error marker")
        val cleanupReached = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val controlStream = ScriptedMuxStream()
        val mux = FakeMuxConnection(
            streams = listOf(controlStream),
            awaitTerminationFailure = error,
            beforeAwaitTerminationResult = {
                cleanupReached.complete(Unit)
                releaseCleanup.await()
            },
        )
        val parentScope = newParentScope()
        val client = newClient(
            config = basicConfig().copy(loginTimeoutMillis = 100L),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 616L,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        val completion = CompletableDeferred<Throwable?>()
        try {
            val runner = client.start()
            runner.invokeOnCompletion { completion.complete(it) }
            withTimeout(5_000L) { controlStream.readStarted.await() }
            withTimeout(5_000L) { cleanupReached.await() }
            val stoppedWaiter = async { client.awaitStopped() }
            delay(25L)

            assertFalse(stoppedWaiter.isCompleted)
            assertFalse(completion.isCompleted)
            assertEquals(1, controlStream.resetCount.get())

            releaseCleanup.complete(Unit)
            assertSame(error, withTimeout(5_000L) { completion.await() })
            withTimeout(5_000L) { stoppedWaiter.await() }

            val state = client.state.value as FrpControlState.Failed
            assertEquals(FrpControlFailure.TRANSPORT_FAILED, state.failure)
            assertFalse(state.toString().contains(error.message.orEmpty()))
            assertEquals(1, mux.closeCount.get())
        } finally {
            releaseCleanup.complete(Unit)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun loginTimeoutWaitsForCleanupCancellationAndPreservesItsIdentity() = runBlocking {
        val cancellation = CancellationException("login cleanup cancellation marker")
        val releaseReset = CompletableDeferred<Unit>()
        val controlStream = ScriptedMuxStream(
            resetFailure = cancellation,
            resetRelease = releaseReset,
        )
        val mux = FakeMuxConnection(listOf(controlStream))
        val parentScope = newParentScope()
        val client = newClient(
            config = basicConfig().copy(loginTimeoutMillis = 100L),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 617L,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        val completion = CompletableDeferred<Throwable?>()
        try {
            val runner = client.start()
            runner.invokeOnCompletion { completion.complete(it) }
            withTimeout(5_000L) { controlStream.readStarted.await() }
            waitUntil { controlStream.resetCount.get() == 1 }
            val stoppedWaiter = async { client.awaitStopped() }
            delay(25L)

            assertFalse(stoppedWaiter.isCompleted)
            assertFalse(completion.isCompleted)

            releaseReset.complete(Unit)
            val failure = withTimeout(5_000L) { completion.await() }
            withTimeout(5_000L) { stoppedWaiter.await() }

            assertOriginalCancellation(cancellation, failure)
            assertTrue(client.state.value is FrpControlState.Closed)
            assertEquals(1, mux.closeCount.get())
        } finally {
            releaseReset.complete(Unit)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun lateOpenPublicationCannotOverwriteClosedState() = runBlocking {
        val fixture = v1Fixture()
        val mux = FakeMuxConnection(listOf(stableV1ControlStream()))
        val openPublishReached = CountDownLatch(1)
        val releaseOpenPublish = CountDownLatch(1)
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config,
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 614L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                beforeStatePublish = { state ->
                    if (state is FrpControlState.Open) {
                        openPublishReached.countDown()
                        releaseOpenPublish.await()
                    }
                },
            ),
        )
        try {
            client.start()
            assertTrue(withContext(Dispatchers.IO) { openPublishReached.await(5, TimeUnit.SECONDS) })

            val closeCall = async(Dispatchers.IO) { client.close() }
            waitUntil { client.state.value is FrpControlState.Closed }
            releaseOpenPublish.countDown()
            withTimeout(5_000L) { closeCall.await() }
            withTimeout(5_000L) { client.awaitStopped() }

            assertTrue(client.state.value is FrpControlState.Closed)
        } finally {
            releaseOpenPublish.countDown()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun lateRetryPublicationCannotOverwriteClosedState() = runBlocking {
        val fixture = v1Fixture()
        val mux = FakeMuxConnection(listOf(stableV1ControlStream()))
        val retryPublishReached = CountDownLatch(1)
        val releaseRetryPublish = CountDownLatch(1)
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                reconnectInitialDelayMillis = 100L,
                reconnectMaximumDelayMillis = 20_000L,
            ),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 612L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            delayer = ManualDelayer(),
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                beforeStatePublish = { state ->
                    if (state is FrpControlState.Retrying) {
                        retryPublishReached.countDown()
                        releaseRetryPublish.await()
                    }
                },
            ),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Open }
            mux.fail(FrpTransportException(FrpTransportFailure.READ_FAILED))
            assertTrue(withContext(Dispatchers.IO) { retryPublishReached.await(5, TimeUnit.SECONDS) })

            client.close()
            releaseRetryPublish.countDown()
            withTimeout(5_000L) { client.awaitStopped() }

            assertTrue(client.state.value is FrpControlState.Closed)
        } finally {
            releaseRetryPublish.countDown()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun parentCancellationAtBeforeOpenBarrierRejectsLateOpen() = runBlocking {
        val fixture = v1Fixture()
        val mux = FakeMuxConnection(listOf(stableV1ControlStream()))
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(
            parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> },
        )
        val client = newClient(
            config = fixture.config,
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 618L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                beforeOpenPublish = {
                    reached.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                },
            ),
        )
        val states = Collections.synchronizedList(mutableListOf<FrpControlState>())
        val collector = launch { client.state.collect(states::add) }
        val completion = CompletableDeferred<Throwable?>()
        try {
            val runner = client.start()
            runner.invokeOnCompletion { completion.complete(it) }
            withTimeout(5_000L) { reached.await() }
            val cancellation = CancellationException("parent before-open cancellation marker")

            parentJob.cancel(cancellation)
            release.complete(Unit)
            assertOriginalCancellation(cancellation, withTimeout(5_000L) { completion.await() })
            withTimeout(5_000L) { client.awaitStopped() }

            assertTrue(client.state.value is FrpControlState.Closed)
            assertTrue(states.none { it is FrpControlState.Open })
            assertEquals(1, mux.closeCount.get())
        } finally {
            release.complete(Unit)
            collector.cancel()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun parentCancellationAtStatePublishBarrierRejectsLateRetry() = runBlocking {
        val fixture = v1Fixture()
        val mux = FakeMuxConnection(listOf(stableV1ControlStream()))
        val retryPublishReached = CountDownLatch(1)
        val releaseRetryPublish = CountDownLatch(1)
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(
            parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> },
        )
        val client = newClient(
            config = fixture.config.copy(
                reconnectInitialDelayMillis = 100L,
                reconnectMaximumDelayMillis = 20_000L,
            ),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 619L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                beforeStatePublish = { state ->
                    if (state is FrpControlState.Retrying) {
                        retryPublishReached.countDown()
                        releaseRetryPublish.await()
                    }
                },
            ),
        )
        val states = Collections.synchronizedList(mutableListOf<FrpControlState>())
        val collector = launch { client.state.collect(states::add) }
        val completion = CompletableDeferred<Throwable?>()
        try {
            val runner = client.start()
            runner.invokeOnCompletion { completion.complete(it) }
            waitUntil { client.state.value is FrpControlState.Open }
            mux.fail(FrpTransportException(FrpTransportFailure.READ_FAILED))
            assertTrue(withContext(Dispatchers.IO) { retryPublishReached.await(5, TimeUnit.SECONDS) })
            val cancellation = CancellationException("parent state-publish cancellation marker")

            parentJob.cancel(cancellation)
            releaseRetryPublish.countDown()
            assertOriginalCancellation(cancellation, withTimeout(5_000L) { completion.await() })
            withTimeout(5_000L) { client.awaitStopped() }

            assertTrue(client.state.value is FrpControlState.Closed)
            assertTrue(states.none { it is FrpControlState.Retrying })
        } finally {
            releaseRetryPublish.countDown()
            collector.cancel()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun parentCancellationRacingStatePublishJvmErrorStillEndsClosed() = runBlocking {
        val error = AssertionError("owner cancellation state error marker")
        val statePublishReached = CountDownLatch(1)
        val releaseStatePublish = CountDownLatch(1)
        val parentCancellationStarted = CountDownLatch(1)
        val releaseParentCancellation = CountDownLatch(1)
        val hookEntered = AtomicBoolean(false)
        val parentJob = SupervisorJob()
        val parentCancellationBlocker = parentJob.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { failure ->
            if (failure != null) {
                parentCancellationStarted.countDown()
                releaseParentCancellation.await()
            }
        }
        val captured = CompletableDeferred<Throwable>()
        val parentScope = CoroutineScope(
            parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, failure ->
                captured.complete(failure)
            },
        )
        val client = newClient(
            config = basicConfig(),
            connector = RecordingConnector { _ -> throw AssertionError("connector must not run") },
            parentScope = parentScope,
            generation = 621L,
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                beforeStatePublish = { state ->
                    if (state is FrpControlState.Connecting && hookEntered.compareAndSet(false, true)) {
                        statePublishReached.countDown()
                        releaseStatePublish.await()
                        throw error
                    }
                },
            ),
        )
        try {
            client.start()
            assertTrue(withContext(Dispatchers.IO) { statePublishReached.await(5, TimeUnit.SECONDS) })

            val cancellationThread = Thread(
                { parentJob.cancel(CancellationException("owner cancellation marker")) },
                "frp-parent-cancellation-fixture",
            )
            cancellationThread.start()
            assertTrue(parentCancellationStarted.await(5, TimeUnit.SECONDS))
            releaseStatePublish.countDown()

            assertSame(error, withTimeout(5_000L) { captured.await() })
            withTimeout(5_000L) { client.awaitStopped() }
            assertTrue(client.state.value is FrpControlState.Closed)
            assertTrue(client.diagnostics().closed)
            assertFalse(client.diagnostics().runnerActive)
            releaseParentCancellation.countDown()
            cancellationThread.join(5_000L)
            assertFalse(cancellationThread.isAlive)
        } finally {
            releaseStatePublish.countDown()
            releaseParentCancellation.countDown()
            parentCancellationBlocker.dispose()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun parentCancellationAfterWorkRegistrationRejectsHandlerDelivery() = runBlocking {
        val fixture = v1Fixture()
        val controlStream = ScriptedMuxStream(fixture.fullServerInput)
        val workStream = ScriptedMuxStream(FrpWireV1.encodeMessage(StartWorkConn(proxyName = "claimed")))
        val mux = FakeMuxConnection(listOf(controlStream, workStream))
        val registered = CompletableDeferred<Unit>()
        val releaseRegistration = CompletableDeferred<Unit>()
        val handlerCalled = AtomicBoolean(false)
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(
            parentJob + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> },
        )
        val client = newClient(
            config = fixture.config,
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 620L,
            workHandler = FrpWorkConnectionHandler { handlerCalled.set(true) },
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                afterWorkRegistration = {
                    registered.complete(Unit)
                    withContext(NonCancellable) { releaseRegistration.await() }
                },
            ),
        )
        val completion = CompletableDeferred<Throwable?>()
        try {
            val runner = client.start()
            runner.invokeOnCompletion { completion.complete(it) }
            withTimeout(5_000L) { registered.await() }
            assertEquals(1, client.diagnostics().activeWorkConnections)
            val cancellation = CancellationException("parent registered-work cancellation marker")

            parentJob.cancel(cancellation)
            releaseRegistration.complete(Unit)
            assertOriginalCancellation(cancellation, withTimeout(5_000L) { completion.await() })
            withTimeout(5_000L) { client.awaitStopped() }

            assertFalse(handlerCalled.get())
            assertEquals(1, workStream.resetCount.get())
            assertEquals(0, client.diagnostics().activeWorkConnections)
            assertTrue(client.state.value is FrpControlState.Closed)
        } finally {
            releaseRegistration.complete(Unit)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun terminationAtOpenBarrierCannotPublishOpen() = runBlocking {
        val fixture = v1Fixture()
        val mux = FakeMuxConnection(listOf(stableV1ControlStream()))
        val connector = RecordingConnector { _ -> mux }
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayer = ManualDelayer()
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config.copy(
                reconnectInitialDelayMillis = 100L,
                reconnectMaximumDelayMillis = 20_000L,
            ),
            connector = connector,
            parentScope = parentScope,
            generation = 62L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            delayer = delayer,
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                beforeOpenPublish = {
                    reached.complete(Unit)
                    release.await()
                },
            ),
        )
        val states = Collections.synchronizedList(mutableListOf<FrpControlState>())
        val collector = parentScope.launch { client.state.collect(states::add) }
        try {
            client.start()
            withTimeout(5_000L) { reached.await() }

            mux.fail(FrpTransportException(FrpTransportFailure.READ_FAILED))
            waitUntil { mux.closeCount.get() == 1 }
            release.complete(Unit)
            waitUntil { client.state.value is FrpControlState.Retrying }

            val retrying = client.state.value as FrpControlState.Retrying
            assertEquals(FrpControlFailure.TRANSPORT_FAILED, retrying.failure)
            assertTrue(states.none { it is FrpControlState.Open })
            assertEquals(1, connector.connectCount.get())
        } finally {
            collector.cancel()
            client.close()
            withTimeout(5_000L) { client.awaitStopped() }
            parentScope.cancel()
        }
    }

    @Test
    fun explicitOpenClientCloseDoesNotRetry() = runBlocking {
        val fixture = v1Fixture()
        val mux = FakeMuxConnection(listOf(stableV1ControlStream()))
        val connector = RecordingConnector { _ -> mux }
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config,
            connector = connector,
            parentScope = parentScope,
            generation = 63L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
        )
        val states = Collections.synchronizedList(mutableListOf<FrpControlState>())
        val collector = parentScope.launch { client.state.collect(states::add) }
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Open }
            waitUntil { states.any { it is FrpControlState.Open } }

            client.close()
            withTimeout(5_000L) { client.awaitStopped() }

            assertTrue(client.state.value is FrpControlState.Closed)
            assertTrue(states.none { it is FrpControlState.Retrying })
            assertEquals(1, connector.connectCount.get())
            assertEquals(1, mux.closeCount.get())
        } finally {
            collector.cancel()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun singleThreadParentStillReadsPongWhileWorkWaitsForStart() = runBlocking {
        val fixture = v1Fixture()
        val marker = "pong server endpoint token payload marker"
        val request = ReqWorkConn()
        val encrypted = encryptV1ServerMessages(listOf(request, Pong(error = marker)))
        val requestEnd = 16 + FrpWireV1.encodeMessage(request).size
        val controlStream = ScriptedMuxStream(
            FrpContractFixtures.bytes("wire-v1-login-response") + encrypted.copyOf(requestEnd),
        )
        val workStream = ScriptedMuxStream()
        val mux = FakeMuxConnection(listOf(controlStream, workStream))
        val delayer = ManualDelayer()
        val parentScope = CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default.limitedParallelism(1, "FrpControlSingleParent") +
                CoroutineExceptionHandler { _, _ -> },
        )
        val client = newClient(
            config = fixture.config.copy(
                workWorkerCount = 1,
                reconnectInitialDelayMillis = 100L,
                reconnectMaximumDelayMillis = 20_000L,
            ),
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 64L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            delayer = delayer,
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Open }
            val expectedNewWork = NewWorkConn(
                runId = "run_fixture_001",
                privilegeKey = FrpTimestampAuth.key(
                    FrpContractFixtures.syntheticToken,
                    fixture.login.timestamp,
                ),
                timestamp = fixture.login.timestamp,
            )
            waitUntil {
                workStream.writtenBytes().size == FrpWireV1.encodeMessage(expectedNewWork).size
            }
            assertEquals(expectedNewWork, decodeWorkRequest(FrpWireProtocol.V1, workStream.writtenBytes()))

            controlStream.appendInput(encrypted.copyOfRange(requestEnd, encrypted.size))
            waitUntil { client.state.value is FrpControlState.Retrying }

            val retrying = client.state.value as FrpControlState.Retrying
            assertEquals(FrpControlFailure.PROTOCOL_FAILED, retrying.failure)
            assertFalse(retrying.toString().contains(marker))
            waitUntil { workStream.resetCount.get() == 1 }
        } finally {
            client.close()
            withTimeout(5_000L) { client.awaitStopped() }
            parentScope.cancel()
        }
    }

    @Test
    fun stopInvalidatesOwnerBeforeLateWorkDelivery() = runBlocking {
        val fixture = v1Fixture()
        val start = StartWorkConn(proxyName = "late-work-payload-marker")
        val controlStream = ScriptedMuxStream(fixture.fullServerInput)
        val workStream = ScriptedMuxStream(FrpWireV1.encodeMessage(start))
        val mux = FakeMuxConnection(listOf(controlStream, workStream))
        val reached = CompletableDeferred<FrpControlIdentity>()
        val release = CompletableDeferred<Unit>()
        val handlerCalled = AtomicBoolean(false)
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config,
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 65L,
            workHandler = FrpWorkConnectionHandler { handlerCalled.set(true) },
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                beforeWorkDelivery = { identity ->
                    reached.complete(identity)
                    withContext(NonCancellable) { release.await() }
                },
            ),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Open }
            assertEquals(
                FrpControlIdentity(65L, 1L, 1L),
                withTimeout(5_000L) { reached.await() },
            )

            client.close()
            release.complete(Unit)
            withTimeout(5_000L) { client.awaitStopped() }

            assertFalse(handlerCalled.get())
            assertEquals(1, workStream.resetCount.get())
            assertTrue(client.state.value is FrpControlState.Closed)
        } finally {
            release.complete(Unit)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun terminationAfterWorkRegistrationClosesClaimWithoutCallingHandler() = runBlocking {
        val fixture = v1Fixture()
        val controlStream = ScriptedMuxStream(fixture.fullServerInput)
        val workStream = ScriptedMuxStream(FrpWireV1.encodeMessage(StartWorkConn(proxyName = "claimed")))
        val mux = FakeMuxConnection(listOf(controlStream, workStream))
        val registered = CompletableDeferred<Unit>()
        val releaseRegistration = CompletableDeferred<Unit>()
        val handlerCalled = AtomicBoolean(false)
        val parentScope = newParentScope()
        val client = newClient(
            config = fixture.config,
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 615L,
            workHandler = FrpWorkConnectionHandler { handlerCalled.set(true) },
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
            lifecycleHooks = FrpControlLifecycleHooks(
                afterWorkRegistration = {
                    registered.complete(Unit)
                    withContext(NonCancellable) { releaseRegistration.await() }
                },
            ),
        )
        try {
            client.start()
            withTimeout(5_000L) { registered.await() }
            assertEquals(1, client.diagnostics().activeWorkConnections)

            client.close()
            releaseRegistration.complete(Unit)
            withTimeout(5_000L) { client.awaitStopped() }

            assertFalse(handlerCalled.get())
            assertEquals(1, workStream.resetCount.get())
            assertEquals(0, client.diagnostics().activeWorkConnections)
        } finally {
            releaseRegistration.complete(Unit)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun matchingUserPrefixIsRemovedOnceWithoutMutatingServerMessage() {
        val server = StartWorkConn(proxyName = "alice.alice.proxy", srcAddr = "source")

        val delivered = server.withFrpUserPrefixRemoved("alice")

        assertEquals("alice.proxy", delivered.proxyName)
        assertEquals("alice.alice.proxy", server.proxyName)
        assertFalse(server === delivered)
    }

    @Test
    fun emptyUserLeavesProxyNameUnchanged() {
        val server = StartWorkConn(proxyName = "alice.proxy")

        val delivered = server.withFrpUserPrefixRemoved("")

        assertSame(server, delivered)
    }

    @Test
    fun nonMatchingUserPrefixLeavesProxyNameUnchanged() {
        val server = StartWorkConn(proxyName = "other.proxy")

        val delivered = server.withFrpUserPrefixRemoved("alice")

        assertSame(server, delivered)
    }

    @Test
    fun explicitCloseJvmErrorRemainsObservableWithoutLeakingIntoState() = runBlocking {
        val fixture = v1Fixture()
        val error = AssertionError("close endpoint token server payload marker")
        val mux = FakeMuxConnection(listOf(stableV1ControlStream()), closeFailure = error)
        val captured = CompletableDeferred<Throwable>()
        val parentScope = newParentScope(
            CoroutineExceptionHandler { _, failure -> captured.complete(failure) },
        )
        val client = newClient(
            config = fixture.config,
            connector = RecordingConnector { _ -> mux },
            parentScope = parentScope,
            generation = 66L,
            unixTimeSource = FrpUnixTimeSource { fixture.login.timestamp },
            secureRandom = RepeatingSecureRandom(0x80),
        )
        try {
            client.start()
            waitUntil { client.state.value is FrpControlState.Open }

            client.close()

            assertSame(error, withTimeout(5_000L) { captured.await() })
            withTimeout(5_000L) { client.awaitStopped() }
            assertTrue(client.state.value is FrpControlState.Closed)
            assertFalse(client.state.value.toString().contains(error.message.orEmpty()))
        } finally {
            client.close()
            parentScope.cancel()
        }
    }

    private fun newClient(
        config: FrpControlConfig,
        connector: FrpMuxConnector,
        parentScope: CoroutineScope,
        generation: Long,
        workHandler: FrpWorkConnectionHandler = FrpWorkConnectionHandler { it.close() },
        unixTimeSource: FrpUnixTimeSource = FrpUnixTimeSource { 1_700_000_000L },
        monotonicTimeSource: FrpMonotonicTimeSource = FrpMonotonicTimeSource { 0L },
        delayer: FrpControlDelayer = CoroutineFrpControlDelayer,
        secureRandom: SecureRandom,
        lifecycleHooks: FrpControlLifecycleHooks = FrpControlLifecycleHooks(),
    ): FrpControlClient = FrpControlClient(
        config = config,
        connector = connector,
        parentScope = parentScope,
        generation = generation,
        workHandler = workHandler,
        unixTimeSource = unixTimeSource,
        monotonicTimeSource = monotonicTimeSource,
        delayer = delayer,
        secureRandom = secureRandom,
        lifecycleHooks = lifecycleHooks,
    )

    private fun basicConfig(): FrpControlConfig = FrpControlConfig(
        token = FrpContractFixtures.syntheticToken,
        version = "fixture-client",
        hostname = "fixture-host",
        os = "android",
        arch = "arm64",
    )

    private fun v1Fixture(): ControlFixture {
        val login = FrpWireV1.readMessage(
            ByteArrayInputStream(FrpContractFixtures.bytes("wire-v1-login-token")),
        ) as Login
        return ControlFixture(
            protocol = FrpWireProtocol.V1,
            login = login,
            config = FrpControlConfig(
                token = FrpContractFixtures.syntheticToken,
                user = login.user,
                version = login.version,
                hostname = login.hostname,
                os = login.os,
                arch = login.arch,
                clientId = login.clientId,
                metas = login.metas,
                wireProtocol = FrpWireProtocol.V1,
            ),
            fullServerInput = FrpContractFixtures.bytes("wire-v1-login-response") +
                FrpContractFixtures.bytes("control-v1-server-cfb"),
            secureRandom = { RepeatingSecureRandom(0x80) },
        )
    }

    private fun v2AesFixture(): ControlFixture {
        val clientBootstrap = FrpContractFixtures.bytes("wire-v2-aes-256-gcm-client-bootstrap")
        val input = ByteArrayInputStream(clientBootstrap)
        FrpWireV2.readMagic(input)
        val hello = FrpWireV2.readClientHello(input)
        val login = FrpWireV2.readMessage(input) as Login
        val clientRandomStart = hello.value.capabilities.crypto.clientRandom?.first()?.toInt()?.and(0xff)
            ?: throw AssertionError("Fixture client random is missing")
        return ControlFixture(
            protocol = FrpWireProtocol.V2,
            login = login,
            config = FrpControlConfig(
                token = FrpContractFixtures.syntheticToken,
                user = login.user,
                version = login.version,
                hostname = login.hostname,
                os = login.os,
                arch = login.arch,
                clientId = login.clientId,
                metas = login.metas,
                wireProtocol = FrpWireProtocol.V2,
                preferredAeadAlgorithms = hello.value.capabilities.crypto.algorithms,
            ),
            fullServerInput = FrpContractFixtures.bytes("wire-v2-aes-256-gcm-server-bootstrap") +
                FrpContractFixtures.bytes("control-v2-aes-256-gcm-server"),
            secureRandom = { SequenceSecureRandom(clientRandomStart, 0xa0) },
        )
    }

    private fun stableV1ControlStream(): ScriptedMuxStream {
        val firstServerMessage = FrpWireV1.readMessage(
            ByteArrayInputStream(FrpContractFixtures.bytes("control-v1-server-plain")),
        ) ?: throw AssertionError("Fixture server message is missing")
        val encrypted = FrpContractFixtures.bytes("control-v1-server-cfb")
        val firstCipherLength = 16 + FrpWireV1.encodeMessage(firstServerMessage).size
        return ScriptedMuxStream(
            FrpContractFixtures.bytes("wire-v1-login-response") + encrypted.copyOf(firstCipherLength),
        )
    }

    private fun encryptV1ServerMessages(messages: List<FrpMessage>): ByteArray {
        val output = ByteArrayOutputStream()
        val encrypted = FrpV1Cfb.encrypting(
            output,
            FrpContractFixtures.syntheticToken,
            RepeatingSecureRandom(0x40),
        )
        messages.forEach { message ->
            val encoded = FrpWireV1.encodeMessage(message)
            try {
                encrypted.write(encoded)
            } finally {
                encoded.fill(0)
            }
        }
        encrypted.flush()
        return output.toByteArray()
    }

    private fun decodeV1ClientControlMessages(bytes: ByteArray): List<FrpMessage> {
        val input = ByteArrayInputStream(bytes)
        check(FrpWireV1.readMessage(input) is Login)
        if (input.available() == 0) return emptyList()
        val encrypted = FrpV1Cfb.decrypting(input, FrpContractFixtures.syntheticToken)
        return buildList {
            while (true) add(FrpWireV1.readMessage(encrypted) ?: break)
        }
    }

    private fun decodeWorkRequest(protocol: FrpWireProtocol, bytes: ByteArray): NewWorkConn {
        val input = ByteArrayInputStream(bytes)
        if (protocol == FrpWireProtocol.V2) FrpWireV2.readMagic(input)
        val message = when (protocol) {
            FrpWireProtocol.V1 -> FrpWireV1.readMessage(input)
            FrpWireProtocol.V2 -> FrpWireV2.readMessage(input)
        } as NewWorkConn
        assertEquals(-1, input.read())
        return message
    }

    private fun newParentScope(
        exceptionHandler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, _ -> },
    ): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    private suspend fun waitUntil(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5L)
        }
    }

    private suspend fun newTimeoutCancellation(): TimeoutCancellationException = try {
        withTimeout(1L) { awaitCancellation() }
    } catch (failure: TimeoutCancellationException) {
        failure
    }

    private fun assertOriginalCancellation(expected: CancellationException, actual: Throwable?) {
        assertTrue(actual is CancellationException)
        val expectedChain = generateSequence(expected as Throwable?) { it.cause }.toList()
        val actualChain = generateSequence(actual) { it.cause }.toList()
        assertTrue(actualChain.any { candidate -> expectedChain.any { it === candidate } })
    }

    private data class ControlFixture(
        val protocol: FrpWireProtocol,
        val login: Login,
        val config: FrpControlConfig,
        val fullServerInput: ByteArray,
        val secureRandom: () -> SecureRandom,
    )

    private data class ObservedWork(
        val identity: FrpControlIdentity,
        val start: StartWorkConn,
        val summary: String,
    )

    private class RecordingConnector(
        private val factory: (Int) -> FrpMuxConnection,
    ) : FrpMuxConnector {
        val connectCount = AtomicInteger(0)

        override suspend fun connect(): FrpMuxConnection = factory(connectCount.getAndIncrement())
    }

    private class FakeMuxConnection(
        streams: List<FrpMuxStream>,
        initialFailure: Throwable? = null,
        private val closeFailure: Throwable? = null,
        private val awaitTerminationFailure: Throwable? = null,
        private val beforeAwaitTerminationResult: suspend () -> Unit = {},
    ) : FrpMuxConnection {
        private val streamQueue = Channel<FrpMuxStream>(Channel.UNLIMITED)
        private val termination = CompletableDeferred<Unit>()
        private val closed = AtomicBoolean(false)
        val closeCount = AtomicInteger(0)

        init {
            streams.forEach { check(streamQueue.trySend(it).isSuccess) }
            initialFailure?.let(termination::completeExceptionally)
        }

        override suspend fun openStream(): FrpMuxStream =
            streamQueue.receiveCatching().getOrNull()
                ?: throw FrpTransportException(FrpTransportFailure.CLOSED)

        override suspend fun awaitTermination() {
            termination.await()
            beforeAwaitTerminationResult()
            awaitTerminationFailure?.let { throw it }
        }

        fun fail(failure: Throwable) {
            termination.completeExceptionally(failure)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            closeCount.incrementAndGet()
            streamQueue.close()
            termination.complete(Unit)
            closeFailure?.let { throw it }
        }

        override fun toString(): String =
            "FakeMuxConnection(closed=${closed.get()}, closeCount=${closeCount.get()})"
    }

    private class ScriptedMuxStream(
        initialInput: ByteArray = ByteArray(0),
        private val resetFailure: Throwable? = null,
        private val resetRelease: CompletableDeferred<Unit>? = null,
    ) : FrpMuxStream {
        private val inputChunks = Channel<ByteArray>(Channel.UNLIMITED)
        private val output = ByteArrayOutputStream()
        private val outputLock = Any()
        private val reset = AtomicBoolean(false)
        private var current = ByteArray(0)
        private var currentOffset = 0
        val totalRead = AtomicInteger(0)
        val resetCount = AtomicInteger(0)
        val readStarted = CompletableDeferred<Unit>()

        init {
            if (initialInput.isNotEmpty()) check(inputChunks.trySend(initialInput.copyOf()).isSuccess)
        }

        fun appendInput(bytes: ByteArray) {
            check(!reset.get())
            check(inputChunks.trySend(bytes.copyOf()).isSuccess)
        }

        fun writtenBytes(): ByteArray = synchronized(outputLock) { output.toByteArray() }

        override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
            readStarted.complete(Unit)
            while (currentOffset >= current.size) {
                current.fill(0)
                current = inputChunks.receiveCatching().getOrNull() ?: return -1
                currentOffset = 0
            }
            val count = minOf(length, current.size - currentOffset)
            current.copyInto(destination, offset, currentOffset, currentOffset + count)
            currentOffset += count
            totalRead.addAndGet(count)
            return count
        }

        override suspend fun write(source: ByteArray, offset: Int, length: Int) {
            if (reset.get()) throw FrpTransportException(FrpTransportFailure.CLOSED)
            synchronized(outputLock) { output.write(source, offset, length) }
        }

        override suspend fun closeWrite() = Unit

        override suspend fun reset() {
            if (!reset.compareAndSet(false, true)) return
            resetCount.incrementAndGet()
            inputChunks.close()
            resetRelease?.await()
            resetFailure?.let { throw it }
        }

        override fun toString(): String =
            "ScriptedMuxStream(reset=${reset.get()}, totalRead=${totalRead.get()})"
    }

    private class ManualDelayer : FrpControlDelayer {
        private val calls = Channel<DelayCall>(Channel.UNLIMITED)

        override suspend fun delayMillis(milliseconds: Long) {
            val call = DelayCall(milliseconds)
            calls.send(call)
            call.release.await()
        }

        suspend fun takeCall(expectedMillis: Long): DelayCall =
            withTimeout(5_000L) { calls.receive() }.also { call ->
                assertEquals(expectedMillis, call.millis)
            }

        suspend fun takeCalls(count: Int, expectedMillis: Long): List<DelayCall> =
            List(count) { takeCall(expectedMillis) }

        suspend fun takeCalls(count: Int): List<DelayCall> =
            List(count) { withTimeout(5_000L) { calls.receive() } }
    }

    private data class DelayCall(
        val millis: Long,
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private class RecordingStopDelayer(private val stopAfter: Int) : FrpControlDelayer {
        private val delays = Collections.synchronizedList(mutableListOf<Long>())

        override suspend fun delayMillis(milliseconds: Long) {
            delays += milliseconds
            if (delays.size >= stopAfter) throw CancellationException("stop retry fixture")
        }

        fun snapshot(): List<Long> = synchronized(delays) { delays.toList() }
    }

    private class SequenceSecureRandom(vararg starts: Int) : SecureRandom() {
        private val starts = ArrayDeque<Int>().apply { starts.forEach(::addLast) }

        override fun nextBytes(bytes: ByteArray) {
            val start = starts.pollFirst() ?: throw AssertionError("Unexpected SecureRandom request")
            repeat(bytes.size) { index -> bytes[index] = (start + index).toByte() }
        }
    }

    private class RepeatingSecureRandom(private val start: Int) : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            repeat(bytes.size) { index -> bytes[index] = (start + index).toByte() }
        }
    }
}
