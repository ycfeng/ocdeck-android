package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.ProjectEventReduction
import io.github.ycfeng.ocdeck.core.store.ProjectKey
import io.github.ycfeng.ocdeck.core.store.SseConnectionStatus
import io.github.ycfeng.ocdeck.core.store.SseFailureReason
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity
import io.github.ycfeng.ocdeck.domain.model.OpenCodeMessagePage
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class ProjectConnectionLease internal constructor(
    private val releaseAction: () -> Unit,
) {
    private val released = AtomicBoolean()

    fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

class GlobalConnectionLease internal constructor(
    private val releaseAction: () -> Unit,
) {
    private val released = AtomicBoolean()

    fun release() {
        if (released.compareAndSet(false, true)) releaseAction()
    }
}

class OpenCodeEventClient internal constructor(
    private val connectionProvider: SseConnectionProvider,
    private val store: InMemoryOpenCodeStore,
    private val pathNormalizer: PathNormalizer,
    private val json: Json,
    private val redactor: Redactor,
    private val snapshotCoordinator: ProjectSnapshotCoordinator,
    private val notifyProjectEvent: suspend (ProjectEventReduction) -> Unit = {},
    private val eventSourceFactory: SseEventSourceFactory = OkHttpSseEventSourceFactory(redactor),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val retryDelayMillis: (attempt: Int) -> Long = ::defaultRetryDelay,
    private val maxBufferedEvents: Int = 256,
    private val maxBufferedBytes: Int = 1_048_576,
) : ForegroundConnectionController {
    private val lock = Any()
    private val ownerIds = AtomicLong()
    private val sourceIds = AtomicLong()
    private val attemptIds = AtomicLong()
    private val retryIds = AtomicLong()
    private val projectSlots = mutableMapOf<ProjectKey, ProjectSlot>()
    private val globalSlots = mutableMapOf<String, GlobalSlot>()
    private val serverTransportIdentities = mutableMapOf<String, ServerTransportIdentity>()

    init {
        require(maxBufferedEvents > 0) { "maxBufferedEvents must be greater than 0" }
        require(maxBufferedBytes > 0) { "maxBufferedBytes must be greater than 0" }
    }

    fun connectProject(
        serverId: String,
        directory: String,
        workspace: String? = null,
    ): ProjectConnectionLease {
        val key = projectKey(serverId, directory, workspace)
        val ownerId = ownerIds.incrementAndGet()
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = projectSlots.getOrPut(key) { ProjectSlot(key) }
            slot.owners += ownerId
            if (!slot.desiredOpen || !slot.hasLifecycleWork()) {
                restartProjectLocked(slot, knownTransport = slot.transportIdentity, actions = actions)
            }
        }
        execute(actions)
        return ProjectConnectionLease { releaseProjectOwner(key, ownerId) }
    }

    fun connectGlobal(serverId: String): GlobalConnectionLease {
        val ownerId = ownerIds.incrementAndGet()
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = globalSlots.getOrPut(serverId) { GlobalSlot(serverId) }
            slot.owners += ownerId
            if (!slot.desiredOpen || !slot.hasLifecycleWork()) {
                restartGlobalLocked(slot, knownTransport = slot.transportIdentity, actions = actions)
            }
        }
        execute(actions)
        return GlobalConnectionLease { releaseGlobalOwner(serverId, ownerId) }
    }

    fun reconnectProject(serverId: String, directory: String, workspace: String? = null) {
        val key = projectKey(serverId, directory, workspace)
        val actions = LifecycleActions()
        synchronized(lock) {
            projectSlots[key]
                ?.takeIf(ProjectSlot::desiredOpen)
                ?.let { slot -> restartProjectLocked(slot, slot.transportIdentity, actions) }
        }
        execute(actions)
    }

    fun reconnectGlobal(serverId: String) {
        val actions = LifecycleActions()
        synchronized(lock) {
            globalSlots[serverId]
                ?.takeIf(GlobalSlot::desiredOpen)
                ?.let { slot -> restartGlobalLocked(slot, slot.transportIdentity, actions) }
        }
        execute(actions)
    }

    fun closeProjectNow(serverId: String, directory: String, workspace: String? = null) {
        val key = projectKey(serverId, directory, workspace)
        val actions = LifecycleActions()
        synchronized(lock) {
            projectSlots[key]?.let { slot -> closeProjectLocked(slot, clearOwners = true, actions = actions) }
        }
        execute(actions)
    }

    fun closeGlobalNow(serverId: String) {
        val actions = LifecycleActions()
        synchronized(lock) {
            globalSlots[serverId]?.let { slot -> closeGlobalLocked(slot, clearOwners = true, actions = actions) }
        }
        execute(actions)
    }

    fun closeServer(serverId: String) {
        val actions = LifecycleActions()
        synchronized(lock) {
            globalSlots[serverId]?.let { slot -> closeGlobalLocked(slot, clearOwners = true, actions = actions) }
            projectSlots.values
                .filter { it.key.serverId == serverId }
                .forEach { slot -> closeProjectLocked(slot, clearOwners = true, actions = actions) }
            serverTransportIdentities.remove(serverId)
        }
        execute(actions)
    }

    override fun desiredServerIds(): Set<String> = synchronized(lock) {
        buildSet {
            globalSlots.values.filter(GlobalSlot::desiredOpen).forEach { add(it.serverId) }
            projectSlots.values.filter(ProjectSlot::desiredOpen).forEach { add(it.key.serverId) }
        }
    }

    override fun onForegroundConnectionReady(serverId: String, transportIdentity: ServerTransportIdentity) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val currentServerTransport = serverTransportIdentities[serverId]
            if (currentServerTransport != null && currentServerTransport != transportIdentity) {
                restartServerForTransportLocked(serverId, transportIdentity, actions)
                return@synchronized
            }
            serverTransportIdentities[serverId] = transportIdentity
            globalSlots[serverId]
                ?.takeIf(GlobalSlot::desiredOpen)
                ?.let { slot ->
                    val source = slot.source
                    if (source == null || !source.opened || source.lease.transportIdentity != transportIdentity) {
                        restartGlobalLocked(slot, transportIdentity, actions)
                    }
                }
            projectSlots.values
                .filter { it.key.serverId == serverId && it.desiredOpen }
                .forEach { slot ->
                    val source = slot.source
                    if (source == null || !source.opened || source.lease.transportIdentity != transportIdentity) {
                        restartProjectLocked(slot, transportIdentity, actions)
                    } else {
                        actions.calibrations += source.lease.toCalibrationRequest(CalibrationReason.Foreground)
                    }
                }
        }
        execute(actions)
    }

    fun refreshProviderCapabilities(serverId: String) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val globalSource = globalSlots[serverId]
                ?.takeIf(GlobalSlot::desiredOpen)
                ?.source
                ?.takeIf(GlobalSourceState::opened)
            projectSlots.values
                .filter { slot -> slot.key.serverId == serverId && slot.desiredOpen }
                .forEach { slot ->
                    if (slot.calibration != null) {
                        slot.calibration?.dirty = true
                        return@forEach
                    }
                    val projectSource = slot.source?.takeIf(ProjectSourceState::opened)
                    when {
                        projectSource != null -> {
                            actions.calibrations += projectSource.lease.toCalibrationRequest(
                                CalibrationReason.ProviderMutation,
                            )
                        }
                        globalSource != null -> {
                            actions.calibrations += globalSource.lease.toCalibrationRequest(
                                slot.key,
                                CalibrationReason.ProviderMutation,
                            )
                        }
                    }
                }
        }
        execute(actions)
    }

    internal fun projectLifecycleState(
        serverId: String,
        directory: String,
        workspace: String? = null,
    ): ConnectionLifecycleState? {
        val key = projectKey(serverId, directory, workspace)
        return synchronized(lock) {
            projectSlots[key]?.let { slot ->
                ConnectionLifecycleState(
                    desiredOpen = slot.desiredOpen,
                    ownerCount = slot.owners.size,
                    generation = slot.generation,
                    recoveryId = slot.source?.lease?.recoveryId,
                    sourceId = slot.source?.lease?.sourceId,
                    transportIdentity = slot.transportIdentity,
                )
            }
        }
    }

    internal fun globalLifecycleState(serverId: String): ConnectionLifecycleState? = synchronized(lock) {
        globalSlots[serverId]?.let { slot ->
            ConnectionLifecycleState(
                desiredOpen = slot.desiredOpen,
                ownerCount = slot.owners.size,
                generation = slot.generation,
                recoveryId = slot.source?.lease?.recoveryId,
                sourceId = slot.source?.lease?.sourceId,
                transportIdentity = slot.transportIdentity,
            )
        }
    }

    private fun releaseProjectOwner(key: ProjectKey, ownerId: Long) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = projectSlots[key] ?: return@synchronized
            if (!slot.owners.remove(ownerId)) return@synchronized
            if (slot.owners.isEmpty()) closeProjectLocked(slot, clearOwners = false, actions = actions)
        }
        execute(actions)
    }

    private fun releaseGlobalOwner(serverId: String, ownerId: Long) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = globalSlots[serverId] ?: return@synchronized
            if (!slot.owners.remove(ownerId)) return@synchronized
            if (slot.owners.isEmpty()) closeGlobalLocked(slot, clearOwners = false, actions = actions)
        }
        execute(actions)
    }

    private fun restartProjectLocked(
        slot: ProjectSlot,
        knownTransport: ServerTransportIdentity?,
        actions: LifecycleActions,
    ) {
        slot.desiredOpen = true
        slot.generation += 1
        slot.clearHandoffEvents()
        detachProjectLifecycleLocked(slot, replayBufferedEvents = true, actions = actions)
        slot.transportIdentity = knownTransport
        setProjectStatusLocked(slot, SseConnectionStatus.Connecting)
        createProjectOpenJobLocked(slot, attempt = 0, actions = actions)
    }

    private fun restartGlobalLocked(
        slot: GlobalSlot,
        knownTransport: ServerTransportIdentity?,
        actions: LifecycleActions,
    ) {
        slot.desiredOpen = true
        slot.generation += 1
        clearServerHandoffEventsLocked(slot.serverId)
        detachGlobalLifecycleLocked(slot, actions)
        slot.transportIdentity = knownTransport
        setGlobalStatusLocked(slot, SseConnectionStatus.Connecting)
        createGlobalOpenJobLocked(slot, attempt = 0, actions = actions)
    }

    private fun restartServerForTransportLocked(
        serverId: String,
        transportIdentity: ServerTransportIdentity,
        actions: LifecycleActions,
    ): Boolean {
        val current = serverTransportIdentities[serverId]
        if (current != null && transportIdentity.isOlderThan(current)) return false
        serverTransportIdentities[serverId] = transportIdentity
        globalSlots[serverId]
            ?.takeIf(GlobalSlot::desiredOpen)
            ?.let { slot -> restartGlobalLocked(slot, transportIdentity, actions) }
        projectSlots.values
            .filter { it.key.serverId == serverId && it.desiredOpen }
            .forEach { slot -> restartProjectLocked(slot, transportIdentity, actions) }
        return true
    }

    private fun closeProjectLocked(slot: ProjectSlot, clearOwners: Boolean, actions: LifecycleActions) {
        if (clearOwners) slot.owners.clear()
        slot.clearHandoffEvents()
        if (!slot.desiredOpen && !slot.hasLifecycleWork()) {
            setProjectStatusLocked(slot, SseConnectionStatus.Closed)
            return
        }
        slot.desiredOpen = false
        slot.generation += 1
        detachProjectLifecycleLocked(slot, replayBufferedEvents = true, actions = actions)
        slot.transportIdentity = null
        setProjectStatusLocked(slot, SseConnectionStatus.Closed)
    }

    private fun closeGlobalLocked(slot: GlobalSlot, clearOwners: Boolean, actions: LifecycleActions) {
        if (clearOwners) slot.owners.clear()
        clearServerHandoffEventsLocked(slot.serverId)
        if (!slot.desiredOpen && !slot.hasLifecycleWork()) {
            setGlobalStatusLocked(slot, SseConnectionStatus.Closed)
            return
        }
        slot.desiredOpen = false
        slot.generation += 1
        detachGlobalLifecycleLocked(slot, actions)
        slot.transportIdentity = null
        setGlobalStatusLocked(slot, SseConnectionStatus.Closed)
    }

    private fun detachProjectLifecycleLocked(
        slot: ProjectSlot,
        replayBufferedEvents: Boolean,
        actions: LifecycleActions,
    ) {
        slot.openJob?.let(actions.jobsToCancel::add)
        slot.openJob = null
        slot.retryJob?.let(actions.jobsToCancel::add)
        slot.retryJob = null
        slot.source?.handle?.let(actions.sourcesToCancel::add)
        slot.source = null
        detachCalibrationLocked(
            slot = slot,
            replayBufferedEvents = replayBufferedEvents,
            actions = actions,
            source = ProjectSnapshotSource.Project,
        )
    }

    private fun detachGlobalLifecycleLocked(slot: GlobalSlot, actions: LifecycleActions) {
        val sourceLease = slot.source?.lease
        slot.openJob?.let(actions.jobsToCancel::add)
        slot.openJob = null
        slot.retryJob?.let(actions.jobsToCancel::add)
        slot.retryJob = null
        slot.source?.handle?.let(actions.sourcesToCancel::add)
        slot.source = null
        sourceLease?.let { detachGlobalCalibrationsLocked(it, actions) }
    }

    private fun createProjectOpenJobLocked(slot: ProjectSlot, attempt: Int, actions: LifecycleActions) {
        val token = ProjectOpenToken(
            key = slot.key,
            generation = slot.generation,
            attemptId = attemptIds.incrementAndGet(),
            attempt = attempt,
        )
        val job = scope.launch(start = CoroutineStart.LAZY) { runProjectOpen(token) }
        slot.openAttemptId = token.attemptId
        slot.openJob = job
        actions.jobsToStart += job
    }

    private fun createGlobalOpenJobLocked(slot: GlobalSlot, attempt: Int, actions: LifecycleActions) {
        val token = GlobalOpenToken(
            serverId = slot.serverId,
            generation = slot.generation,
            attemptId = attemptIds.incrementAndGet(),
            attempt = attempt,
        )
        val job = scope.launch(start = CoroutineStart.LAZY) { runGlobalOpen(token) }
        slot.openAttemptId = token.attemptId
        slot.openJob = job
        actions.jobsToStart += job
    }

    private suspend fun runProjectOpen(token: ProjectOpenToken) {
        try {
            val connection = connectionProvider.getSseConnection(token.key.serverId)
            val prepared = prepareProjectSource(token, connection)
            execute(prepared.actions)
            val lease = prepared.lease ?: return
            val request = SseRequest(
                connection.effectiveBaseUrl.eventUrl(
                    path = "event",
                    directory = token.key.normalizedDirectory,
                    workspace = token.key.normalizedWorkspace,
                ),
            )
            val handle = eventSourceFactory.open(
                connection = connection,
                request = request,
                listener = projectListener(lease),
            )
            registerProjectSourceHandle(lease, handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            handleProjectSetupFailure(token, exception)
        }
    }

    private suspend fun runGlobalOpen(token: GlobalOpenToken) {
        try {
            val connection = connectionProvider.getSseConnection(token.serverId)
            val prepared = prepareGlobalSource(token, connection)
            execute(prepared.actions)
            val lease = prepared.lease ?: return
            val request = SseRequest(connection.effectiveBaseUrl.trimEnd('/') + "/global/event")
            val handle = eventSourceFactory.open(
                connection = connection,
                request = request,
                listener = globalListener(lease),
            )
            registerGlobalSourceHandle(lease, handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            handleGlobalSetupFailure(token, exception)
        }
    }

    private fun prepareProjectSource(token: ProjectOpenToken, connection: SseConnection): PreparedProjectSource {
        val actions = LifecycleActions()
        val lease = synchronized(lock) {
            val slot = projectSlots[token.key]
                ?.takeIf { it.desiredOpen && it.generation == token.generation && it.openAttemptId == token.attemptId }
                ?: return@synchronized null
            val serverTransport = serverTransportIdentities[token.key.serverId]
            if (serverTransport != null && serverTransport != connection.transportIdentity) {
                if (connection.transportIdentity.isOlderThan(serverTransport)) {
                    rejectOlderProjectOpenLocked(slot, token, actions)
                    return@synchronized null
                }
                restartServerForTransportLocked(token.key.serverId, connection.transportIdentity, actions)
                return@synchronized null
            }
            serverTransportIdentities[token.key.serverId] = connection.transportIdentity
            val currentTransport = slot.transportIdentity
            if (currentTransport != null && currentTransport != connection.transportIdentity) {
                if (connection.transportIdentity.isOlderThan(currentTransport)) {
                    rejectOlderProjectOpenLocked(slot, token, actions)
                    return@synchronized null
                }
                restartProjectLocked(slot, connection.transportIdentity, actions)
                return@synchronized null
            }
            slot.transportIdentity = connection.transportIdentity
            val sourceLease = ProjectSourceLease(
                key = token.key,
                generation = token.generation,
                recoveryId = ++slot.recoverySequence,
                sourceId = sourceIds.incrementAndGet(),
                transportIdentity = connection.transportIdentity,
                attemptId = token.attemptId,
                attempt = token.attempt,
            )
            slot.source = ProjectSourceState(sourceLease)
            sourceLease
        }
        return PreparedProjectSource(lease, actions)
    }

    private fun prepareGlobalSource(token: GlobalOpenToken, connection: SseConnection): PreparedGlobalSource {
        val actions = LifecycleActions()
        val lease = synchronized(lock) {
            val slot = globalSlots[token.serverId]
                ?.takeIf { it.desiredOpen && it.generation == token.generation && it.openAttemptId == token.attemptId }
                ?: return@synchronized null
            val serverTransport = serverTransportIdentities[token.serverId]
            if (serverTransport != null && serverTransport != connection.transportIdentity) {
                if (connection.transportIdentity.isOlderThan(serverTransport)) {
                    rejectOlderGlobalOpenLocked(slot, token, actions)
                    return@synchronized null
                }
                restartServerForTransportLocked(token.serverId, connection.transportIdentity, actions)
                return@synchronized null
            }
            serverTransportIdentities[token.serverId] = connection.transportIdentity
            val currentTransport = slot.transportIdentity
            if (currentTransport != null && currentTransport != connection.transportIdentity) {
                if (connection.transportIdentity.isOlderThan(currentTransport)) {
                    rejectOlderGlobalOpenLocked(slot, token, actions)
                    return@synchronized null
                }
                restartGlobalLocked(slot, connection.transportIdentity, actions)
                return@synchronized null
            }
            slot.transportIdentity = connection.transportIdentity
            val sourceLease = GlobalSourceLease(
                serverId = token.serverId,
                generation = token.generation,
                recoveryId = ++slot.recoverySequence,
                sourceId = sourceIds.incrementAndGet(),
                transportIdentity = connection.transportIdentity,
                attemptId = token.attemptId,
                attempt = token.attempt,
            )
            slot.source = GlobalSourceState(sourceLease)
            sourceLease
        }
        return PreparedGlobalSource(lease, actions)
    }

    private fun rejectOlderProjectOpenLocked(
        slot: ProjectSlot,
        token: ProjectOpenToken,
        actions: LifecycleActions,
    ) {
        val source = slot.source?.takeIf { it.lease.attemptId == token.attemptId }
        source?.handle?.let(actions.sourcesToCancel::add)
        if (source != null) slot.source = null
        if (slot.openAttemptId == token.attemptId) slot.openJob = null
        detachCalibrationLocked(
            slot = slot,
            replayBufferedEvents = true,
            actions = actions,
            source = ProjectSnapshotSource.Project,
        )
        createProjectRetryJobLocked(slot, token.attempt + 1, SseFailureReason.TransportChanged, actions)
    }

    private fun rejectOlderGlobalOpenLocked(
        slot: GlobalSlot,
        token: GlobalOpenToken,
        actions: LifecycleActions,
    ) {
        val source = slot.source?.takeIf { it.lease.attemptId == token.attemptId }
        source?.handle?.let(actions.sourcesToCancel::add)
        if (source != null) {
            detachGlobalCalibrationsLocked(source.lease, actions)
            slot.source = null
        }
        if (slot.openAttemptId == token.attemptId) slot.openJob = null
        createGlobalRetryJobLocked(slot, token.attempt + 1, SseFailureReason.TransportChanged, actions)
    }

    private fun registerProjectSourceHandle(lease: ProjectSourceLease, handle: SseEventSource) {
        var cancel = false
        synchronized(lock) {
            val slot = projectSlots[lease.key]
            val source = slot?.source
            if (slot == null || !slot.desiredOpen || source?.lease != lease) {
                cancel = true
            } else {
                source.handle = handle
                if (slot.openAttemptId == lease.attemptId) slot.openJob = null
            }
        }
        if (cancel) handle.cancel()
    }

    private fun registerGlobalSourceHandle(lease: GlobalSourceLease, handle: SseEventSource) {
        var cancel = false
        synchronized(lock) {
            val slot = globalSlots[lease.serverId]
            val source = slot?.source
            if (slot == null || !slot.desiredOpen || source?.lease != lease) {
                cancel = true
            } else {
                source.handle = handle
                if (slot.openAttemptId == lease.attemptId) slot.openJob = null
            }
        }
        if (cancel) handle.cancel()
    }

    private fun projectListener(lease: ProjectSourceLease): SseEventListener = object : SseEventListener {
        override fun onOpen() = onProjectOpen(lease)

        override fun onEvent(id: String?, type: String?, data: String) = onProjectEvent(lease, id, data)

        override fun onFailure(failure: SseFailure) = onProjectTerminated(lease, failure)

        override fun onClosed() = onProjectTerminated(
            lease,
            SseFailure(SseFailureReason.StreamClosed),
        )
    }

    private fun globalListener(lease: GlobalSourceLease): SseEventListener = object : SseEventListener {
        override fun onOpen() = onGlobalOpen(lease)

        override fun onEvent(id: String?, type: String?, data: String) = onGlobalEvent(lease, id, data)

        override fun onFailure(failure: SseFailure) = onGlobalTerminated(lease, failure)

        override fun onClosed() = onGlobalTerminated(
            lease,
            SseFailure(SseFailureReason.StreamClosed),
        )
    }

    private fun onProjectOpen(lease: ProjectSourceLease) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = projectSlots[lease.key]
            val source = slot?.source
            if (slot == null || !slot.desiredOpen || source?.lease != lease || source.opened) return@synchronized
            if (slot.calibration?.token?.source == ProjectSnapshotSource.Global) {
                detachCalibrationLocked(slot, replayBufferedEvents = true, actions = actions)
            }
            source.opened = true
            if (slot.openAttemptId == lease.attemptId) slot.openJob = null
            setProjectStatusLocked(slot, SseConnectionStatus.Open)
            actions.calibrations += lease.toCalibrationRequest(CalibrationReason.Recovery)
        }
        execute(actions)
    }

    private fun onGlobalOpen(lease: GlobalSourceLease) {
        synchronized(lock) {
            val slot = globalSlots[lease.serverId]
            val source = slot?.source
            if (slot == null || !slot.desiredOpen || source?.lease != lease || source.opened) return
            source.opened = true
            if (slot.openAttemptId == lease.attemptId) slot.openJob = null
            setGlobalStatusLocked(slot, SseConnectionStatus.Open)
        }
    }

    private fun onProjectEvent(lease: ProjectSourceLease, id: String?, data: String) {
        try {
            val event = json.parseToJsonElement(data) as? JsonObject ?: return
            val bufferedEvent = BufferedProjectEvent(event, data.estimatedUtf16Bytes())
            val handoffEvent = event.toHandoffEvent(id)
            val actions = LifecycleActions()
            synchronized(lock) {
                val slot = projectSlots[lease.key]
                val source = slot?.source
                if (slot == null || !slot.desiredOpen || source?.lease != lease) return@synchronized
                if (source.opened && consumeProjectHandoffEventLocked(slot, handoffEvent)) return@synchronized
                val calibration = slot.calibration
                if (calibration != null && calibration.token.matches(lease)) {
                    if (event.requiresProjectSnapshotRefresh()) calibration.dirty = true
                    val exceedsLimit = calibration.events.size >= maxBufferedEvents ||
                        calibration.bufferedBytes + bufferedEvent.estimatedBytes > maxBufferedBytes
                    if (exceedsLimit) {
                        calibration.handle?.let(actions.snapshotsToCancel::add)
                        slot.calibration = null
                        replayEventsLocked(slot.key, calibration.events + bufferedEvent, actions)
                        scheduleDirtyFollowUpLocked(slot, calibration, actions)
                    } else {
                        calibration.events += bufferedEvent
                        calibration.bufferedBytes += bufferedEvent.estimatedBytes
                    }
                } else {
                    actions.reductions += store.reduceProjectEvent(
                        serverId = lease.key.serverId,
                        directory = lease.key.normalizedDirectory,
                        event = event,
                        workspace = lease.key.normalizedWorkspace,
                    )
                    if (event.requiresProjectSnapshotRefresh()) {
                        actions.calibrations += lease.toCalibrationRequest(CalibrationReason.CapabilityEvent)
                    }
                }
            }
            execute(actions)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
    }

    private fun onGlobalEvent(lease: GlobalSourceLease, id: String?, data: String) {
        try {
            val event = json.parseToJsonElement(data) as? JsonObject ?: return
            val handoffEvent = event.toHandoffEvent(id)
            val actions = LifecycleActions()
            synchronized(lock) {
                val slot = globalSlots[lease.serverId]
                val source = slot?.source
                if (slot == null || !slot.desiredOpen || source?.lease != lease) return@synchronized
                val directory = event.projectDirectory()?.let(pathNormalizer::normalize) ?: return@synchronized
                val workspace = normalizeWorkspace(event.projectWorkspace())
                val key = ProjectKey.create(lease.serverId, directory, workspace, pathNormalizer)
                val projectSlot = projectSlots[key]
                val projectSource = projectSlot?.source
                if (projectSource != null && projectSource.lease.transportIdentity != lease.transportIdentity) return@synchronized
                if (projectSource?.opened == true) return@synchronized
                val calibration = projectSlot?.calibration
                if (calibration != null && calibration.token.matches(lease)) {
                    if (event.requiresProjectSnapshotRefresh()) calibration.dirty = true
                    val bufferedEvent = BufferedProjectEvent(event, data.estimatedUtf16Bytes())
                    val exceedsLimit = calibration.events.size >= maxBufferedEvents ||
                        calibration.bufferedBytes + bufferedEvent.estimatedBytes > maxBufferedBytes
                    if (exceedsLimit) {
                        calibration.handle?.let(actions.snapshotsToCancel::add)
                        projectSlot.calibration = null
                        replayEventsLocked(key, calibration.events + bufferedEvent, actions)
                        scheduleDirtyFollowUpLocked(projectSlot, calibration, actions)
                    } else {
                        calibration.events += bufferedEvent
                        calibration.bufferedBytes += bufferedEvent.estimatedBytes
                    }
                    recordGlobalHandoffEventLocked(projectSlot, handoffEvent)
                } else {
                    actions.reductions += store.reduceProjectEvent(
                        serverId = lease.serverId,
                        directory = directory,
                        event = event,
                        workspace = workspace,
                    )
                    if (event.requiresProjectSnapshotRefresh()) {
                        actions.calibrations += lease.toCalibrationRequest(key, CalibrationReason.CapabilityEvent)
                    }
                    recordGlobalHandoffEventLocked(projectSlot, handoffEvent)
                }
            }
            execute(actions)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
    }

    private fun onProjectTerminated(lease: ProjectSourceLease, failure: SseFailure) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = projectSlots[lease.key]
            val source = slot?.source
            if (slot == null || !slot.desiredOpen || source?.lease != lease) return@synchronized
            source.handle?.let(actions.sourcesToCancel::add)
            slot.source = null
            if (slot.openAttemptId == lease.attemptId) slot.openJob = null
            detachCalibrationLocked(
                slot,
                replayBufferedEvents = true,
                actions = actions,
                source = ProjectSnapshotSource.Project,
            )
            val disposition = classifyFailure(failure)
            if (disposition.permanent) {
                setProjectStatusLocked(slot, SseConnectionStatus.Failed(disposition.reason))
            } else {
                val nextAttempt = if (source.opened) 1 else lease.attempt + 1
                createProjectRetryJobLocked(slot, nextAttempt, disposition.reason, actions)
            }
        }
        execute(actions)
    }

    private fun onGlobalTerminated(lease: GlobalSourceLease, failure: SseFailure) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = globalSlots[lease.serverId]
            val source = slot?.source
            if (slot == null || !slot.desiredOpen || source?.lease != lease) return@synchronized
            source.handle?.let(actions.sourcesToCancel::add)
            detachGlobalCalibrationsLocked(lease, actions)
            slot.source = null
            if (slot.openAttemptId == lease.attemptId) slot.openJob = null
            val disposition = classifyFailure(failure)
            if (disposition.permanent) {
                setGlobalStatusLocked(slot, SseConnectionStatus.Failed(disposition.reason))
            } else {
                val nextAttempt = if (source.opened) 1 else lease.attempt + 1
                createGlobalRetryJobLocked(slot, nextAttempt, disposition.reason, actions)
            }
        }
        execute(actions)
    }

    private fun handleProjectSetupFailure(token: ProjectOpenToken, throwable: Throwable) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = projectSlots[token.key]
                ?.takeIf { it.desiredOpen && it.generation == token.generation }
                ?: return@synchronized
            val sourceMatches = slot.source?.lease?.attemptId == token.attemptId
            val openMatches = slot.openJob != null && slot.openAttemptId == token.attemptId
            if (!sourceMatches && !openMatches) return@synchronized
            slot.source?.takeIf { sourceMatches }?.handle?.let(actions.sourcesToCancel::add)
            if (sourceMatches) slot.source = null
            if (openMatches) slot.openJob = null
            detachCalibrationLocked(
                slot,
                replayBufferedEvents = true,
                actions = actions,
                source = ProjectSnapshotSource.Project,
            )
            val disposition = classifyFailure(SseFailure.from(throwable))
            if (disposition.permanent) {
                setProjectStatusLocked(slot, SseConnectionStatus.Failed(disposition.reason))
            } else {
                createProjectRetryJobLocked(slot, token.attempt + 1, disposition.reason, actions)
            }
        }
        execute(actions)
    }

    private fun handleGlobalSetupFailure(token: GlobalOpenToken, throwable: Throwable) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = globalSlots[token.serverId]
                ?.takeIf { it.desiredOpen && it.generation == token.generation }
                ?: return@synchronized
            val sourceMatches = slot.source?.lease?.attemptId == token.attemptId
            val openMatches = slot.openJob != null && slot.openAttemptId == token.attemptId
            if (!sourceMatches && !openMatches) return@synchronized
            slot.source?.takeIf { sourceMatches }?.handle?.let(actions.sourcesToCancel::add)
            if (sourceMatches) {
                slot.source?.lease?.let { detachGlobalCalibrationsLocked(it, actions) }
                slot.source = null
            }
            if (openMatches) slot.openJob = null
            val disposition = classifyFailure(SseFailure.from(throwable))
            if (disposition.permanent) {
                setGlobalStatusLocked(slot, SseConnectionStatus.Failed(disposition.reason))
            } else {
                createGlobalRetryJobLocked(slot, token.attempt + 1, disposition.reason, actions)
            }
        }
        execute(actions)
    }

    private fun createProjectRetryJobLocked(
        slot: ProjectSlot,
        attempt: Int,
        reason: SseFailureReason,
        actions: LifecycleActions,
    ) {
        slot.retryJob?.let(actions.jobsToCancel::add)
        val delayMillis = retryDelayMillis(attempt)
        val token = ProjectRetryToken(slot.key, slot.generation, retryIds.incrementAndGet(), attempt, delayMillis)
        val job = scope.launch(start = CoroutineStart.LAZY) { runProjectRetry(token) }
        slot.retryId = token.retryId
        slot.retryJob = job
        setProjectStatusLocked(slot, SseConnectionStatus.Retrying(attempt, delayMillis, reason))
        actions.jobsToStart += job
    }

    private fun createGlobalRetryJobLocked(
        slot: GlobalSlot,
        attempt: Int,
        reason: SseFailureReason,
        actions: LifecycleActions,
    ) {
        slot.retryJob?.let(actions.jobsToCancel::add)
        val delayMillis = retryDelayMillis(attempt)
        val token = GlobalRetryToken(slot.serverId, slot.generation, retryIds.incrementAndGet(), attempt, delayMillis)
        val job = scope.launch(start = CoroutineStart.LAZY) { runGlobalRetry(token) }
        slot.retryId = token.retryId
        slot.retryJob = job
        setGlobalStatusLocked(slot, SseConnectionStatus.Retrying(attempt, delayMillis, reason))
        actions.jobsToStart += job
    }

    private suspend fun runProjectRetry(token: ProjectRetryToken) {
        delay(token.delayMillis)
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = projectSlots[token.key]
                ?.takeIf { it.desiredOpen && it.generation == token.generation && it.retryId == token.retryId }
                ?: return@synchronized
            slot.retryJob = null
            createProjectOpenJobLocked(slot, token.attempt, actions)
        }
        execute(actions)
    }

    private suspend fun runGlobalRetry(token: GlobalRetryToken) {
        delay(token.delayMillis)
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = globalSlots[token.serverId]
                ?.takeIf { it.desiredOpen && it.generation == token.generation && it.retryId == token.retryId }
                ?: return@synchronized
            slot.retryJob = null
            createGlobalOpenJobLocked(slot, token.attempt, actions)
        }
        execute(actions)
    }

    private fun requestCalibration(request: CalibrationRequest) {
        val token = synchronized(lock) {
            if (!isCalibrationAuthorityCurrentLocked(request)) return
            val slot = projectSlots[request.key]
                ?: ProjectSlot(request.key).also { projectSlots[request.key] = it }
            if (slot.calibration != null) return
            ProjectSnapshotToken(
                key = request.key,
                source = request.source,
                generation = request.generation,
                recoveryId = request.recoveryId,
                sourceId = request.sourceId,
                calibrationId = ++slot.calibrationSequence,
                transportIdentity = request.transportIdentity,
            ).also { snapshotToken ->
                slot.calibration = ProjectCalibrationState(snapshotToken)
            }
        }
        val handle = snapshotCoordinator.request(token) { guardedStore, outcome ->
            completeCalibration(token, guardedStore, outcome)
        }
        var cancel = false
        synchronized(lock) {
            val calibration = projectSlots[token.key]?.calibration
            if (calibration?.token == token) {
                calibration.handle = handle
            } else {
                cancel = true
            }
        }
        if (cancel) handle.cancel()
    }

    private fun completeCalibration(
        token: ProjectSnapshotToken,
        guardedStore: InMemoryOpenCodeStore,
        outcome: ProjectSnapshotOutcome,
    ) {
        val actions = LifecycleActions()
        synchronized(lock) {
            val slot = projectSlots[token.key]
            val calibration = slot?.calibration
            if (
                slot == null ||
                calibration?.token != token ||
                !isCalibrationAuthorityCurrentLocked(token)
            ) return@synchronized
            slot.calibration = null
            val needsFollowUp = calibration.dirty
            when (outcome) {
                is ProjectSnapshotOutcome.Success -> {
                    val loaded = outcome.loaded
                    if (loaded.transportIdentity != token.transportIdentity) {
                        replayEventsLocked(token.key, calibration.events, actions)
                        restartServerForTransportLocked(token.key.serverId, loaded.transportIdentity, actions)
                    } else if (!loaded.snapshot.matches(token.key)) {
                        guardedStore.setProjectLoading(
                            serverId = token.key.serverId,
                            directory = token.key.normalizedDirectory,
                            isLoading = false,
                            error = UiText.Resource(R.string.project_snapshot_identity_mismatch),
                            workspace = token.key.normalizedWorkspace,
                        )
                        replayEventsLocked(token.key, calibration.events, actions)
                    } else {
                        guardedStore.applyProjectSnapshot(loaded.snapshot)
                        loaded.activeSessionMessages
                            ?.takeIf { messages ->
                                guardedStore.currentProject(
                                    serverId = token.key.serverId,
                                    directory = token.key.normalizedDirectory,
                                    workspace = token.key.normalizedWorkspace,
                                ).activeSessionId == messages.sessionId
                            }
                            ?.let { messages ->
                                guardedStore.putMessagePage(
                                    serverId = token.key.serverId,
                                    directory = token.key.normalizedDirectory,
                                    sessionId = messages.sessionId,
                                    page = OpenCodeMessagePage(messages.bundle, messages.nextCursor),
                                    before = null,
                                    firstPageRequestGeneration = messages.requestGeneration,
                                    expectedRevision = messages.expectedRevision,
                                    workspace = token.key.normalizedWorkspace,
                                )
                            }
                        replayEventsLocked(token.key, calibration.events, actions)
                    }
                }

                is ProjectSnapshotOutcome.Failure -> {
                    guardedStore.setProjectLoading(
                        serverId = token.key.serverId,
                        directory = token.key.normalizedDirectory,
                        isLoading = false,
                        error = outcome.failure.toErrorUiText(R.string.project_snapshot_load_failed),
                        workspace = token.key.normalizedWorkspace,
                    )
                    replayEventsLocked(token.key, calibration.events, actions)
                }
            }
            if (needsFollowUp) scheduleDirtyFollowUpLocked(slot, calibration, actions)
        }
        execute(actions)
    }

    private fun detachCalibrationLocked(
        slot: ProjectSlot,
        replayBufferedEvents: Boolean,
        actions: LifecycleActions,
        source: ProjectSnapshotSource? = null,
    ) {
        val calibration = slot.calibration ?: return
        if (source != null && calibration.token.source != source) return
        calibration.handle?.let(actions.snapshotsToCancel::add)
        if (replayBufferedEvents) replayEventsLocked(slot.key, calibration.events, actions)
        slot.calibration = null
    }

    private fun detachGlobalCalibrationsLocked(lease: GlobalSourceLease, actions: LifecycleActions) {
        projectSlots.values.forEach { slot ->
            if (slot.calibration?.token?.matches(lease) == true) {
                detachCalibrationLocked(slot, replayBufferedEvents = true, actions = actions)
            }
        }
    }

    private fun replayEventsLocked(
        key: ProjectKey,
        events: List<BufferedProjectEvent>,
        actions: LifecycleActions,
    ) {
        events.forEach { buffered ->
            actions.reductions += store.reduceProjectEvent(
                serverId = key.serverId,
                directory = key.normalizedDirectory,
                event = buffered.event,
                workspace = key.normalizedWorkspace,
            )
        }
    }

    private fun recordGlobalHandoffEventLocked(slot: ProjectSlot?, event: HandoffEvent) {
        if (slot == null || !slot.desiredOpen) return
        if (event.estimatedBytes > maxBufferedBytes) {
            slot.clearHandoffEvents()
            return
        }
        while (
            slot.handoffEvents.isNotEmpty() &&
            (slot.handoffEvents.size >= maxBufferedEvents || slot.handoffBytes + event.estimatedBytes > maxBufferedBytes)
        ) {
            slot.handoffBytes -= slot.handoffEvents.removeFirst().estimatedBytes
        }
        if (slot.handoffEvents.size >= maxBufferedEvents || slot.handoffBytes + event.estimatedBytes > maxBufferedBytes) {
            slot.clearHandoffEvents()
            return
        }
        slot.handoffEvents.addLast(event)
        slot.handoffBytes += event.estimatedBytes
    }

    private fun consumeProjectHandoffEventLocked(slot: ProjectSlot, event: HandoffEvent): Boolean {
        val expected = slot.handoffEvents.peekFirst() ?: return false
        val matches = if (expected.id != null || event.id != null) {
            expected.id != null && expected.id == event.id
        } else {
            expected.fingerprint == event.fingerprint
        }
        if (!matches) {
            slot.clearHandoffEvents()
            return false
        }
        slot.handoffEvents.removeFirst()
        slot.handoffBytes -= expected.estimatedBytes
        return true
    }

    private fun clearServerHandoffEventsLocked(serverId: String) {
        projectSlots.values
            .filter { slot -> slot.key.serverId == serverId }
            .forEach(ProjectSlot::clearHandoffEvents)
    }

    private fun scheduleDirtyFollowUpLocked(
        slot: ProjectSlot,
        calibration: ProjectCalibrationState,
        actions: LifecycleActions,
    ) {
        if (!calibration.dirty) return
        if (isCalibrationAuthorityCurrentLocked(calibration.token)) {
            actions.calibrations += calibration.token.toCalibrationRequest(CalibrationReason.CapabilityEvent)
        }
    }

    private fun isCalibrationAuthorityCurrentLocked(request: CalibrationRequest): Boolean =
        isCalibrationAuthorityCurrentLocked(
            key = request.key,
            source = request.source,
            generation = request.generation,
            recoveryId = request.recoveryId,
            sourceId = request.sourceId,
            transportIdentity = request.transportIdentity,
        )

    private fun isCalibrationAuthorityCurrentLocked(token: ProjectSnapshotToken): Boolean =
        isCalibrationAuthorityCurrentLocked(
            key = token.key,
            source = token.source,
            generation = token.generation,
            recoveryId = token.recoveryId,
            sourceId = token.sourceId,
            transportIdentity = token.transportIdentity,
        )

    private fun isCalibrationAuthorityCurrentLocked(
        key: ProjectKey,
        source: ProjectSnapshotSource,
        generation: Long,
        recoveryId: Long,
        sourceId: Long,
        transportIdentity: ServerTransportIdentity,
    ): Boolean = when (source) {
        ProjectSnapshotSource.Project -> {
            val slot = projectSlots[key]
            val current = slot?.source
            slot != null &&
                slot.desiredOpen &&
                current?.opened == true &&
                current.lease.generation == generation &&
                current.lease.recoveryId == recoveryId &&
                current.lease.sourceId == sourceId &&
                current.lease.transportIdentity == transportIdentity
        }

        ProjectSnapshotSource.Global -> {
            val globalSlot = globalSlots[key.serverId]
            val current = globalSlot?.source
            val projectSource = projectSlots[key]?.source
            globalSlot != null &&
                globalSlot.desiredOpen &&
                current?.opened == true &&
                current.lease.generation == generation &&
                current.lease.recoveryId == recoveryId &&
                current.lease.sourceId == sourceId &&
                current.lease.transportIdentity == transportIdentity &&
                projectSource?.opened != true
        }
    }

    private fun setProjectStatusLocked(slot: ProjectSlot, status: SseConnectionStatus) {
        slot.status = status
        store.setProjectConnectionStatus(
            serverId = slot.key.serverId,
            directory = slot.key.normalizedDirectory,
            status = status,
            workspace = slot.key.normalizedWorkspace,
        )
    }

    private fun setGlobalStatusLocked(slot: GlobalSlot, status: SseConnectionStatus) {
        slot.status = status
        store.setGlobalConnectionStatus(slot.serverId, status)
    }

    private fun classifyFailure(failure: SseFailure): FailureDisposition {
        val reason = failure.reason
        val permanent = when (reason) {
            is SseFailureReason.HttpStatus -> reason.code != 200 &&
                reason.code !in RETRYABLE_HTTP_CODES &&
                reason.code !in 500..599
            SseFailureReason.InvalidResponse,
            SseFailureReason.ResponseTooLarge,
            is SseFailureReason.OperationRejected -> true
            SseFailureReason.NetworkUnavailable,
            SseFailureReason.Timeout,
            SseFailureReason.TransportChanged,
            SseFailureReason.StreamClosed,
            SseFailureReason.Unknown -> false
        }
        return FailureDisposition(permanent = permanent, reason = reason)
    }

    private fun execute(actions: LifecycleActions) {
        actions.snapshotsToCancel.forEach(ProjectSnapshotRequestHandle::cancel)
        actions.jobsToCancel.forEach(Job::cancel)
        actions.sourcesToCancel.forEach(SseEventSource::cancel)
        actions.jobsToStart.forEach(Job::start)
        actions.calibrations.forEach(::requestCalibration)
        if (actions.reductions.isNotEmpty()) {
            scope.launch {
                actions.reductions.forEach { reduction -> notifyProjectEvent(reduction) }
            }
        }
    }

    private fun projectKey(serverId: String, directory: String, workspace: String?): ProjectKey = ProjectKey.create(
        serverId = serverId,
        directory = directory,
        workspace = workspace,
        pathNormalizer = pathNormalizer,
    )

    private fun normalizeWorkspace(workspace: String?): String? = workspace
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let(pathNormalizer::normalize)

    private fun String.eventUrl(path: String, directory: String, workspace: String?): String {
        val builder = (trimEnd('/') + "/$path").toHttpUrl().newBuilder()
            .addQueryParameter("directory", directory)
        workspace?.let { builder.addQueryParameter("workspace", it) }
        return builder.build().toString()
    }

    private fun io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot.matches(key: ProjectKey): Boolean =
        ProjectKey.create(serverId, normalizedDirectory, workspace, pathNormalizer) == key

    private companion object {
        val RETRYABLE_HTTP_CODES = setOf(408, 409, 425, 429)
    }
}

internal data class ConnectionLifecycleState(
    val desiredOpen: Boolean,
    val ownerCount: Int,
    val generation: Long,
    val recoveryId: Long?,
    val sourceId: Long?,
    val transportIdentity: ServerTransportIdentity?,
)

private class ProjectSlot(val key: ProjectKey) {
    val owners = mutableSetOf<Long>()
    var desiredOpen = false
    var generation = 0L
    var recoverySequence = 0L
    var calibrationSequence = 0L
    var openAttemptId = 0L
    var retryId = 0L
    var source: ProjectSourceState? = null
    var openJob: Job? = null
    var retryJob: Job? = null
    var calibration: ProjectCalibrationState? = null
    var transportIdentity: ServerTransportIdentity? = null
    var status: SseConnectionStatus = SseConnectionStatus.Closed
    val handoffEvents = ArrayDeque<HandoffEvent>()
    var handoffBytes = 0

    fun hasLifecycleWork(): Boolean = source != null || openJob != null || retryJob != null

    fun clearHandoffEvents() {
        handoffEvents.clear()
        handoffBytes = 0
    }
}

private class GlobalSlot(val serverId: String) {
    val owners = mutableSetOf<Long>()
    var desiredOpen = false
    var generation = 0L
    var recoverySequence = 0L
    var openAttemptId = 0L
    var retryId = 0L
    var source: GlobalSourceState? = null
    var openJob: Job? = null
    var retryJob: Job? = null
    var transportIdentity: ServerTransportIdentity? = null
    var status: SseConnectionStatus = SseConnectionStatus.Closed

    fun hasLifecycleWork(): Boolean = source != null || openJob != null || retryJob != null
}

private data class ProjectOpenToken(
    val key: ProjectKey,
    val generation: Long,
    val attemptId: Long,
    val attempt: Int,
) {
    override fun toString(): String =
        "ProjectOpenToken(key=$key, generation=$generation, attemptId=$attemptId, attempt=$attempt)"
}

private data class GlobalOpenToken(
    val serverId: String,
    val generation: Long,
    val attemptId: Long,
    val attempt: Int,
) {
    override fun toString(): String =
        "GlobalOpenToken(serverId=<redacted>, generation=$generation, attemptId=$attemptId, attempt=$attempt)"
}

private data class ProjectRetryToken(
    val key: ProjectKey,
    val generation: Long,
    val retryId: Long,
    val attempt: Int,
    val delayMillis: Long,
) {
    override fun toString(): String =
        "ProjectRetryToken(key=$key, generation=$generation, retryId=$retryId, attempt=$attempt, " +
            "delayMillis=$delayMillis)"
}

private data class GlobalRetryToken(
    val serverId: String,
    val generation: Long,
    val retryId: Long,
    val attempt: Int,
    val delayMillis: Long,
) {
    override fun toString(): String =
        "GlobalRetryToken(serverId=<redacted>, generation=$generation, retryId=$retryId, " +
            "attempt=$attempt, delayMillis=$delayMillis)"
}

private data class ProjectSourceLease(
    val key: ProjectKey,
    val generation: Long,
    val recoveryId: Long,
    val sourceId: Long,
    val transportIdentity: ServerTransportIdentity,
    val attemptId: Long,
    val attempt: Int,
) {
    override fun toString(): String =
        "ProjectSourceLease(key=$key, generation=$generation, recoveryId=$recoveryId, sourceId=$sourceId, " +
            "transportIdentity=$transportIdentity, attemptId=$attemptId, attempt=$attempt)"
}

private data class GlobalSourceLease(
    val serverId: String,
    val generation: Long,
    val recoveryId: Long,
    val sourceId: Long,
    val transportIdentity: ServerTransportIdentity,
    val attemptId: Long,
    val attempt: Int,
) {
    override fun toString(): String =
        "GlobalSourceLease(serverId=<redacted>, generation=$generation, recoveryId=$recoveryId, " +
            "sourceId=$sourceId, transportIdentity=$transportIdentity, attemptId=$attemptId, attempt=$attempt)"
}

private class ProjectSourceState(
    val lease: ProjectSourceLease,
    var handle: SseEventSource? = null,
    var opened: Boolean = false,
)

private class GlobalSourceState(
    val lease: GlobalSourceLease,
    var handle: SseEventSource? = null,
    var opened: Boolean = false,
)

private class ProjectCalibrationState(
    val token: ProjectSnapshotToken,
    val events: MutableList<BufferedProjectEvent> = mutableListOf(),
    var bufferedBytes: Int = 0,
    var handle: ProjectSnapshotRequestHandle? = null,
    var dirty: Boolean = false,
)

private data class BufferedProjectEvent(
    val event: JsonObject,
    val estimatedBytes: Int,
) {
    override fun toString(): String =
        "BufferedProjectEvent(event=<redacted>, estimatedBytes=$estimatedBytes)"
}

private data class HandoffEvent(
    val id: String?,
    val fingerprint: String,
    val estimatedBytes: Int,
) {
    override fun toString(): String =
        "HandoffEvent(id=${if (id == null) "null" else "<redacted>"}, " +
            "fingerprint=<redacted>, estimatedBytes=$estimatedBytes)"
}

private data class PreparedProjectSource(
    val lease: ProjectSourceLease?,
    val actions: LifecycleActions,
)

private data class PreparedGlobalSource(
    val lease: GlobalSourceLease?,
    val actions: LifecycleActions,
)

private data class FailureDisposition(
    val permanent: Boolean,
    val reason: SseFailureReason,
)

private enum class CalibrationReason {
    Recovery,
    Foreground,
    CapabilityEvent,
    ProviderMutation,
}

private data class CalibrationRequest(
    val key: ProjectKey,
    val source: ProjectSnapshotSource,
    val generation: Long,
    val recoveryId: Long,
    val sourceId: Long,
    val transportIdentity: ServerTransportIdentity,
    val reason: CalibrationReason,
) {
    override fun toString(): String =
        "CalibrationRequest(key=$key, source=$source, generation=$generation, recoveryId=$recoveryId, " +
            "sourceId=$sourceId, transportIdentity=$transportIdentity, reason=$reason)"
}

private class LifecycleActions {
    val jobsToStart = mutableListOf<Job>()
    val jobsToCancel = mutableListOf<Job>()
    val sourcesToCancel = mutableListOf<SseEventSource>()
    val snapshotsToCancel = mutableListOf<ProjectSnapshotRequestHandle>()
    val reductions = mutableListOf<ProjectEventReduction>()
    val calibrations = mutableListOf<CalibrationRequest>()
}

private fun defaultRetryDelay(attempt: Int): Long = min(30_000L, 1_000L * (1L shl min(attempt, 5)))

private fun ProjectSourceLease.toCalibrationRequest(reason: CalibrationReason): CalibrationRequest = CalibrationRequest(
    key = key,
    source = ProjectSnapshotSource.Project,
    generation = generation,
    recoveryId = recoveryId,
    sourceId = sourceId,
    transportIdentity = transportIdentity,
    reason = reason,
)

private fun GlobalSourceLease.toCalibrationRequest(
    key: ProjectKey,
    reason: CalibrationReason,
): CalibrationRequest = CalibrationRequest(
    key = key,
    source = ProjectSnapshotSource.Global,
    generation = generation,
    recoveryId = recoveryId,
    sourceId = sourceId,
    transportIdentity = transportIdentity,
    reason = reason,
)

private fun ProjectSnapshotToken.toCalibrationRequest(reason: CalibrationReason): CalibrationRequest = CalibrationRequest(
    key = key,
    source = source,
    generation = generation,
    recoveryId = recoveryId,
    sourceId = sourceId,
    transportIdentity = transportIdentity,
    reason = reason,
)

private fun ProjectSnapshotToken.matches(lease: ProjectSourceLease): Boolean =
    source == ProjectSnapshotSource.Project &&
        key == lease.key &&
        generation == lease.generation &&
        recoveryId == lease.recoveryId &&
        sourceId == lease.sourceId &&
        transportIdentity == lease.transportIdentity

private fun ProjectSnapshotToken.matches(lease: GlobalSourceLease): Boolean =
    source == ProjectSnapshotSource.Global &&
        key.serverId == lease.serverId &&
        generation == lease.generation &&
        recoveryId == lease.recoveryId &&
        sourceId == lease.sourceId &&
        transportIdentity == lease.transportIdentity

private fun String.estimatedUtf16Bytes(): Int = length
    .coerceIn(1, Int.MAX_VALUE / 2)
    .times(2)

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.payloadObject(): JsonObject {
    val payload = objectOrNull("payload") ?: this
    return if (payload.string("type") == "sync") payload.objectOrNull("syncEvent") ?: payload else payload
}

private fun JsonObject.toHandoffEvent(id: String?): HandoffEvent {
    val normalizedId = id?.takeIf(String::isNotBlank)
    val fingerprint = payloadObject().canonicalJson()
    val estimatedBytes = fingerprint.estimatedUtf16Bytes().saturatedAdd(normalizedId?.estimatedUtf16Bytes() ?: 0)
    return HandoffEvent(normalizedId, fingerprint, estimatedBytes)
}

private fun JsonElement.canonicalJson(): String = when (this) {
    is JsonObject -> entries
        .sortedBy { entry -> entry.key }
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${JsonPrimitive(key)}:${value.canonicalJson()}"
        }
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { element -> element.canonicalJson() }
    else -> toString()
}

private fun Int.saturatedAdd(other: Int): Int = if (this > Int.MAX_VALUE - other) Int.MAX_VALUE else this + other

private fun JsonObject.requiresProjectSnapshotRefresh(): Boolean {
    val payload = payloadObject()
    val type = (payload.string("type") ?: string("type"))?.lowercase(Locale.US) ?: return false
    return type == "lsp.updated" || type == "mcp.tools.changed"
}

private fun JsonObject.projectDirectory(): String? {
    val payload = payloadObject()
    val properties = payload.objectOrNull("properties") ?: payload.objectOrNull("data")
    return string("directory") ?: payload.string("directory") ?: properties?.string("directory")
}

private fun JsonObject.projectWorkspace(): String? {
    val payload = payloadObject()
    val properties = payload.objectOrNull("properties") ?: payload.objectOrNull("data")
    return string("workspace") ?: payload.string("workspace") ?: properties?.string("workspace")
}
