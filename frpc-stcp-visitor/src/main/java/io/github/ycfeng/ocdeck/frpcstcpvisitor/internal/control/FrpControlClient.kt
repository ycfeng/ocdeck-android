package io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.control

import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.crypto.FrpCryptoException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.crypto.FrpTimestampAuth
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.ClientSpec
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.FrpMessage
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.FrpProtocolException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.FrpWireV1
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.FrpWireV2
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.Login
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.NewWorkConn
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.Ping
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.Pong
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.ReqWorkConn
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.protocol.StartWorkConn
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.BlockingIoThreadGuard
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpMuxConnection
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpMuxConnector
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpMuxStream
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.FrpTransportException
import io.github.ycfeng.ocdeck.frpcstcpvisitor.internal.transport.YamuxStreamBlockingIo
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.LinkedHashSet
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal fun interface FrpWorkConnectionHandler {
    suspend fun handle(connection: FrpWorkConnection)
}

internal data class FrpControlLifecycleHooks(
    val beforeOpenPublish: suspend (FrpControlIdentity) -> Unit = {},
    val beforeWorkDelivery: suspend (FrpControlIdentity) -> Unit = {},
    val afterWorkRegistration: suspend (FrpControlIdentity) -> Unit = {},
    val beforeStatePublish: (FrpControlState) -> Unit = {},
)

internal class FrpWorkConnection internal constructor(
    val identity: FrpControlIdentity,
    val start: StartWorkConn,
    internal val stream: FrpMuxStream,
    private val blockingIo: YamuxStreamBlockingIo,
) : AutoCloseable {
    val input: InputStream
        get() = blockingIo.input

    val output: OutputStream
        get() = blockingIo.output

    val isClosed: Boolean
        get() = blockingIo.isClosed

    suspend fun closeWrite() {
        if (blockingIo.isClosed) throw FrpControlException(FrpControlFailure.CLOSED)
        stream.closeWrite()
    }

    override fun close() {
        blockingIo.close()
    }

    suspend fun awaitClosed() {
        blockingIo.awaitReset()
    }

    override fun toString(): String =
        "FrpWorkConnection(identity=$identity, proxyNamePresent=${start.proxyName.isNotEmpty()}, " +
            "sourcePresent=${start.srcAddr.isNotEmpty() || start.srcPort != 0}, " +
            "destinationPresent=${start.dstAddr.isNotEmpty() || start.dstPort != 0}, " +
            "errorPresent=${start.error.isNotEmpty()}, closed=$isClosed)"
}

internal data class FrpControlDiagnostics(
    val started: Boolean,
    val closed: Boolean,
    val runnerActive: Boolean,
    val identity: FrpControlIdentity?,
    val activeWorkConnections: Int,
    val workFailureCount: Long,
)

internal class FrpControlClient(
    private val config: FrpControlConfig,
    private val connector: FrpMuxConnector,
    parentScope: CoroutineScope,
    private val generation: Long,
    private val workHandler: FrpWorkConnectionHandler = FrpWorkConnectionHandler { it.close() },
    private val unixTimeSource: FrpUnixTimeSource = SystemFrpUnixTimeSource,
    private val monotonicTimeSource: FrpMonotonicTimeSource = SystemFrpMonotonicTimeSource,
    private val delayer: FrpControlDelayer = CoroutineFrpControlDelayer,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val lifecycleHooks: FrpControlLifecycleHooks = FrpControlLifecycleHooks(),
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val transportSequence = AtomicLong(0L)
    private val epochSequence = AtomicLong(0L)
    private val activeSession = AtomicReference<ControlSession?>()
    private val runner = AtomicReference<Job?>()
    private val stateLock = Any()
    private val stopped = CompletableDeferred<Unit>()
    private val parentJob = parentScope.coroutineContext[Job]
    private val ownerJob = SupervisorJob(parentJob)
    private val blockingIoDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(
        config.workWorkerCount + CONTROL_CODEC_PARALLELISM,
        "FrpControlBlockingIo",
    )
    private val blockingIoMarker = ThreadLocal<Boolean>()
    private val blockingIoContext: CoroutineContext =
        blockingIoDispatcher + blockingIoMarker.asContextElement(true)
    private val blockingIoThreadGuard = BlockingIoThreadGuard {
        check(blockingIoMarker.get() == true) {
            "FRP control codecs require the dedicated blocking dispatcher"
        }
    }
    private val workHandlerDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(
        config.maxActiveWorkConnections,
        "FrpWorkHandlers",
    )
    private val workHandlerContext: CoroutineContext =
        workHandlerDispatcher + blockingIoMarker.asContextElement(true)
    private val scope = CoroutineScope(
        parentScope.coroutineContext.minusKey(Job) +
            ownerJob +
            CoroutineName("FrpControlClient"),
    )
    private val mutableState = MutableStateFlow<FrpControlState>(
        FrpControlState.Connecting(generation, attempt = 0),
    )

    val state: StateFlow<FrpControlState> = mutableState.asStateFlow()

    fun start(): Job {
        if (closed.get() || !started.compareAndSet(false, true)) {
            throw FrpControlException(FrpControlFailure.CLOSED)
        }
        val job = scope.launch {
            runClient()
        }
        check(runner.compareAndSet(null, job))
        job.invokeOnCompletion { failure ->
            val session = activeSession.getAndSet(null)
            if (failure is CancellationException || closed.get() || !hasActiveParentLease()) {
                transitionToClosed()
            }
            session?.close()
            stopped.complete(Unit)
        }
        return job
    }

    suspend fun awaitStopped() {
        stopped.await()
    }

    override fun close() {
        if (!transitionToClosed()) return
        val session = activeSession.getAndSet(null)
        session?.close()
        ownerJob.cancel(ControlClientClosedCancellation())
        if (!started.get()) stopped.complete(Unit)
    }

    fun diagnostics(): FrpControlDiagnostics {
        val session = activeSession.get()
        return FrpControlDiagnostics(
            started = started.get(),
            closed = closed.get(),
            runnerActive = runner.get()?.isActive == true,
            identity = session?.identity,
            activeWorkConnections = session?.activeWorkConnectionCount ?: 0,
            workFailureCount = session?.workFailureCount ?: 0L,
        )
    }

    override fun toString(): String {
        val diagnostics = diagnostics()
        return "FrpControlClient(generation=$generation, started=${diagnostics.started}, " +
            "closed=${diagnostics.closed}, runnerActive=${diagnostics.runnerActive}, " +
            "identityPresent=${diagnostics.identity != null}, " +
            "activeWorkConnections=${diagnostics.activeWorkConnections}, " +
            "workFailureCount=${diagnostics.workFailureCount})"
    }

    private suspend fun runClient() {
        var previousRunId = ""
        var hasOpened = false
        var retryAttempt = 0
        try {
            while (hasActiveOwnerLease()) {
                if (!hasOpened) publish(FrpControlState.Connecting(generation, attempt = 1))
                val transportIdentity = transportSequence.incrementAndGet()
                val epoch = epochSequence.get() + 1L
                val established = try {
                    establish(previousRunId, FrpControlIdentity(generation, epoch, transportIdentity))
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Error) {
                    throw failure
                } catch (failure: Exception) {
                    val mapped = mapControlFailure(
                        failure,
                        FrpControlFailure.TRANSPORT_FAILED,
                        login = true,
                    ) as FrpControlException
                    if (!hasOpened) {
                        publish(FrpControlState.Failed(generation, mapped.failure))
                        return
                    }
                    retryAttempt = incrementAttempt(retryAttempt)
                    delayBeforeRetry(retryAttempt, mapped.failure)
                    continue
                }

                previousRunId = established.runId
                epochSequence.set(epoch)
                hasOpened = true
                val session = ControlSession(
                    identity = established.identity,
                    runId = established.runId,
                    mux = established.mux,
                    channel = established.channel,
                    config = config,
                    parentScope = scope,
                    workHandler = workHandler,
                    unixTimeSource = unixTimeSource,
                    monotonicTimeSource = monotonicTimeSource,
                    delayer = delayer,
                    blockingIoContext = blockingIoContext,
                    workHandlerContext = workHandlerContext,
                    blockingIoThreadGuard = blockingIoThreadGuard,
                    lifecycleHooks = lifecycleHooks,
                    isAuthoritative = ::isAuthoritative,
                )
                if (!hasActiveOwnerLease() || !activeSession.compareAndSet(null, session)) {
                    session.close()
                    withContext(NonCancellable) { session.shutdown() }
                    return
                }
                session.start()
                lifecycleHooks.beforeOpenPublish(session.identity)
                if (session.publishOpenIfActive { publish(FrpControlState.Open(session.identity)) }) {
                    retryAttempt = 0
                }

                var awaitFailure: Throwable? = null
                try {
                    session.awaitTermination()
                } catch (failure: CancellationException) {
                    awaitFailure = failure
                } catch (failure: Error) {
                    awaitFailure = failure
                } catch (failure: Exception) {
                    awaitFailure = failure
                } finally {
                    withContext(NonCancellable) { session.shutdown() }
                    activeSession.compareAndSet(session, null)
                }
                val failure = awaitFailure?.let { preferControlFailure(session.finalFailure(), it) }
                    ?: session.finalFailure()
                when (failure) {
                    is CancellationException -> throw failure
                    is Error -> throw failure
                }
                if (!hasActiveOwnerLease()) return
                val mapped = mapControlFailure(
                    failure,
                    FrpControlFailure.TRANSPORT_FAILED,
                ) as FrpControlException
                retryAttempt = incrementAttempt(retryAttempt)
                delayBeforeRetry(retryAttempt, mapped.failure)
            }
        } catch (failure: CancellationException) {
            if (!closed.get()) {
                transitionToClosed()
            }
            throw failure
        } catch (failure: Error) {
            if (!closed.get()) {
                publish(FrpControlState.Failed(generation, FrpControlFailure.TRANSPORT_FAILED))
            }
            throw failure
        } finally {
            activeSession.getAndSet(null)?.let { session ->
                session.close()
                withContext(NonCancellable) { session.shutdown() }
            }
        }
    }

    private suspend fun establish(previousRunId: String, identity: FrpControlIdentity): EstablishedControl {
        var mux: FrpMuxConnection? = null
        var blockingIo: YamuxStreamBlockingIo? = null
        try {
            mux = connector.connect()
            val controlStream = mux.openStream()
            blockingIo = YamuxStreamBlockingIo(controlStream, scope, blockingIoThreadGuard)
            val login = buildLogin(previousRunId)
            val loggedIn = withControlBudgetTimeout(
                timeoutMillis = config.loginTimeoutMillis,
                timeoutFailure = FrpControlFailure.LOGIN_TIMEOUT,
            ) {
                runControlCodec {
                    FrpControlBootstrap(config, secureRandom).exchange(
                        input = blockingIo.input,
                        output = blockingIo.output,
                        login = login,
                        closeOwner = blockingIo,
                    )
                }
            }
            return EstablishedControl(identity, mux, loggedIn.runId, loggedIn.channel)
        } catch (failure: CancellationException) {
            closeEstablishedResources(blockingIo, mux, failure)
        } catch (failure: Error) {
            closeEstablishedResources(blockingIo, mux, failure)
        } catch (failure: Exception) {
            val selected = try {
                currentCoroutineContext().ensureActive()
                mapControlFailure(failure, FrpControlFailure.TRANSPORT_FAILED, login = true)
            } catch (cancellation: CancellationException) {
                cancellation
            }
            closeEstablishedResources(
                blockingIo,
                mux,
                selected,
            )
        }
    }

    private suspend fun <T> runControlCodec(block: () -> T): T =
        runInterruptible(blockingIoContext, block)

    private suspend fun closeEstablishedResources(
        blockingIo: YamuxStreamBlockingIo?,
        mux: FrpMuxConnection?,
        failure: Throwable,
    ): Nothing {
        var selected = failure
        fun selectFatal(candidate: Throwable) {
            selected = preferControlFatalFailure(selected, candidate)
        }
        if (blockingIo != null) {
            try {
                blockingIo.close()
            } catch (closeFailure: CancellationException) {
                selectFatal(closeFailure)
            } catch (closeFailure: Error) {
                selectFatal(closeFailure)
            } catch (_: Exception) {
                // The safe establishment failure remains authoritative.
            }
        }
        if (mux != null) {
            try {
                mux.close()
            } catch (closeFailure: CancellationException) {
                selectFatal(closeFailure)
            } catch (closeFailure: Error) {
                selectFatal(closeFailure)
            } catch (_: Exception) {
                // The safe establishment failure remains authoritative.
            }
        }
        withContext(NonCancellable) {
            if (blockingIo != null) {
                try {
                    blockingIo.awaitReset()
                } catch (closeFailure: CancellationException) {
                    selectFatal(closeFailure)
                } catch (closeFailure: Error) {
                    selectFatal(closeFailure)
                } catch (_: Exception) {
                    // The safe establishment failure remains authoritative.
                }
            }
            if (mux != null) {
                try {
                    mux.awaitTermination()
                } catch (closeFailure: CancellationException) {
                    selectFatal(closeFailure)
                } catch (closeFailure: Error) {
                    selectFatal(closeFailure)
                } catch (_: Exception) {
                    // The safe establishment failure remains authoritative.
                }
            }
        }
        throw selected
    }

    private fun buildLogin(previousRunId: String): Login {
        val timestamp = unixTimeSource.nowSeconds()
        return Login(
            version = config.version,
            hostname = config.hostname,
            os = config.os,
            arch = config.arch,
            user = config.user,
            privilegeKey = FrpTimestampAuth.key(config.token, timestamp),
            timestamp = timestamp,
            runId = previousRunId,
            clientId = config.clientId,
            metas = config.metas,
            clientSpec = ClientSpec(),
            poolCount = 1,
        )
    }

    private suspend fun delayBeforeRetry(attempt: Int, failure: FrpControlFailure) {
        val delayMillis = reconnectDelay(attempt)
        publish(FrpControlState.Retrying(generation, attempt, delayMillis, failure))
        delayer.delayMillis(delayMillis)
    }

    private fun reconnectDelay(attempt: Int): Long {
        var value = config.reconnectInitialDelayMillis
        repeat((attempt - 1).coerceAtLeast(0)) {
            if (value >= config.reconnectMaximumDelayMillis) return config.reconnectMaximumDelayMillis
            value = (value * 2L).coerceAtMost(config.reconnectMaximumDelayMillis)
        }
        return value
    }

    private fun incrementAttempt(current: Int): Int =
        if (current == Int.MAX_VALUE) current else current + 1

    private fun isAuthoritative(identity: FrpControlIdentity): Boolean {
        if (!hasActiveOwnerLease() || identity.generation != generation) return false
        return activeSession.get()?.identity == identity
    }

    private fun hasActiveParentLease(): Boolean = parentJob?.isActive != false && ownerJob.isActive

    private fun hasActiveOwnerLease(): Boolean = hasActiveParentLease() && !closed.get()

    private fun transitionToClosed(): Boolean = synchronized(stateLock) {
        val changed = closed.compareAndSet(false, true)
        if (mutableState.value !is FrpControlState.Closed) {
            mutableState.value = FrpControlState.Closed(generation)
        }
        changed
    }

    private fun publish(value: FrpControlState) {
        if (value !is FrpControlState.Closed) {
            if (!hasActiveOwnerLease()) return
            lifecycleHooks.beforeStatePublish(value)
        }
        synchronized(stateLock) {
            if (mutableState.value is FrpControlState.Closed) return
            if (value is FrpControlState.Closed || hasActiveOwnerLease()) mutableState.value = value
        }
    }

    private data class EstablishedControl(
        val identity: FrpControlIdentity,
        val mux: FrpMuxConnection,
        val runId: String,
        val channel: FrpMessageChannel,
    ) {
        override fun toString(): String =
            "EstablishedControl(identity=$identity, runIdPresent=${runId.isNotEmpty()}, channel=$channel)"
    }

    private companion object {
        const val CONTROL_CODEC_PARALLELISM = 2
    }
}

private class ControlSession(
    val identity: FrpControlIdentity,
    private val runId: String,
    private val mux: FrpMuxConnection,
    private val channel: FrpMessageChannel,
    private val config: FrpControlConfig,
    parentScope: CoroutineScope,
    private val workHandler: FrpWorkConnectionHandler,
    private val unixTimeSource: FrpUnixTimeSource,
    private val monotonicTimeSource: FrpMonotonicTimeSource,
    private val delayer: FrpControlDelayer,
    private val blockingIoContext: CoroutineContext,
    private val workHandlerContext: CoroutineContext,
    private val blockingIoThreadGuard: BlockingIoThreadGuard,
    private val lifecycleHooks: FrpControlLifecycleHooks,
    private val isAuthoritative: (FrpControlIdentity) -> Boolean,
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val terminationSignal = CompletableDeferred<Unit>()
    private val openDecision = CompletableDeferred<Boolean>()
    private val lifecycleLock = Any()
    private var lifecycle = SessionLifecycle.STARTING
    private var selectedFailure: Throwable? = null
    private val writerQueue = Channel<OutgoingMessage>(config.writerQueueCapacity)
    private val workQueue = Channel<Unit>(config.workQueueCapacity)
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(
        parentScope.coroutineContext.minusKey(Job) +
            sessionJob +
            CoroutineName("FrpControlSession"),
    )
    private val jobsLock = Any()
    private val jobs = mutableListOf<Job>()
    private val activeWork = LinkedHashSet<FrpWorkConnection>()
    private val handlerJobs = LinkedHashSet<Job>()
    private val activeWorkPermits = Channel<Unit>(config.maxActiveWorkConnections).apply {
        repeat(config.maxActiveWorkConnections) { check(trySend(Unit).isSuccess) }
    }
    private val workFailures = AtomicLong(0L)
    private val lastPongMillis = AtomicLong(monotonicTimeSource.nowMillis())
    private val firstPingMillis = CompletableDeferred<Long>()

    val activeWorkConnectionCount: Int
        get() = synchronized(lifecycleLock) { activeWork.size }

    val workFailureCount: Long
        get() = workFailures.get()

    fun start() {
        if (!started.compareAndSet(false, true)) throw FrpControlException(FrpControlFailure.CLOSED)
        launchSession { runWriter() }
        repeat(config.workWorkerCount) { workerIndex ->
            launchSession(CoroutineName("FrpControlWorkWorker-$workerIndex")) { runWorkWorker() }
        }
        launchSession { runReader() }
        launchSession { runMuxMonitor() }
        if (config.heartbeatIntervalMillis > 0L) {
            launchSession { runHeartbeatSender() }
            if (config.heartbeatTimeoutMillis > 0L) launchSession { runHeartbeatMonitor() }
        }
    }

    suspend fun awaitTermination() {
        terminationSignal.await()
    }

    fun publishOpenIfActive(publish: () -> Unit): Boolean = synchronized(lifecycleLock) {
        if (lifecycle != SessionLifecycle.STARTING || closing.get() || !isAuthoritative(identity)) {
            openDecision.complete(false)
            return@synchronized false
        }
        lifecycle = SessionLifecycle.OPEN
        publish()
        openDecision.complete(true)
        true
    }

    fun finalFailure(): Throwable = synchronized(lifecycleLock) {
        selectedFailure ?: FrpControlException(FrpControlFailure.CLOSED)
    }

    override fun close() {
        recordFailure(FrpControlException(FrpControlFailure.CLOSED))
    }

    suspend fun shutdown() {
        beginPhysicalClose()
        val activeHandlers = synchronized(lifecycleLock) { handlerJobs.toList() }
        sessionJob.cancel(ControlSessionClosedCancellation())
        val childJobs = synchronized(jobsLock) { jobs.toList() }
        (childJobs + activeHandlers).distinct().joinAll()
        closeActiveWorkConnections()
        try {
            mux.awaitTermination()
        } catch (failure: CancellationException) {
            recordFailure(failure)
        } catch (failure: Error) {
            recordFailure(failure)
        } catch (_: Exception) {
            // The selected control failure remains authoritative for ordinary mux close I/O.
        }
        try {
            runControlCodec { channel.close() }
        } catch (failure: CancellationException) {
            recordFailure(failure)
        } catch (failure: Error) {
            recordFailure(failure)
        } catch (_: Exception) {
            // The selected control failure remains authoritative.
        }
    }

    override fun toString(): String =
        "ControlSession(identity=$identity, runIdPresent=${runId.isNotEmpty()}, " +
            "started=${started.get()}, closing=${closing.get()}, " +
            "activeWorkConnections=$activeWorkConnectionCount, workFailureCount=${workFailures.get()})"

    private suspend fun <T> runControlCodec(block: () -> T): T =
        runInterruptible(blockingIoContext, block)

    private fun launchSession(
        context: CoroutineName = CoroutineName("FrpControlSessionWorker"),
        block: suspend () -> Unit,
    ) {
        val job = scope.launch(context) {
            try {
                block()
            } catch (failure: CancellationException) {
                if (!closing.get()) recordFailure(failure)
            } catch (failure: Error) {
                recordFailure(failure)
            } catch (failure: Exception) {
                recordFailure(mapControlFailure(failure, FrpControlFailure.TRANSPORT_FAILED))
            }
        }
        synchronized(jobsLock) { jobs += job }
    }

    private suspend fun runReader() {
        while (!closing.get()) {
            val message = try {
                runControlCodec { channel.readMessage() }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Error) {
                throw failure
            } catch (failure: Exception) {
                throw mapControlFailure(failure, FrpControlFailure.READ_FAILED)
            } ?: throw FrpControlException(FrpControlFailure.READ_FAILED)
            when (message) {
                is Pong -> {
                    if (message.error.isNotEmpty()) {
                        throw FrpControlException(FrpControlFailure.PROTOCOL_FAILED)
                    }
                    lastPongMillis.set(monotonicTimeSource.nowMillis())
                }
                is ReqWorkConn -> {
                    if (!workQueue.trySend(Unit).isSuccess) {
                        throw FrpControlException(FrpControlFailure.QUEUE_OVERFLOW)
                    }
                }
                else -> throw FrpControlException(FrpControlFailure.PROTOCOL_FAILED)
            }
        }
    }

    private suspend fun runWriter() {
        try {
            for (outgoing in writerQueue) {
                try {
                    runControlCodec { channel.writeMessage(outgoing.message) }
                    outgoing.result.complete(Unit)
                } catch (failure: CancellationException) {
                    outgoing.result.completeExceptionally(failure)
                    throw failure
                } catch (failure: Error) {
                    outgoing.result.completeExceptionally(failure)
                    throw failure
                } catch (failure: Exception) {
                    val mapped = mapControlFailure(failure, FrpControlFailure.WRITE_FAILED)
                    outgoing.result.completeExceptionally(mapped)
                    throw mapped
                }
            }
        } finally {
            val failure = finalFailure()
            while (true) {
                val pending = writerQueue.tryReceive().getOrNull() ?: break
                pending.result.completeExceptionally(failure)
            }
        }
    }

    private suspend fun send(message: FrpMessage) {
        if (closing.get()) throw finalFailure()
        val outgoing = OutgoingMessage(message)
        writerQueue.send(outgoing)
        outgoing.result.await()
    }

    private suspend fun runHeartbeatSender() {
        while (!closing.get()) {
            val timestamp = unixTimeSource.nowSeconds()
            send(
                Ping(
                    privilegeKey = if (config.authenticateHeartbeats) {
                        FrpTimestampAuth.key(config.token, timestamp)
                    } else {
                        ""
                    },
                    timestamp = if (config.authenticateHeartbeats) timestamp else 0L,
                ),
            )
            firstPingMillis.complete(monotonicTimeSource.nowMillis())
            delayer.delayMillis(config.heartbeatIntervalMillis)
        }
    }

    private suspend fun runHeartbeatMonitor() {
        val checkInterval = minOf(1_000L, config.heartbeatIntervalMillis).coerceAtLeast(1L)
        val firstPing = firstPingMillis.await()
        delayer.delayMillis(config.heartbeatTimeoutMillis)
        while (!closing.get()) {
            val lastResponse = maxOf(firstPing, lastPongMillis.get())
            if (monotonicTimeSource.nowMillis() - lastResponse > config.heartbeatTimeoutMillis) {
                throw FrpControlException(FrpControlFailure.HEARTBEAT_TIMEOUT)
            }
            delayer.delayMillis(checkInterval)
        }
    }

    private suspend fun runMuxMonitor() {
        try {
            mux.awaitTermination()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Error) {
            throw failure
        } catch (failure: Exception) {
            throw mapControlFailure(failure, FrpControlFailure.TRANSPORT_FAILED)
        }
        if (!closing.get()) throw FrpControlException(FrpControlFailure.TRANSPORT_FAILED)
    }

    private suspend fun runWorkWorker() {
        for (ignored in workQueue) {
            if (closing.get()) return
            activeWorkPermits.receive()
            if (closing.get()) {
                releaseWorkPermit()
                return
            }
            processWorkRequest()
        }
    }

    private suspend fun processWorkRequest() {
        var permitOwned = true
        var blockingIo: YamuxStreamBlockingIo? = null
        var workConnection: FrpWorkConnection? = null
        var activeLease: ActiveWorkLease? = null
        try {
            if (!isAuthoritative(identity)) return
            val stream = mux.openStream()
            if (!isAuthoritative(identity)) {
                stream.reset()
                return
            }
            val workIo = YamuxStreamBlockingIo(stream, scope, blockingIoThreadGuard)
            blockingIo = workIo
            val startMessage = withControlBudgetTimeout(
                timeoutMillis = config.workHandshakeTimeoutMillis,
                timeoutFailure = FrpControlFailure.WORK_FAILED,
            ) {
                runControlCodec {
                    if (config.wireProtocol == FrpWireProtocol.V2) {
                        FrpWireV2.writeMagic(workIo.output)
                    }
                    val timestamp = unixTimeSource.nowSeconds()
                    writeWorkMessage(
                        workIo.output,
                        NewWorkConn(
                            runId = runId,
                            privilegeKey = if (config.authenticateNewWorkConnections) {
                                FrpTimestampAuth.key(config.token, timestamp)
                            } else {
                                ""
                            },
                            timestamp = if (config.authenticateNewWorkConnections) timestamp else 0L,
                        ),
                    )
                    val response = readWorkMessage(workIo.input) as? StartWorkConn
                        ?: throw FrpControlException(FrpControlFailure.WORK_FAILED)
                    if (response.error.isNotEmpty()) {
                        throw FrpControlException(FrpControlFailure.WORK_FAILED)
                    }
                    response
                }
            }
            workConnection = FrpWorkConnection(
                identity = identity,
                start = startMessage.withFrpUserPrefixRemoved(config.user),
                stream = stream,
                blockingIo = workIo,
            )
            if (!openDecision.await() || !isAuthoritative(identity)) return
            lifecycleHooks.beforeWorkDelivery(identity)
            val lease = ActiveWorkLease(workConnection)
            activeLease = lease
            workConnection = null
            blockingIo = null
            permitOwned = false
            val handlerJob = scope.launch(
                context = CoroutineName("FrpControlWorkHandler"),
                start = CoroutineStart.LAZY,
            ) {
                runWorkHandler(lease)
            }
            lease.attach(handlerJob)
            handlerJob.invokeOnCompletion { lease.release() }
            if (!registerWorkHandler(lease)) {
                handlerJob.cancel()
                return
            }
            lifecycleHooks.afterWorkRegistration(identity)
            if (!handlerJob.start()) return
            activeLease = null
        } catch (failure: CancellationException) {
            if (hasLiveSessionOwner()) throw failure
        } catch (failure: Error) {
            recordFailure(failure)
        } catch (_: Exception) {
            workFailures.incrementAndGet()
        } finally {
            activeLease?.release()
            workConnection?.close() ?: blockingIo?.close()
            if (permitOwned) releaseWorkPermit()
        }
    }

    private fun registerWorkHandler(lease: ActiveWorkLease): Boolean = synchronized(lifecycleLock) {
        if (lifecycle != SessionLifecycle.OPEN || !isAuthoritative(identity) || lease.isReleased) {
            false
        } else {
            activeWork += lease.connection
            handlerJobs += lease.job
            true
        }
    }

    private fun hasLiveSessionOwner(): Boolean = synchronized(lifecycleLock) {
        lifecycle != SessionLifecycle.TERMINATED && !closing.get() && isAuthoritative(identity)
    }

    private fun hasOpenHandlerOwner(): Boolean = synchronized(lifecycleLock) {
        lifecycle == SessionLifecycle.OPEN && !closing.get() && isAuthoritative(identity)
    }

    private suspend fun runWorkHandler(lease: ActiveWorkLease) {
        val deliver = synchronized(lifecycleLock) {
            lifecycle == SessionLifecycle.OPEN &&
                activeWork.contains(lease.connection) &&
                handlerJobs.contains(lease.job) &&
                isAuthoritative(identity)
        }
        if (!deliver) return
        try {
            withContext(workHandlerContext) { workHandler.handle(lease.connection) }
        } catch (failure: CancellationException) {
            if (hasOpenHandlerOwner()) workFailures.incrementAndGet()
        } catch (failure: Error) {
            recordFailure(failure)
        } catch (_: Exception) {
            workFailures.incrementAndGet()
        }
    }

    private fun releaseWorkPermit() {
        check(activeWorkPermits.trySend(Unit).isSuccess)
    }

    private fun readWorkMessage(input: InputStream): FrpMessage? = when (config.wireProtocol) {
        FrpWireProtocol.V1 -> FrpWireV1.readMessage(input)
        FrpWireProtocol.V2 -> FrpWireV2.readMessage(input)
    }

    private fun writeWorkMessage(output: OutputStream, message: FrpMessage) {
        when (config.wireProtocol) {
            FrpWireProtocol.V1 -> FrpWireV1.writeMessage(output, message)
            FrpWireProtocol.V2 -> FrpWireV2.writeMessage(output, message)
        }
        output.flush()
    }

    private fun recordFailure(failure: Throwable) {
        val firstTermination = synchronized(lifecycleLock) {
            selectedFailure = preferControlFailure(selectedFailure, failure)
            if (lifecycle == SessionLifecycle.TERMINATED) {
                false
            } else {
                lifecycle = SessionLifecycle.TERMINATED
                openDecision.complete(false)
                terminationSignal.complete(Unit)
                true
            }
        }
        if (firstTermination) beginPhysicalClose()
    }

    private fun beginPhysicalClose() {
        if (!closing.compareAndSet(false, true)) return
        writerQueue.close()
        workQueue.close()
        closeActiveWorkConnections()
        try {
            mux.close()
        } catch (failure: CancellationException) {
            updateSelectedFailure(failure)
        } catch (failure: Error) {
            updateSelectedFailure(failure)
        } catch (_: Exception) {
            // Expected close I/O is secondary to the selected control failure.
        }
    }

    private fun closeActiveWorkConnections() {
        val connections = synchronized(lifecycleLock) {
            activeWork.toList().also { activeWork.clear() }
        }
        connections.forEach { connection ->
            try {
                connection.close()
            } catch (failure: CancellationException) {
                updateSelectedFailure(failure)
            } catch (failure: Error) {
                updateSelectedFailure(failure)
            } catch (_: Exception) {
                // Work connection close is best effort after session termination.
            }
        }
    }

    private fun updateSelectedFailure(failure: Throwable) {
        synchronized(lifecycleLock) {
            selectedFailure = preferControlFailure(selectedFailure, failure)
        }
    }

    private inner class ActiveWorkLease(
        val connection: FrpWorkConnection,
    ) {
        private val released = AtomicBoolean(false)
        private var handlerJob: Job? = null

        val job: Job
            get() = checkNotNull(handlerJob)

        val isReleased: Boolean
            get() = released.get()

        fun attach(job: Job) {
            check(handlerJob == null)
            handlerJob = job
        }

        fun release() {
            if (!released.compareAndSet(false, true)) return
            val job = handlerJob
            job?.cancel()
            synchronized(lifecycleLock) {
                activeWork.remove(connection)
                if (job != null) handlerJobs.remove(job)
            }
            try {
                connection.close()
            } catch (failure: CancellationException) {
                updateSelectedFailure(failure)
            } catch (failure: Error) {
                updateSelectedFailure(failure)
            } catch (_: Exception) {
                // Work close is best effort after ownership has left the handler.
            }
            releaseWorkPermit()
        }

        override fun toString(): String =
            "ActiveWorkLease(released=${released.get()}, jobAttached=${handlerJob != null})"
    }

    private class OutgoingMessage(
        val message: FrpMessage,
        val result: CompletableDeferred<Unit> = CompletableDeferred(),
    ) {
        override fun toString(): String =
            "OutgoingMessage(type=${message::class.java.simpleName}, completed=${result.isCompleted})"
    }

    private enum class SessionLifecycle {
        STARTING,
        OPEN,
        TERMINATED,
    }
}

internal fun StartWorkConn.withFrpUserPrefixRemoved(user: String): StartWorkConn {
    if (user.isEmpty()) return this
    val prefix = "$user."
    return if (proxyName.startsWith(prefix)) copy(proxyName = proxyName.removePrefix(prefix)) else this
}

private suspend fun <T> withControlBudgetTimeout(
    timeoutMillis: Long,
    timeoutFailure: FrpControlFailure,
    block: suspend () -> T,
): T {
    val callerContext = currentCoroutineContext()
    val budgetCancellation = AtomicReference<ControlBudgetCancellation?>()
    return try {
        coroutineScope {
            val budgetOwner = checkNotNull(currentCoroutineContext()[Job])
            val timer = launch {
                delay(timeoutMillis)
                val cancellation = ControlBudgetCancellation()
                budgetCancellation.compareAndSet(null, cancellation)
                budgetOwner.cancel(cancellation)
            }
            try {
                block()
            } finally {
                timer.cancel()
            }
        }
    } catch (failure: CancellationException) {
        if (!callerContext.isActive) callerContext.ensureActive()
        if (budgetCancellation.get() != null) throw FrpControlException(timeoutFailure)
        throw failure
    } catch (failure: Error) {
        throw failure
    } catch (failure: Exception) {
        if (!callerContext.isActive) callerContext.ensureActive()
        if (budgetCancellation.get() != null) throw FrpControlException(timeoutFailure)
        throw failure
    }
}

private fun mapControlFailure(
    failure: Throwable,
    fallback: FrpControlFailure,
    login: Boolean = false,
): Throwable = when (failure) {
    is Error -> failure
    is CancellationException -> failure
    is FrpControlException -> failure
    is FrpTransportException -> FrpControlException(FrpControlFailure.TRANSPORT_FAILED)
    is FrpProtocolException -> FrpControlException(
        if (login) FrpControlFailure.LOGIN_PROTOCOL_FAILED else FrpControlFailure.PROTOCOL_FAILED,
    )
    is FrpCryptoException -> FrpControlException(FrpControlFailure.CRYPTO_FAILED)
    else -> FrpControlException(fallback)
}

private fun preferControlFailure(current: Throwable?, candidate: Throwable): Throwable {
    if (current == null || current === candidate) return candidate
    val selected = when {
        candidate is Error && current !is Error -> candidate
        candidate is CancellationException && current !is Error && current !is CancellationException -> candidate
        else -> current
    }
    val secondary = if (selected === current) candidate else current
    if (secondary is Error || secondary is CancellationException) {
        if (selected !== secondary) selected.addSuppressed(secondary)
    } else {
        selected.addSuppressed(FrpControlException(FrpControlFailure.CLOSED))
    }
    return selected
}

private fun preferControlFatalFailure(current: Throwable, candidate: Throwable): Throwable {
    if (current === candidate) return current
    val selected = when {
        candidate is Error && current !is Error -> candidate
        current is Error -> current
        candidate is CancellationException && current !is CancellationException -> candidate
        else -> current
    }
    val secondary = if (selected === current) candidate else current
    if (selected !== secondary) selected.addSuppressed(secondary)
    return selected
}

private class ControlClientClosedCancellation : CancellationException("frp control client closed")

private class ControlSessionClosedCancellation : CancellationException("frp control session closed")

private class ControlBudgetCancellation : CancellationException("frp control budget expired")
