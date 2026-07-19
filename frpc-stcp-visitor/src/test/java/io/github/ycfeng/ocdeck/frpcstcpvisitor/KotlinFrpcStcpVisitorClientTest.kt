package io.github.ycfeng.ocdeck.frpcstcpvisitor

import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control.FrpClientStreamLease
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control.FrpControlConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control.FrpControlIdentity
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control.FrpControlState
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control.FrpUnixTimeSource
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control.FrpWireProtocol
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.crypto.FrpTimestampAuth
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.FrpWireV1
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.FrpWireV2
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.NewVisitorConn
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.NewVisitorConnResp
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime.FrpLocalConnection
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime.FrpLocalListener
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime.FrpLocalListenerFactory
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime.FrpSessionControl
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime.FrpSessionControlFactory
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime.FrpRelayVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime.FrpVisitorRelay
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.runtime.productionFrpControlConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpMuxStream
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpTransportException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpTransportFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinFrpcStcpVisitorClientTest {
    @Test
    fun revisionsValidationFixedBindAndLoopbackOwnershipAreLinearized() = runBlocking {
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val controlStates = Channel<FrpControlState>(Channel.UNLIMITED)
        val parentScope = newParentScope()
        val client = newClient(
            parentScope = parentScope,
            controlFactory = controlFactory,
            listenerFactory = listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                afterControlState = { controlStates.send(it) },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            val identity = control.open(epoch = 1L, transport = 1L)
            awaitControlState(controlStates) { it is FrpControlState.Open }

            val invalidPort = captureRuntimeFailure {
                client.ensureVisitor(sessionId, visitorConfig(name = "invalid", bindPort = 0))
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.INVALID_CONFIGURATION, invalidPort.failure)
            assertEquals(0L, client.getState(sessionId).configRevision)

            val firstConfig = visitorConfig(
                name = "first",
                bindAddr = "0.0.0.0",
                bindPort = 50_000,
            )
            val first = client.ensureVisitor(sessionId, firstConfig)
            assertEquals(50_000, first.bindPort)
            assertEquals(1L, first.desiredRevision)
            val firstBinding = listenerFactory.takeBinding()
            assertEquals("127.0.0.1", firstBinding.address.hostAddress)
            assertEquals(50_000, firstBinding.requestedPort)
            assertEquals(50_000, firstBinding.listener.bindPort)
            val invalidWaitTimeout = captureRuntimeFailure {
                client.waitVisitorReady(sessionId, "first", first.desiredRevision, 0L)
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.INVALID_CONFIGURATION, invalidWaitTimeout.failure)
            assertEquals(
                FrpcVisitorReadyResult("first", 1L, "ready", true, identity.controlEpoch),
                client.waitVisitorReady(sessionId, "first", 1L, 1_000L),
            )

            val same = client.ensureVisitor(
                sessionId,
                firstConfig.copy(bindAddr = "192.0.2.1"),
            )
            assertEquals(50_000, same.bindPort)
            assertEquals(1L, same.desiredRevision)
            assertEquals(1, listenerFactory.bindCount.get())
            assertEquals(2L, client.getState(sessionId).configRevision)

            val unsupported = captureRuntimeFailure {
                client.ensureVisitor(sessionId, firstConfig.copy(useEncryption = true))
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.UNSUPPORTED_CONFIGURATION, unsupported.failure)
            assertEquals(2L, client.getState(sessionId).configRevision)

            val second = client.ensureVisitor(
                sessionId,
                visitorConfig(name = "second", bindPort = 50_001),
            )
            assertEquals(3L, second.desiredRevision)
            listenerFactory.takeBinding()
            val duplicate = captureRuntimeFailure {
                client.ensureVisitor(
                    sessionId,
                    visitorConfig(name = "duplicate", bindPort = 50_001),
                )
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.BIND_PORT_CONFLICT, duplicate.failure)
            assertEquals(3L, client.getState(sessionId).configRevision)

            client.stopVisitor(sessionId, "missing")
            assertEquals(3L, client.getState(sessionId).configRevision)
            client.stopVisitor(sessionId, "first")
            assertEquals(4L, client.getState(sessionId).configRevision)
            assertEquals(1, firstBinding.listener.closeCount.get())
            assertFalse(client.getState(sessionId).visitors.containsKey("first"))
        } finally {
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun readinessRequiresExactRevisionAndRebindsOnTheCurrentControlEpoch() = runBlocking {
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val controlStates = Channel<FrpControlState>(Channel.UNLIMITED)
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                afterControlState = { controlStates.send(it) },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            awaitControlState(controlStates) { it is FrpControlState.Open }
            val initial = client.ensureVisitor(sessionId, visitorConfig(secret = "secret-one", bindPort = 50_010))
            val firstListener = listenerFactory.takeBinding().listener
            assertEquals(1L, initial.desiredRevision)
            assertEquals(1L, client.waitVisitorReady(sessionId, "visitor", 1L, 1_000L).boundControlEpoch)

            val changed = client.ensureVisitor(sessionId, visitorConfig(secret = "secret-two", bindPort = 50_010))
            val secondListener = listenerFactory.takeBinding().listener
            assertEquals(2L, changed.desiredRevision)
            assertEquals(1, firstListener.closeCount.get())
            val stale = captureRuntimeFailure {
                client.waitVisitorReady(sessionId, "visitor", 1L, 1_000L)
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.VISITOR_SUPERSEDED, stale.failure)

            val futureWait = async {
                captureRuntimeFailure {
                    client.waitVisitorReady(sessionId, "visitor", 3L, 100L)
                }
            }
            val same = client.ensureVisitor(sessionId, visitorConfig(secret = "secret-two", bindPort = 50_010))
            assertEquals(2L, same.desiredRevision)
            assertEquals(KotlinFrpcStcpVisitorFailure.WAIT_TIMEOUT, futureWait.await().failure)

            control.retry(attempt = 1)
            awaitControlState(controlStates) { it is FrpControlState.Retrying }
            assertEquals(1, secondListener.closeCount.get())
            val disconnected = client.getState(sessionId)
            assertEquals("reconnecting", disconnected.phase)
            assertFalse(disconnected.visitors.getValue("visitor").listenerBound)

            val secondIdentity = control.open(epoch = 2L, transport = 2L)
            awaitControlState(controlStates) {
                it is FrpControlState.Open && it.identity == secondIdentity
            }
            listenerFactory.takeBinding()
            val ready = client.waitVisitorReady(sessionId, "visitor", 2L, 1_000L)
            assertEquals(2L, ready.boundControlEpoch)
            assertEquals(3L, client.getState(sessionId).configRevision)
        } finally {
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun liveControlStatePreventsStaleReadinessBeforeActorReconciliation() = runBlocking {
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val blockNextControl = AtomicBoolean(false)
        val blockedControl = CompletableDeferred<Unit>()
        val releaseControl = CompletableDeferred<Unit>()
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                beforeRuntimeCommand = { kind ->
                    if (kind == "ControlChangedCommand" && blockNextControl.compareAndSet(true, false)) {
                        blockedControl.complete(Unit)
                        releaseControl.await()
                    }
                },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_011))
            control.open(epoch = 1L, transport = 1L)
            listenerFactory.takeBinding()
            assertEquals(
                1L,
                client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L).boundControlEpoch,
            )

            blockNextControl.set(true)
            control.retry(attempt = 1)
            withTimeout(5_000L) { blockedControl.await() }
            val disconnected = client.getState(sessionId)
            assertEquals("reconnecting", disconnected.phase)
            assertFalse(disconnected.visitors.getValue("visitor").listenerBound)
            val stale = captureRuntimeFailure {
                client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 25L)
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.WAIT_TIMEOUT, stale.failure)

            control.fail()
            assertEquals("failed", client.getState(sessionId).phase)
            val failed = captureRuntimeFailure {
                client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.CONTROL_FAILED, failed.failure)
        } finally {
            releaseControl.complete(Unit)
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun v1AndV2VisitorHandshakePreserveCoalescedPayloadAndRelayHalfCloses() = runBlocking {
        listOf(FrpWireProtocol.V1, FrpWireProtocol.V2).forEach { protocol ->
            val controlFactory = FakeControlFactory(protocol = protocol)
            val listenerFactory = FakeListenerFactory()
            val controlStates = Channel<FrpControlState>(Channel.UNLIMITED)
            val parentScope = newParentScope()
            val client = newClient(
                parentScope,
                controlFactory,
                listenerFactory,
                unixTimeSource = FrpUnixTimeSource { FIXED_TIMESTAMP },
                hooks = KotlinFrpcRuntimeLifecycleHooks(
                    afterControlState = { controlStates.send(it) },
                ),
            )
            val sessionId = client.startSession(
                sessionConfig(user = "session-user", wireProtocol = protocol.name.lowercase()),
            )
            val control = controlFactory.single()
            try {
                control.open(epoch = 1L, transport = 1L)
                awaitControlState(controlStates) { it is FrpControlState.Open }
                val ensured = client.ensureVisitor(
                    sessionId,
                    visitorConfig(
                        serverName = "remote-service",
                        serverUser = "server-user",
                        secret = SYNTHETIC_SECRET,
                        bindPort = 50_020,
                    ),
                )
                val listener = listenerFactory.takeBinding().listener
                client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)

                val proxyName = "server-user.remote-service"
                val serverPayload = "server-payload-${protocol.name}".encodeToByteArray()
                val response = when (protocol) {
                    FrpWireProtocol.V1 -> FrpWireV1.encodeMessage(
                        NewVisitorConnResp(proxyName = "mismatched-response-name"),
                    )
                    FrpWireProtocol.V2 -> FrpWireV2.encodeMessage(
                        NewVisitorConnResp(proxyName = "mismatched-response-name"),
                    )
                }
                val stream = ScriptedMuxStream(response + serverPayload)
                control.enqueueStream(stream)
                val localPayload = "local-payload-${protocol.name}".encodeToByteArray()
                val local = FakeLocalConnection(localPayload)
                listener.offer(local)
                withTimeout(5_000L) { stream.resetStarted.await() }

                val written = ByteArrayInputStream(stream.writtenBytes())
                if (protocol == FrpWireProtocol.V2) FrpWireV2.readMagic(written)
                val request = when (protocol) {
                    FrpWireProtocol.V1 -> FrpWireV1.readMessage(written)
                    FrpWireProtocol.V2 -> FrpWireV2.readMessage(written)
                } as NewVisitorConn
                assertEquals("run-fixture", request.runId)
                assertEquals(proxyName, request.proxyName)
                assertEquals(FIXED_TIMESTAMP, request.timestamp)
                assertEquals(FrpTimestampAuth.key(SYNTHETIC_SECRET, FIXED_TIMESTAMP), request.signKey)
                assertFalse(request.useEncryption)
                assertFalse(request.useCompression)
                assertTrue(localPayload.contentEquals(written.readBytes()))
                assertTrue(serverPayload.contentEquals(local.writtenBytes()))
                assertEquals(1, stream.closeWriteCount.get())
                assertEquals(1, local.shutdownOutputCount.get())
                assertEquals(1, stream.resetCount.get())
                assertEquals("ready", client.getState(sessionId).visitors.getValue("visitor").phase)
            } finally {
                control.releaseStop()
                client.stopSession(sessionId, 5_000L)
                client.close()
                parentScope.cancel()
            }
        }
    }

    @Test
    fun rejectedVisitorHandshakeIsRelayLocalAndNeverExposesServerText() = runBlocking {
        val marker = "unsafe-server-handshake-marker"
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val controlStates = Channel<FrpControlState>(Channel.UNLIMITED)
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                afterControlState = { controlStates.send(it) },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            awaitControlState(controlStates) { it is FrpControlState.Open }
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_030))
            val listener = listenerFactory.takeBinding().listener
            client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)
            val response = FrpWireV1.encodeMessage(
                NewVisitorConnResp(proxyName = "remote-service", error = marker),
            )
            val stream = ScriptedMuxStream(response)
            control.enqueueStream(stream)
            val local = FakeLocalConnection(ByteArray(0))
            listener.offer(local)
            withTimeout(5_000L) { stream.resetStarted.await() }

            val state = client.getState(sessionId)
            assertEquals("open", state.phase)
            assertEquals("ready", state.visitors.getValue("visitor").phase)
            assertFalse(state.toString().contains(marker))
            assertFalse(client.toString().contains(marker))
            assertEquals(1, local.closeCount.get())
        } finally {
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun completedFailedRelayCannotRejectTheNextAcceptedConnection() = runBlocking {
        val blockNextEnsure = AtomicBoolean(false)
        val actorBlocked = CompletableDeferred<Unit>()
        val releaseActor = CompletableDeferred<Unit>()
        val releaseFirstHandshake = CompletableDeferred<Unit>()
        val firstRelayCompleted = CompletableDeferred<Unit>()
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                beforeRuntimeCommand = { kind ->
                    if (kind == "EnsureVisitorCommand" && blockNextEnsure.compareAndSet(true, false)) {
                        actorBlocked.complete(Unit)
                        releaseActor.await()
                    }
                },
                afterRelayCompletion = { firstRelayCompleted.complete(Unit) },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_031))
            val listener = listenerFactory.takeBinding().listener
            client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)

            val failedResponse = FrpWireV1.encodeMessage(
                NewVisitorConnResp(proxyName = "ignored", error = "synthetic rejection"),
            )
            val firstStream = ScriptedMuxStream(failedResponse, firstReadRelease = releaseFirstHandshake)
            control.enqueueStream(firstStream)
            val firstLocal = FakeLocalConnection(ByteArray(0))
            listener.offer(firstLocal)
            firstStream.readStarted.await()

            blockNextEnsure.set(true)
            val sameEnsure = async { client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_031)) }
            actorBlocked.await()
            val secondResponse = FrpWireV1.encodeMessage(NewVisitorConnResp(proxyName = "ignored"))
            val secondStream = ScriptedMuxStream(secondResponse)
            control.enqueueStream(secondStream)
            val secondLocal = FakeLocalConnection(byteArrayOf(1, 2, 3))
            listener.offer(secondLocal)
            waitUntil { listener.acceptCount.get() == 2 }

            releaseFirstHandshake.complete(Unit)
            firstRelayCompleted.await()
            assertEquals(1, firstLocal.closeCount.get())
            releaseActor.complete(Unit)
            sameEnsure.await()

            withTimeout(5_000L) { secondStream.resetStarted.await() }
            assertEquals(1, secondLocal.closeCount.get())
            assertTrue(secondStream.writtenBytes().isNotEmpty())
            assertTrue(control.state.value is FrpControlState.Open)
        } finally {
            releaseFirstHandshake.complete(Unit)
            releaseActor.complete(Unit)
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun listenerBindFailureIsTypedSafeAndCommitsOnlyTheAcceptedRevision() = runBlocking {
        val marker = "unsafe-bind-endpoint-marker"
        val controlFactory = FakeControlFactory()
        val delayer = ManualRuntimeDelayer()
        val listenerFactory = FakeListenerFactory().apply {
            nextFailure = BindException(marker)
        }
        val controlStates = Channel<FrpControlState>(Channel.UNLIMITED)
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            runtimeDelayer = delayer,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                afterControlState = { controlStates.send(it) },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            awaitControlState(controlStates) { it is FrpControlState.Open }

            val accepted = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_040))
            assertEquals(1L, accepted.desiredRevision)
            val retry = delayer.takeCall(10_000L)
            val state = client.getState(sessionId)
            assertEquals(1L, state.configRevision)
            assertEquals("failed", state.visitors.getValue("visitor").phase)
            assertEquals(
                KotlinFrpcStcpVisitorFailure.LISTENER_BIND_FAILED.description,
                state.visitors.getValue("visitor").lastError,
            )
            assertFalse(state.toString().contains(marker))
            val exactFailure = captureRuntimeFailure {
                client.waitVisitorReady(sessionId, "visitor", accepted.desiredRevision, 120_000L)
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.LISTENER_BIND_FAILED, exactFailure.failure)
            assertFalse(exactFailure.toString().contains(marker))
            assertEquals(null, exactFailure.cause)

            val same = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_040))
            assertEquals(1L, same.desiredRevision)
            assertEquals(2L, client.getState(sessionId).configRevision)
            assertEquals(1, listenerFactory.bindCount.get())
            retry.release.complete(Unit)
            listenerFactory.takeBinding()
            assertEquals(
                "ready",
                client.waitVisitorReady(sessionId, "visitor", accepted.desiredRevision, 1_000L).phase,
            )
        } finally {
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun stopTimeoutDoesNotCancelTheSharedCleanupFlightOrRemoveTheSessionEarly() = runBlocking {
        val stopRelease = CompletableDeferred<Unit>()
        val cleanupRelease = CompletableDeferred<Unit>()
        val controlFactory = FakeControlFactory(stopRelease = stopRelease)
        val listenerFactory = FakeListenerFactory()
        val controlStates = Channel<FrpControlState>(Channel.UNLIMITED)
        val stopStarted = CompletableDeferred<Unit>()
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                afterControlState = { controlStates.send(it) },
                beforeStopCleanup = {
                    stopStarted.complete(Unit)
                    cleanupRelease.await()
                },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            awaitControlState(controlStates) { it is FrpControlState.Open }
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_041))
            listenerFactory.takeBinding()
            client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)

            val firstStop = async {
                captureRuntimeFailure { client.stopSession(sessionId, 50L) }
            }
            withTimeout(5_000L) { stopStarted.await() }
            assertEquals("stopping", client.getState(sessionId).phase)
            assertEquals(
                KotlinFrpcStcpVisitorFailure.SESSION_CLOSED,
                captureRuntimeFailure {
                    client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 5_000L)
                }.failure,
            )
            assertEquals(KotlinFrpcStcpVisitorFailure.STOP_TIMEOUT, firstStop.await().failure)
            assertEquals(0, control.closeCount.get())
            assertFalse(control.closeCalled.isCompleted)

            val secondStop = async { client.stopSession(sessionId, 5_000L) }
            assertFalse(secondStop.isCompleted)
            cleanupRelease.complete(Unit)
            withTimeout(5_000L) { control.closeCalled.await() }
            assertEquals(1, control.closeCount.get())
            assertFalse(secondStop.isCompleted)
            stopRelease.complete(Unit)
            assertEquals("closed", secondStop.await().phase)
            assertEquals(1, control.closeCount.get())
            assertEquals("closed", client.getState(sessionId).phase)

            val replacementSessionId = client.startSession(sessionConfig())
            assertEquals(sessionId, replacementSessionId)
            assertEquals("closed", client.stopSession(replacementSessionId, 5_000L).phase)
        } finally {
            cleanupRelease.complete(Unit)
            stopRelease.complete(Unit)
            control.releaseStop()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun fatalCleanupStillClosesControlAndCompletesTheSharedFlight() = runBlocking {
        val hookFailure = AssertionError("synthetic stop hook failure")
        val listenerFailure = AssertionError("synthetic listener close failure")
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val controlStates = Channel<FrpControlState>(Channel.UNLIMITED)
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                afterControlState = { controlStates.send(it) },
                beforeStopCleanup = { throw hookFailure },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            awaitControlState(controlStates) { it is FrpControlState.Open }
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_042))
            val listener = listenerFactory.takeBinding().listener
            client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)
            listener.closeFailure = listenerFailure

            var actual: Error? = null
            try {
                client.stopSession(sessionId, 5_000L)
            } catch (failure: Error) {
                actual = failure
            }
            val propagated = actual?.cause ?: actual
            assertSame(hookFailure, propagated)
            assertTrue(propagated?.suppressed?.any { (it.cause ?: it) === listenerFailure } == true)
            withTimeout(5_000L) { control.closeCalled.await() }
            assertEquals(1, control.closeCount.get())
            assertEquals(1, listener.closeCount.get())
        } finally {
            control.releaseStop()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun callerCancellationKeepsItsIdentityWhileReadinessHasNoActorWaiter() = runBlocking {
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(parentScope, controlFactory, listenerFactory)
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_043))
            val waiter = async {
                client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision + 1L, 120_000L)
            }
            val cancellation = CancellationException("caller cancellation identity")
            waiter.cancel(cancellation)
            val actual = try {
                waiter.await()
                throw AssertionError("cancelled waiter unexpectedly completed")
            } catch (failure: CancellationException) {
                failure
            }
            assertSame(cancellation, actual.cause ?: actual)
            assertEquals(1L, client.getState(sessionId).configRevision)
        } finally {
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun missingVisitorFailsImmediatelyWithoutWaitingForTimeout() = runBlocking {
        val controlFactory = FakeControlFactory()
        val parentScope = newParentScope()
        val client = newClient(parentScope, controlFactory, FakeListenerFactory())
        val sessionId = client.startSession(sessionConfig())
        try {
            val failure = captureRuntimeFailure {
                client.waitVisitorReady(sessionId, "missing", 1L, 120_000L)
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.VISITOR_NOT_FOUND, failure.failure)
        } finally {
            controlFactory.single().releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun visitorRevisionAndExactFailurePrecedeControlTerminalFailure() = runBlocking {
        val controlStates = Channel<FrpControlState>(Channel.UNLIMITED)
        val delayer = ManualRuntimeDelayer()
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            runtimeDelayer = delayer,
            hooks = KotlinFrpcRuntimeLifecycleHooks(afterControlState = { controlStates.send(it) }),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            awaitControlState(controlStates) { it is FrpControlState.Open }
            val original = client.ensureVisitor(
                sessionId,
                visitorConfig(name = "superseded", secret = "first-secret", bindPort = 50_062),
            )
            listenerFactory.takeBinding()
            client.waitVisitorReady(sessionId, "superseded", original.desiredRevision, 1_000L)
            val replacement = client.ensureVisitor(
                sessionId,
                visitorConfig(name = "superseded", secret = "second-secret", bindPort = 50_062),
            )
            listenerFactory.takeBinding()
            client.waitVisitorReady(sessionId, "superseded", replacement.desiredRevision, 1_000L)

            listenerFactory.nextFailure = BindException("synthetic bind failure")
            val failed = client.ensureVisitor(
                sessionId,
                visitorConfig(name = "failed", bindPort = 50_063),
            )
            delayer.takeCall(10_000L)

            control.fail()
            awaitControlState(controlStates) { it is FrpControlState.Failed }

            assertEquals(
                KotlinFrpcStcpVisitorFailure.VISITOR_NOT_FOUND,
                captureRuntimeFailure {
                    client.waitVisitorReady(sessionId, "missing", 1L, 120_000L)
                }.failure,
            )
            assertEquals(
                KotlinFrpcStcpVisitorFailure.VISITOR_SUPERSEDED,
                captureRuntimeFailure {
                    client.waitVisitorReady(sessionId, "superseded", original.desiredRevision, 120_000L)
                }.failure,
            )
            assertEquals(
                KotlinFrpcStcpVisitorFailure.LISTENER_BIND_FAILED,
                captureRuntimeFailure {
                    client.waitVisitorReady(sessionId, "failed", failed.desiredRevision, 120_000L)
                }.failure,
            )
            assertEquals(
                KotlinFrpcStcpVisitorFailure.LISTENER_BIND_FAILED.description,
                client.getState(sessionId).visitors.getValue("failed").lastError,
            )
        } finally {
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun unrelatedVisitorRevisionDoesNotSupersedeTargetVisitor() = runBlocking {
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(parentScope, controlFactory, listenerFactory)
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            val target = client.ensureVisitor(
                sessionId,
                visitorConfig(name = "target", bindPort = 50_060),
            )
            listenerFactory.takeBinding()
            client.waitVisitorReady(sessionId, "target", target.desiredRevision, 1_000L)
            val unrelated = client.ensureVisitor(
                sessionId,
                visitorConfig(name = "unrelated", bindPort = 50_061),
            )
            listenerFactory.takeBinding()
            client.waitVisitorReady(sessionId, "unrelated", unrelated.desiredRevision, 1_000L)

            assertEquals(
                target.desiredRevision,
                client.waitVisitorReady(sessionId, "target", target.desiredRevision, 1_000L).desiredRevision,
            )
            val future = captureRuntimeFailure {
                client.waitVisitorReady(sessionId, "target", target.desiredRevision + 1L, 50L)
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.WAIT_TIMEOUT, future.failure)
        } finally {
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun nonPositiveStopTimeoutUsesTheDefaultBudget() = runBlocking {
        val controlFactory = FakeControlFactory()
        val sessionSequence = AtomicInteger(0)
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            FakeListenerFactory(),
            sessionIdSource = FrpSessionIdSource { "stop-default-${sessionSequence.incrementAndGet()}" },
        )
        try {
            val zero = client.startSession(sessionConfig())
            assertEquals("closed", client.stopSession(zero, 0L).phase)
            val negative = client.startSession(sessionConfig())
            assertEquals("closed", client.stopSession(negative, -1L).phase)
        } finally {
            controlFactory.snapshot().forEach(FakeSessionControl::releaseStop)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun phaseContractIncludesStartingReconnectingStoppingAndStopped() = runBlocking {
        val bindEntered = CompletableDeferred<Unit>()
        val releaseBind = CompletableDeferred<Unit>()
        val stopRelease = CompletableDeferred<Unit>()
        val controlFactory = FakeControlFactory(stopRelease = stopRelease)
        val listenerFactory = FakeListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                beforeVisitorBind = { _, _ ->
                    bindEntered.complete(Unit)
                    releaseBind.await()
                },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_044))
            assertEquals("connecting", client.getState(sessionId).phase)
            assertEquals("pending", client.getState(sessionId).visitors.getValue("visitor").phase)

            control.open(epoch = 1L, transport = 1L)
            bindEntered.await()
            assertEquals("open", client.getState(sessionId).phase)
            assertEquals("starting", client.getState(sessionId).visitors.getValue("visitor").phase)
            releaseBind.complete(Unit)
            listenerFactory.takeBinding()
            client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)

            control.retry(attempt = 1)
            waitUntil { client.getState(sessionId).phase == "reconnecting" }
            val reconnecting = client.getState(sessionId)
            assertEquals("pending", reconnecting.visitors.getValue("visitor").phase)

            control.open(epoch = 2L, transport = 2L)
            listenerFactory.takeBinding()
            client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)

            val stopping = async { client.stopSession(sessionId, 5_000L) }
            control.closeCalled.await()
            val stoppingState = client.getState(sessionId)
            assertEquals("stopping", stoppingState.phase)
            assertEquals("stopped", stoppingState.visitors.getValue("visitor").phase)
            stopRelease.complete(Unit)
            assertEquals("closed", stopping.await().phase)
        } finally {
            releaseBind.complete(Unit)
            stopRelease.complete(Unit)
            control.releaseStop()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun updateInvalidatesBlockedBindAndClosesLateListenerBeforeReplacement() = runBlocking {
        val controlFactory = FakeControlFactory()
        val listenerFactory = LateListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(parentScope, controlFactory, listenerFactory)
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            val first = client.ensureVisitor(sessionId, visitorConfig(secret = "first-secret", bindPort = 50_045))
            val firstBind = listenerFactory.takeRequest()
            assertEquals("starting", client.getState(sessionId).visitors.getValue("visitor").phase)

            val update = async {
                client.ensureVisitor(sessionId, visitorConfig(secret = "second-secret", bindPort = 50_045))
            }
            assertFalse(update.isCompleted)
            firstBind.release.complete(Unit)
            val second = update.await()
            assertEquals(first.desiredRevision + 1L, second.desiredRevision)
            assertEquals(1, firstBind.listener.closeCount.get())
            val secondBind = listenerFactory.takeRequest()
            secondBind.release.complete(Unit)
            client.waitVisitorReady(sessionId, "visitor", second.desiredRevision, 1_000L)
        } finally {
            listenerFactory.releaseAll()
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
        Unit
    }

    @Test
    fun reconnectInvalidatesBlockedBindAndClosesLateListener() = runBlocking {
        val controlFactory = FakeControlFactory()
        val listenerFactory = LateListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(parentScope, controlFactory, listenerFactory)
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_046))
            val firstBind = listenerFactory.takeRequest()

            control.retry(attempt = 1)
            assertEquals("reconnecting", client.getState(sessionId).phase)
            firstBind.release.complete(Unit)
            waitUntil { firstBind.listener.closeCount.get() == 1 }

            control.open(epoch = 2L, transport = 2L)
            val secondBind = listenerFactory.takeRequest()
            secondBind.release.complete(Unit)
            assertEquals(
                2L,
                client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L).boundControlEpoch,
            )
        } finally {
            listenerFactory.releaseAll()
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun stopInvalidatesBlockedBindAndClosesLateListener() = runBlocking {
        val controlFactory = FakeControlFactory()
        val listenerFactory = LateListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(parentScope, controlFactory, listenerFactory)
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_047))
            val pendingBind = listenerFactory.takeRequest()

            val stop = async { client.stopSession(sessionId, 5_000L) }
            waitUntil { client.getState(sessionId).phase == "stopping" }
            pendingBind.release.complete(Unit)
            assertEquals("closed", stop.await().phase)
            assertEquals(1, pendingBind.listener.closeCount.get())
        } finally {
            listenerFactory.releaseAll()
            control.releaseStop()
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun mailboxFullClosesAcceptedSocketsAndReliablyFailsThenRetriesVisitor() = runBlocking {
        val blockNextEnsure = AtomicBoolean(false)
        val actorBlocked = CompletableDeferred<Unit>()
        val releaseActor = CompletableDeferred<Unit>()
        val delayer = ManualRuntimeDelayer()
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            runtimeDelayer = delayer,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                beforeRuntimeCommand = { kind ->
                    if (kind == "EnsureVisitorCommand" && blockNextEnsure.compareAndSet(true, false)) {
                        actorBlocked.complete(Unit)
                        releaseActor.await()
                    }
                },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_048))
            val listener = listenerFactory.takeBinding().listener
            client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)

            blockNextEnsure.set(true)
            val sameEnsure = async { client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_048)) }
            actorBlocked.await()
            val connections = List(65) { FakeLocalConnection(ByteArray(0)) }
            connections.forEach(listener::offer)
            waitUntil { connections.last().closeCount.get() == 1 }
            assertEquals(1, listener.closeCount.get())

            releaseActor.complete(Unit)
            sameEnsure.await()
            delayer.takeCall(10_000L)
            waitUntil { connections.all { it.closeCount.get() == 1 } }
            assertEquals("failed", client.getState(sessionId).visitors.getValue("visitor").phase)
        } finally {
            releaseActor.complete(Unit)
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
        Unit
    }

    @Test
    fun acceptFailureRevokesReadinessWhileActorIsBlocked() = runBlocking {
        val blockNextEnsure = AtomicBoolean(false)
        val actorBlocked = CompletableDeferred<Unit>()
        val releaseActor = CompletableDeferred<Unit>()
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                beforeRuntimeCommand = { kind ->
                    if (kind == "EnsureVisitorCommand" && blockNextEnsure.compareAndSet(true, false)) {
                        actorBlocked.complete(Unit)
                        releaseActor.await()
                    }
                },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_051))
            val listener = listenerFactory.takeBinding().listener
            client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)

            blockNextEnsure.set(true)
            val sameEnsure = async { client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_051)) }
            actorBlocked.await()
            listener.failAccept(BindException("synthetic accept failure"))
            waitUntil { listener.closeCount.get() == 1 }

            val state = client.getState(sessionId).visitors.getValue("visitor")
            assertFalse(state.listenerBound)
            assertEquals("failed", state.phase)
            val readiness = captureRuntimeFailure {
                client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)
            }
            assertEquals(KotlinFrpcStcpVisitorFailure.VISITOR_FAILED, readiness.failure)

            releaseActor.complete(Unit)
            sameEnsure.await()
        } finally {
            releaseActor.complete(Unit)
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
        Unit
    }

    @Test
    fun parentCancellationDuringStopDrainsMailboxAndRemovesRegistryAfterAllResourcesStop() = runBlocking {
        val blockNextEnsure = AtomicBoolean(false)
        val actorBlocked = CompletableDeferred<Unit>()
        val releaseActor = CompletableDeferred<Unit>()
        val cleanupObserved = CompletableDeferred<KotlinFrpcSessionCleanupDiagnostics>()
        val stopRelease = CompletableDeferred<Unit>()
        val controlFactory = FakeControlFactory(stopRelease = stopRelease)
        val listenerFactory = FakeListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                beforeRuntimeCommand = { kind ->
                    if (kind == "EnsureVisitorCommand" && blockNextEnsure.compareAndSet(true, false)) {
                        actorBlocked.complete(Unit)
                        releaseActor.await()
                    }
                },
                beforeSessionRegistryRemoval = { cleanupObserved.complete(it) },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            val ensured = client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_049))
            val listener = listenerFactory.takeBinding().listener
            client.waitVisitorReady(sessionId, "visitor", ensured.desiredRevision, 1_000L)

            blockNextEnsure.set(true)
            val blockedEnsure = async { captureRuntimeFailure { client.ensureVisitor(sessionId, visitorConfig(bindPort = 50_049)) } }
            actorBlocked.await()
            val pendingSockets = List(8) { FakeLocalConnection(ByteArray(0)) }
            pendingSockets.forEach(listener::offer)
            waitUntil { listener.acceptCount.get() == pendingSockets.size }

            val stop = async {
                try {
                    client.stopSession(sessionId, 5_000L)
                } catch (_: CancellationException) {
                    null
                }
            }
            parentScope.cancel()
            stopRelease.complete(Unit)
            releaseActor.complete(Unit)
            stop.await()
            val cleanup = cleanupObserved.await()
            assertEquals(0, cleanup.activeOwnedJobs)
            assertEquals(0, cleanup.activeListeners)
            assertEquals(0, cleanup.activeRelays)
            assertTrue(cleanup.actorCompleted)
            assertTrue(cleanup.controlCollectorCompleted)
            assertTrue(pendingSockets.all { it.closeCount.get() == 1 })
            assertEquals(0, client.diagnostics().activeSessions)
            assertTrue(blockedEnsure.isCompleted || blockedEnsure.await().failure == KotlinFrpcStcpVisitorFailure.SESSION_CLOSED)
        } finally {
            releaseActor.complete(Unit)
            stopRelease.complete(Unit)
            control.releaseStop()
            client.close()
        }
    }

    @Test
    fun globalRelayBudgetIsSharedAcrossSessionsAndLeavesReverseIoCapacity() = runBlocking {
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val sessionSequence = AtomicInteger(0)
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            sessionIdSource = FrpSessionIdSource { "relay-session-${sessionSequence.incrementAndGet()}" },
            ioDispatcher = Dispatchers.IO.limitedParallelism(64, "KotlinFrpcRelayBudgetTest"),
        )
        val sessions = mutableListOf<String>()
        val localConnections = mutableListOf<BlockingLocalConnection>()
        try {
            repeat(2) { index ->
                val sessionId = client.startSession(sessionConfig())
                sessions += sessionId
                val control = controlFactory.snapshot()[index]
                control.open(epoch = 1L, transport = 1L)
                val ensured = client.ensureVisitor(
                    sessionId,
                    visitorConfig(name = "visitor-$index", bindPort = 50_050 + index),
                )
                val listener = listenerFactory.takeBinding().listener
                client.waitVisitorReady(sessionId, "visitor-$index", ensured.desiredRevision, 1_000L)
                repeat(16) { relayIndex ->
                    val response = FrpWireV1.encodeMessage(
                        NewVisitorConnResp(proxyName = "ignored-response-$index-$relayIndex"),
                    )
                    control.enqueueStream(ScriptedMuxStream(response + byteArrayOf(relayIndex.toByte())))
                    BlockingLocalConnection().also { local ->
                        localConnections += local
                        listener.offer(local)
                    }
                }
            }
            waitUntil {
                localConnections.size == 32 &&
                    localConnections.all { it.readStarted.isCompleted && it.shutdownOutputCount.get() == 1 }
            }
            assertEquals(32, client.diagnostics().activeRelays)

            val rejected = FakeLocalConnection(ByteArray(0))
            listenerFactory.bindingSnapshot().first().listener.offer(rejected)
            waitUntil { rejected.closeCount.get() == 1 }
            assertEquals(32, client.diagnostics().activeRelays)
            assertEquals(1, client.diagnostics().rejectedRelays)

            localConnections.forEach(BlockingLocalConnection::releaseRead)
            waitUntil { client.diagnostics().activeRelays == 0 }
        } finally {
            localConnections.forEach(BlockingLocalConnection::releaseRead)
            controlFactory.snapshot().forEach(FakeSessionControl::releaseStop)
            sessions.forEach { client.stopSession(it, 5_000L) }
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun relayZeroLengthReadFailsTypedWithoutFailingControl() = runBlocking {
        val parentScope = newParentScope()
        val control = FakeSessionControl(
            generation = 1L,
            protocol = FrpWireProtocol.V1,
            parentScope = parentScope,
            stopRelease = null,
        )
        val identity = control.open(epoch = 1L, transport = 1L)
        control.enqueueStream(
            ScriptedMuxStream(FrpWireV1.encodeMessage(NewVisitorConnResp(proxyName = "ignored"))),
        )
        val relay = FrpVisitorRelay(
            visitorRevision = 1L,
            listenerAttempt = 1L,
            control = control,
            identity = identity,
            visitor = FrpRelayVisitorConfig(
                serverName = "remote-service",
                serverUser = "",
                sessionUser = "",
                secretKey = SYNTHETIC_SECRET,
            ),
            local = ZeroReadLocalConnection(),
            parentScope = parentScope,
            unixTimeSource = FrpUnixTimeSource { FIXED_TIMESTAMP },
            ioDispatcher = Dispatchers.IO,
        )
        try {
            relay.start()
            val failure = captureRuntimeFailure { relay.awaitStopped() }
            assertEquals(KotlinFrpcStcpVisitorFailure.RELAY_FAILED, failure.failure)
            assertTrue(control.state.value is FrpControlState.Open)
        } finally {
            relay.close()
            control.close()
            control.releaseStop()
            control.awaitStopped()
            parentScope.cancel()
        }
    }

    @Test
    fun relayFailureClosesUninterruptibleSiblingBeforeJoiningPumps() = runBlocking {
        val releaseRemoteFailure = CompletableDeferred<Unit>()
        val parentScope = newParentScope()
        val control = FakeSessionControl(
            generation = 1L,
            protocol = FrpWireProtocol.V1,
            parentScope = parentScope,
            stopRelease = null,
        )
        val identity = control.open(epoch = 1L, transport = 1L)
        val stream = ScriptedMuxStream(
            inputBytes = FrpWireV1.encodeMessage(NewVisitorConnResp(proxyName = "ignored")),
            afterInputRelease = releaseRemoteFailure,
            afterInputResult = 0,
        )
        control.enqueueStream(stream)
        val local = UninterruptibleLocalConnection()
        val relay = FrpVisitorRelay(
            visitorRevision = 1L,
            listenerAttempt = 1L,
            control = control,
            identity = identity,
            visitor = FrpRelayVisitorConfig(
                serverName = "remote-service",
                serverUser = "",
                sessionUser = "",
                secretKey = SYNTHETIC_SECRET,
            ),
            local = local,
            parentScope = parentScope,
            unixTimeSource = FrpUnixTimeSource { FIXED_TIMESTAMP },
            ioDispatcher = Dispatchers.IO,
        )
        try {
            relay.start()
            local.awaitReadStarted()
            releaseRemoteFailure.complete(Unit)

            val failure = captureRuntimeFailure { relay.awaitStopped() }
            assertEquals(KotlinFrpcStcpVisitorFailure.RELAY_FAILED, failure.failure)
            assertEquals(1, local.closeCount.get())
            assertEquals(1, stream.resetCount.get())
            assertTrue(control.state.value is FrpControlState.Open)
        } finally {
            releaseRemoteFailure.complete(Unit)
            relay.close()
            control.close()
            control.releaseStop()
            control.awaitStopped()
            parentScope.cancel()
        }
    }

    @Test
    fun lazyRelayClosedBeforeFirstDispatchCompletesExactlyOnce() = runBlocking {
        val dispatcher = QueueingDispatcher()
        val parentScope = CoroutineScope(SupervisorJob() + dispatcher)
        val control = FakeSessionControl(
            generation = 1L,
            protocol = FrpWireProtocol.V1,
            parentScope = parentScope,
            stopRelease = null,
        )
        val identity = control.open(epoch = 1L, transport = 1L)
        val local = FakeLocalConnection(ByteArray(0))
        val relay = FrpVisitorRelay(
            visitorRevision = 1L,
            listenerAttempt = 1L,
            control = control,
            identity = identity,
            visitor = FrpRelayVisitorConfig(
                serverName = "remote-service",
                serverUser = "",
                sessionUser = "",
                secretKey = SYNTHETIC_SECRET,
            ),
            local = local,
            parentScope = parentScope,
            unixTimeSource = FrpUnixTimeSource { FIXED_TIMESTAMP },
            ioDispatcher = Dispatchers.IO,
        )
        try {
            relay.start()
            assertEquals(1, dispatcher.queuedCount)

            relay.close()

            withTimeout(5_000L) { relay.awaitStopped() }
            assertTrue(relay.isCompleted)
            assertEquals(1, local.closeCount.get())
            assertEquals(1, dispatcher.queuedCount)
        } finally {
            relay.close()
            parentScope.cancel()
        }
    }

    @Test
    fun parentCancellationBeforeActorFirstDispatchRunsEmergencyCleanup() = runBlocking {
        val dispatcher = QueueingDispatcher()
        val parentJob = SupervisorJob()
        val parentScope = CoroutineScope(parentJob + dispatcher)
        val controlFactory = FakeControlFactory()
        val cleanupDiagnostics = CompletableDeferred<KotlinFrpcSessionCleanupDiagnostics>()
        val client = newClient(
            parentScope = parentScope,
            controlFactory = controlFactory,
            listenerFactory = FakeListenerFactory(),
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                beforeSessionRegistryRemoval = { cleanupDiagnostics.complete(it) },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            assertTrue(dispatcher.queuedCount >= 2)
            parentJob.cancel()
            val stopped = async { client.stopSession(sessionId, 5_000L) }

            withTimeout(5_000L) {
                while (!stopped.isCompleted) {
                    if (!dispatcher.runNextLifo()) delay(1L)
                }
            }

            assertEquals("closed", stopped.await().phase)
            val diagnostics = withTimeout(5_000L) { cleanupDiagnostics.await() }
            assertEquals(0, diagnostics.activeOwnedJobs)
            assertEquals(0, diagnostics.activeListeners)
            assertEquals(0, diagnostics.activeRelays)
            assertTrue(diagnostics.actorCompleted)
            assertTrue(diagnostics.controlCollectorCompleted)
            assertEquals(0, client.diagnostics().activeSessions)
            assertEquals(1, control.closeCount.get())
            dispatcher.runAllLifo()
            assertEquals(0, dispatcher.queuedCount)
        } finally {
            parentJob.cancel()
            dispatcher.runAllLifo()
            client.close()
        }
    }

    @Test
    fun defaultSessionLimitIsFour() = runBlocking {
        val controlFactory = FakeControlFactory()
        val sequence = AtomicInteger(0)
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            FakeListenerFactory(),
            sessionIdSource = FrpSessionIdSource { "limit-session-${sequence.incrementAndGet()}" },
        )
        val sessions = mutableListOf<String>()
        try {
            repeat(4) { sessions += client.startSession(sessionConfig()) }
            val failure = captureRuntimeFailure { client.startSession(sessionConfig()) }
            assertEquals(KotlinFrpcStcpVisitorFailure.SESSION_LIMIT, failure.failure)
        } finally {
            controlFactory.snapshot().forEach(FakeSessionControl::releaseStop)
            sessions.forEach { client.stopSession(it, 5_000L) }
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun protocolNormalizationDefaultsBlanksButRejectsUppercaseValues() = runBlocking {
        val controlFactory = FakeControlFactory()
        val sequence = AtomicInteger(0)
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            FakeListenerFactory(),
            sessionIdSource = FrpSessionIdSource { "protocol-session-${sequence.incrementAndGet()}" },
        )
        try {
            val blank = client.startSession(
                sessionConfig(transportProtocol = "  ", wireProtocol = "\t"),
            )
            val normalized = controlFactory.configSnapshot().single()
            assertEquals("tcp", normalized.transportProtocol)
            assertEquals("v1", normalized.wireProtocol)
            client.stopSession(blank, 5_000L)

            assertEquals(
                KotlinFrpcStcpVisitorFailure.UNSUPPORTED_CONFIGURATION,
                captureRuntimeFailure {
                    client.startSession(sessionConfig(transportProtocol = "TCP", wireProtocol = "v1"))
                }.failure,
            )
            assertEquals(
                KotlinFrpcStcpVisitorFailure.UNSUPPORTED_CONFIGURATION,
                captureRuntimeFailure {
                    client.startSession(sessionConfig(transportProtocol = "tcp", wireProtocol = "V1"))
                }.failure,
            )
            assertEquals(1, controlFactory.snapshot().size)
        } finally {
            controlFactory.snapshot().forEach(FakeSessionControl::releaseStop)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun bindAndCommandSummariesNeverExposeVisitorName() = runBlocking {
        val marker = "synthetic-private-visitor-name"
        val summaries = Channel<String>(Channel.UNLIMITED)
        val controlFactory = FakeControlFactory()
        val listenerFactory = FakeListenerFactory()
        val parentScope = newParentScope()
        val client = newClient(
            parentScope,
            controlFactory,
            listenerFactory,
            hooks = KotlinFrpcRuntimeLifecycleHooks(
                observeRuntimeCommandSummary = { summary -> check(summaries.trySend(summary).isSuccess) },
            ),
        )
        val sessionId = client.startSession(sessionConfig())
        val control = controlFactory.single()
        try {
            control.open(epoch = 1L, transport = 1L)
            val ensured = client.ensureVisitor(
                sessionId,
                visitorConfig(name = marker, bindPort = 50_064),
            )
            listenerFactory.takeBinding()
            client.waitVisitorReady(sessionId, marker, ensured.desiredRevision, 1_000L)

            val observed = mutableListOf<String>()
            withTimeout(5_000L) {
                while (observed.none { it.startsWith("BindCompletedCommand") }) {
                    observed += summaries.receive()
                }
            }
            assertTrue(observed.any { it.contains("namePresent=true") })
            assertTrue(observed.none { it.contains(marker) })
        } finally {
            control.releaseStop()
            client.stopSession(sessionId, 5_000L)
            client.close()
            parentScope.cancel()
        }
    }

    @Test
    fun productionControlLeavesApplicationHeartbeatDisabled() {
        val config = productionFrpControlConfig(sessionConfig(), FrpWireProtocol.V1)

        assertEquals(FrpControlConfig.DISABLED_HEARTBEAT_MILLIS, config.heartbeatIntervalMillis)
        assertEquals(FrpControlConfig.DISABLED_HEARTBEAT_MILLIS, config.heartbeatTimeoutMillis)
    }

    private fun newClient(
        parentScope: CoroutineScope,
        controlFactory: FrpSessionControlFactory,
        listenerFactory: FrpLocalListenerFactory,
        unixTimeSource: FrpUnixTimeSource = FrpUnixTimeSource { FIXED_TIMESTAMP },
        hooks: KotlinFrpcRuntimeLifecycleHooks = KotlinFrpcRuntimeLifecycleHooks(),
        runtimeDelayer: FrpRuntimeDelayer = CoroutineFrpRuntimeDelayer,
        sessionIdSource: FrpSessionIdSource = FrpSessionIdSource { "runtime-session-fixture" },
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    ): KotlinFrpcStcpVisitorClient = KotlinFrpcStcpVisitorClient(
        parentScope = parentScope,
        controlFactory = controlFactory,
        listenerFactory = listenerFactory,
        unixTimeSource = unixTimeSource,
        sessionIdSource = sessionIdSource,
        ioDispatcher = ioDispatcher,
        lifecycleHooks = hooks,
        runtimeDelayer = runtimeDelayer,
        testOnly = Unit,
    )

    private fun sessionConfig(
        user: String? = null,
        transportProtocol: String = "tcp",
        wireProtocol: String = "v1",
    ): FrpcSessionConfig = FrpcSessionConfig(
        serverAddr = "frps.example.invalid",
        serverPort = 7000,
        authToken = "synthetic-token",
        user = user,
        transportProtocol = transportProtocol,
        wireProtocol = wireProtocol,
    )

    private fun visitorConfig(
        name: String = "visitor",
        serverName: String = "remote-service",
        serverUser: String? = null,
        secret: String = SYNTHETIC_SECRET,
        bindAddr: String = "127.0.0.1",
        bindPort: Int,
    ): FrpcStcpVisitorConfig = FrpcStcpVisitorConfig(
        name = name,
        serverName = serverName,
        serverUser = serverUser,
        secretKey = secret,
        bindAddr = bindAddr,
        bindPort = bindPort,
    )

    private suspend fun awaitControlState(
        states: Channel<FrpControlState>,
        predicate: (FrpControlState) -> Boolean,
    ): FrpControlState = withTimeout(5_000L) {
        while (true) {
            val state = states.receive()
            if (predicate(state)) return@withTimeout state
        }
        throw AssertionError("unreachable")
    }

    private suspend fun waitUntil(predicate: suspend () -> Boolean) {
        withTimeout(5_000L) {
            while (!predicate()) delay(10L)
        }
    }

    private suspend fun captureRuntimeFailure(
        block: suspend () -> Any?,
    ): KotlinFrpcStcpVisitorException = try {
        block()
        throw AssertionError("operation unexpectedly succeeded")
    } catch (failure: KotlinFrpcStcpVisitorException) {
        failure
    }

    private fun newParentScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private companion object {
        const val FIXED_TIMESTAMP = 1_700_000_000L
        const val SYNTHETIC_SECRET = "obviously-synthetic-stcp-secret"
    }
}

private class FakeControlFactory(
    private val protocol: FrpWireProtocol = FrpWireProtocol.V1,
    private val stopRelease: CompletableDeferred<Unit>? = null,
) : FrpSessionControlFactory {
    private val controls = Collections.synchronizedList(mutableListOf<FakeSessionControl>())
    private val configs = Collections.synchronizedList(mutableListOf<FrpcSessionConfig>())

    override fun create(
        config: FrpcSessionConfig,
        parentScope: CoroutineScope,
        generation: Long,
    ): FrpSessionControl {
        configs += config
        return FakeSessionControl(
            generation = generation,
            protocol = protocol,
            parentScope = parentScope,
            stopRelease = stopRelease,
        ).also(controls::add)
    }

    fun single(): FakeSessionControl = synchronized(controls) { controls.single() }

    fun snapshot(): List<FakeSessionControl> = synchronized(controls) { controls.toList() }

    fun configSnapshot(): List<FrpcSessionConfig> = synchronized(configs) { configs.toList() }
}

private class FakeSessionControl(
    private val generation: Long,
    private val protocol: FrpWireProtocol,
    parentScope: CoroutineScope,
    private val stopRelease: CompletableDeferred<Unit>?,
) : FrpSessionControl {
    private val mutableState = MutableStateFlow<FrpControlState>(
        FrpControlState.Connecting(generation, attempt = 0),
    )
    private val streams = Channel<FrpMuxStream>(Channel.UNLIMITED)
    private val activeLeases = Collections.synchronizedSet(mutableSetOf<FrpClientStreamLease>())
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(
        parentScope.coroutineContext.minusKey(Job) + cleanupJob,
    )
    private val stopped = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    val closeCount = AtomicInteger(0)
    val closeCalled = CompletableDeferred<Unit>()

    override val state: StateFlow<FrpControlState> = mutableState

    override fun start() = Unit

    fun open(epoch: Long, transport: Long): FrpControlIdentity =
        FrpControlIdentity(generation, epoch, transport).also { identity ->
            mutableState.value = FrpControlState.Open(identity)
        }

    fun retry(attempt: Int) {
        mutableState.value = FrpControlState.Retrying(
            generation = generation,
            attempt = attempt,
            delayMillis = 100L,
            failure = io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control.FrpControlFailure.TRANSPORT_FAILED,
        )
    }

    fun fail() {
        mutableState.value = FrpControlState.Failed(
            generation = generation,
            failure = io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control.FrpControlFailure.TRANSPORT_FAILED,
        )
    }

    fun enqueueStream(stream: FrpMuxStream) {
        check(streams.trySend(stream).isSuccess)
    }

    override suspend fun openClientStream(expectedIdentity: FrpControlIdentity): FrpClientStreamLease {
        val current = mutableState.value as? FrpControlState.Open
            ?: throw FrpTransportException(FrpTransportFailure.CLOSED)
        if (current.identity != expectedIdentity) throw FrpTransportException(FrpTransportFailure.CLOSED)
        val stream = streams.receive()
        lateinit var lease: FrpClientStreamLease
        lease = FrpClientStreamLease(
            identity = expectedIdentity,
            runId = "run-fixture",
            wireProtocol = protocol,
            delegate = stream,
            cleanupScope = cleanupScope,
            onReleased = { activeLeases.remove(it) },
        )
        activeLeases += lease
        return lease
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeCount.incrementAndGet()
        closeCalled.complete(Unit)
        mutableState.value = FrpControlState.Closed(generation)
        synchronized(activeLeases) { activeLeases.toList() }.forEach(FrpClientStreamLease::beginReset)
        cleanupScope.launch {
            stopRelease?.await()
            val leases = synchronized(activeLeases) { activeLeases.toList() }
            leases.forEach { it.reset() }
            stopped.complete(Unit)
            cleanupJob.cancel()
        }
    }

    fun releaseStop() {
        if (stopRelease == null) stopped.complete(Unit)
    }

    override suspend fun awaitStopped() {
        stopped.await()
    }

    override fun toString(): String =
        "FakeSessionControl(generation=$generation, protocol=$protocol, closed=${closed.get()})"
}

private class FakeListenerFactory(
    private val firstEphemeralPort: Int = 43_100,
) : FrpLocalListenerFactory {
    private val bindings = Channel<FakeBinding>(Channel.UNLIMITED)
    private val recordedBindings = Collections.synchronizedList(mutableListOf<FakeBinding>())
    private val ephemeralSequence = AtomicInteger(firstEphemeralPort)
    val bindCount = AtomicInteger(0)
    @Volatile
    var nextFailure: Exception? = null

    override suspend fun bind(address: InetAddress, port: Int): FrpLocalListener {
        bindCount.incrementAndGet()
        nextFailure?.let { failure ->
            nextFailure = null
            throw failure
        }
        val listener = FakeLocalListener(if (port == 0) ephemeralSequence.getAndIncrement() else port)
        val binding = FakeBinding(address, port, listener)
        recordedBindings += binding
        bindings.send(binding)
        return listener
    }

    suspend fun takeBinding(): FakeBinding = withTimeout(5_000L) { bindings.receive() }

    fun bindingSnapshot(): List<FakeBinding> = synchronized(recordedBindings) { recordedBindings.toList() }
}

private class LateListenerFactory : FrpLocalListenerFactory {
    private val requests = Channel<LateBindRequest>(Channel.UNLIMITED)
    private val recorded = Collections.synchronizedList(mutableListOf<LateBindRequest>())

    override suspend fun bind(address: InetAddress, port: Int): FrpLocalListener {
        val request = LateBindRequest(address, port, FakeLocalListener(port))
        recorded += request
        requests.send(request)
        return try {
            withContext(NonCancellable) { request.release.await() }
            currentCoroutineContext().ensureActive()
            request.listener
        } catch (failure: CancellationException) {
            request.listener.close()
            throw failure
        } catch (failure: Error) {
            request.listener.close()
            throw failure
        } catch (failure: Exception) {
            request.listener.close()
            throw failure
        }
    }

    suspend fun takeRequest(): LateBindRequest = withTimeout(5_000L) { requests.receive() }

    fun releaseAll() {
        synchronized(recorded) { recorded.toList() }.forEach { it.release.complete(Unit) }
    }
}

private data class LateBindRequest(
    val address: InetAddress,
    val requestedPort: Int,
    val listener: FakeLocalListener,
    val release: CompletableDeferred<Unit> = CompletableDeferred(),
)

private class ManualRuntimeDelayer : FrpRuntimeDelayer {
    private val calls = Channel<RuntimeDelayCall>(Channel.UNLIMITED)

    override suspend fun delayMillis(milliseconds: Long) {
        val call = RuntimeDelayCall(milliseconds)
        calls.send(call)
        call.release.await()
    }

    suspend fun takeCall(expectedMillis: Long): RuntimeDelayCall = withTimeout(5_000L) {
        calls.receive().also { assertEquals(expectedMillis, it.millis) }
    }
}

private data class RuntimeDelayCall(
    val millis: Long,
    val release: CompletableDeferred<Unit> = CompletableDeferred(),
)

private data class FakeBinding(
    val address: InetAddress,
    val requestedPort: Int,
    val listener: FakeLocalListener,
)

private class FakeLocalListener(
    override val bindPort: Int,
) : FrpLocalListener {
    private val connections = Channel<FrpLocalConnection>(Channel.UNLIMITED)
    private val closed = AtomicBoolean(false)
    val closeCount = AtomicInteger(0)
    val acceptCount = AtomicInteger(0)
    @Volatile
    var closeFailure: Error? = null

    fun offer(connection: FrpLocalConnection) {
        if (!connections.trySend(connection).isSuccess) connection.close()
    }

    fun failAccept(failure: Throwable) {
        connections.close(failure)
    }

    override suspend fun accept(): FrpLocalConnection = connections.receive().also { acceptCount.incrementAndGet() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeCount.incrementAndGet()
        connections.close()
        closeFailure?.let { throw it }
    }

    override fun toString(): String = "FakeLocalListener(closed=${closed.get()})"
}

private class FakeLocalConnection(
    inputBytes: ByteArray,
) : FrpLocalConnection {
    private val closed = AtomicBoolean(false)
    private val outputBytes = ByteArrayOutputStream()
    override val input: InputStream = ByteArrayInputStream(inputBytes)
    override val output: OutputStream = outputBytes
    val shutdownOutputCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)

    override fun shutdownOutput() {
        shutdownOutputCount.incrementAndGet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeCount.incrementAndGet()
    }

    fun writtenBytes(): ByteArray = outputBytes.toByteArray()

    override fun toString(): String = "FakeLocalConnection(closed=${closed.get()})"
}

private class BlockingLocalConnection : FrpLocalConnection {
    private val release = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val outputBytes = ByteArrayOutputStream()
    val readStarted = CompletableDeferred<Unit>()
    val shutdownOutputCount = AtomicInteger(0)
    val closeCount = AtomicInteger(0)

    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            readStarted.complete(Unit)
            runBlocking { release.await() }
            return -1
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int = read()
    }

    override val output: OutputStream = outputBytes

    override fun shutdownOutput() {
        shutdownOutputCount.incrementAndGet()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeCount.incrementAndGet()
        release.complete(Unit)
    }

    fun releaseRead() {
        release.complete(Unit)
    }
}

private class UninterruptibleLocalConnection : FrpLocalConnection {
    private val readStarted = CountDownLatch(1)
    private val releaseRead = CountDownLatch(1)
    private val closed = AtomicBoolean(false)
    override val input: InputStream = object : InputStream() {
        override fun read(): Int {
            readStarted.countDown()
            while (true) {
                try {
                    releaseRead.await()
                    return -1
                } catch (_: InterruptedException) {
                    // The fixture models a socket read that ignores thread interruption.
                }
            }
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int = read()
    }
    override val output: OutputStream = ByteArrayOutputStream()
    val closeCount = AtomicInteger(0)

    suspend fun awaitReadStarted() {
        assertTrue(withContext(Dispatchers.IO) { readStarted.await(5, TimeUnit.SECONDS) })
    }

    override fun shutdownOutput() = Unit

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeCount.incrementAndGet()
        releaseRead.countDown()
    }
}

private class ZeroReadLocalConnection : FrpLocalConnection {
    private val closed = AtomicBoolean(false)
    override val input: InputStream = object : InputStream() {
        override fun read(): Int = 0

        override fun read(destination: ByteArray, offset: Int, length: Int): Int = 0
    }
    override val output: OutputStream = ByteArrayOutputStream()

    override fun shutdownOutput() = Unit

    override fun close() {
        closed.set(true)
    }
}

private class ScriptedMuxStream(
    inputBytes: ByteArray,
    private val firstReadRelease: CompletableDeferred<Unit>? = null,
    private val afterInputRelease: CompletableDeferred<Unit>? = null,
    private val afterInputResult: Int = -1,
) : FrpMuxStream {
    private val input = inputBytes.copyOf()
    private var inputOffset = 0
    private val output = ByteArrayOutputStream()
    private val outputLock = Any()
    private val reset = AtomicBoolean(false)
    val closeWriteCount = AtomicInteger(0)
    val resetCount = AtomicInteger(0)
    val resetStarted = CompletableDeferred<Unit>()
    val readStarted = CompletableDeferred<Unit>()
    private val firstRead = AtomicBoolean(true)

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        readStarted.complete(Unit)
        if (firstRead.compareAndSet(true, false)) firstReadRelease?.await()
        if (reset.get()) return -1
        if (inputOffset >= input.size) {
            afterInputRelease?.await()
            if (reset.get()) return -1
            return afterInputResult
        }
        val count = minOf(length, input.size - inputOffset)
        input.copyInto(destination, offset, inputOffset, inputOffset + count)
        inputOffset += count
        return count
    }

    override suspend fun write(source: ByteArray, offset: Int, length: Int) {
        if (reset.get()) throw FrpTransportException(FrpTransportFailure.CLOSED)
        synchronized(outputLock) { output.write(source, offset, length) }
    }

    override suspend fun closeWrite() {
        closeWriteCount.incrementAndGet()
    }

    override suspend fun reset() {
        if (!reset.compareAndSet(false, true)) return
        resetCount.incrementAndGet()
        resetStarted.complete(Unit)
        firstReadRelease?.complete(Unit)
        afterInputRelease?.complete(Unit)
    }

    fun writtenBytes(): ByteArray = synchronized(outputLock) { output.toByteArray() }

    override fun toString(): String = "ScriptedMuxStream(reset=${reset.get()})"
}

private class QueueingDispatcher : CoroutineDispatcher() {
    private val queue = ConcurrentLinkedDeque<Runnable>()

    val queuedCount: Int
        get() = queue.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    fun runNextLifo(): Boolean {
        val next = queue.pollLast() ?: return false
        next.run()
        return true
    }

    fun runAllLifo() {
        while (runNextLifo()) {
            // Drain tasks added by cleanup continuations before returning.
        }
    }
}
