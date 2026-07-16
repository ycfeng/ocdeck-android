package io.github.ycfeng.ocdeck.core.network

import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.OpenCodeEventReducer
import io.github.ycfeng.ocdeck.core.store.ProjectEventReduction
import io.github.ycfeng.ocdeck.core.store.SessionListWindowState
import io.github.ycfeng.ocdeck.core.store.SseFailureReason
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerTransportIdentity
import io.github.ycfeng.ocdeck.domain.model.OpenCodeAgent
import io.github.ycfeng.ocdeck.domain.model.OpenCodeCommand
import io.github.ycfeng.ocdeck.domain.model.OpenCodeProjectSnapshot
import io.github.ycfeng.ocdeck.domain.model.OpenCodeSession
import io.github.ycfeng.ocdeck.domain.model.PromptCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

internal class MutableSseConnectionProvider(
    var identity: ServerTransportIdentity = testTransport(),
) : SseConnectionProvider {
    var calls: Int = 0
    var handler: suspend (serverId: String, call: Int) -> SseConnection = { serverId, _ ->
        testSseConnection(serverId, identity)
    }

    override suspend fun getSseConnection(serverId: String): SseConnection {
        calls += 1
        return handler(serverId, calls)
    }
}

internal class RecordingSseEventSourceFactory : SseEventSourceFactory {
    val sources = mutableListOf<RecordingSseEventSource>()
    var beforeReturn: ((SseEventListener) -> Unit)? = null
    var closeOnCancel: Boolean = false

    override fun open(
        connection: SseConnection,
        request: SseRequest,
        listener: SseEventListener,
    ): SseEventSource {
        val source = RecordingSseEventSource(
            connection = connection,
            request = request,
            listener = listener,
            closeOnCancel = { closeOnCancel },
        )
        beforeReturn?.invoke(listener)
        sources += source
        return source
    }
}

internal class RecordingSseEventSource(
    val connection: SseConnection,
    val request: SseRequest,
    private val listener: SseEventListener,
    private val closeOnCancel: () -> Boolean,
) : SseEventSource {
    private val cancelled = AtomicBoolean()
    var cancelCount: Int = 0
        private set

    override fun cancel() {
        cancelCount += 1
        if (cancelled.compareAndSet(false, true) && closeOnCancel()) listener.onClosed()
    }

    fun open() = listener.onOpen()

    fun event(data: String, id: String? = null) = listener.onEvent(id = id, type = null, data = data)

    @Suppress("UNUSED_PARAMETER")
    fun fail(
        throwable: Throwable? = null,
        responseCode: Int? = null,
        responseMessage: String? = null,
    ) = listener.onFailure(
        when {
            throwable != null -> SseFailure.from(throwable)
            responseCode != null -> SseFailure(SseFailureReason.HttpStatus(responseCode))
            else -> SseFailure(SseFailureReason.Unknown)
        },
    )

    fun close() = listener.onClosed()
}

internal class ScriptedProjectSnapshotLoader(
    var handler: suspend (serverId: String, directory: String, workspace: String?, call: Int) -> Result<LoadedProjectSnapshot>,
) : ProjectSnapshotLoader {
    var calls: Int = 0
    val activeSessionRequests = mutableListOf<ActiveSessionMessagesRequest?>()
    val sessionWindowRequests = mutableListOf<SessionListWindowState>()

    override suspend fun loadProjectSnapshot(
        serverId: String,
        directory: String,
        workspace: String?,
        sessionWindow: SessionListWindowState,
        activeSessionMessages: ActiveSessionMessagesRequest?,
    ): Result<LoadedProjectSnapshot> {
        calls += 1
        activeSessionRequests += activeSessionMessages
        sessionWindowRequests += sessionWindow
        return handler(serverId, directory, workspace, calls)
    }
}

internal data class EventClientTestEnvironment(
    val client: OpenCodeEventClient,
    val store: InMemoryOpenCodeStore,
    val snapshotCoordinator: ProjectSnapshotCoordinator,
    val reductions: MutableList<ProjectEventReduction>,
)

internal fun eventClientEnvironment(
    scope: CoroutineScope,
    provider: MutableSseConnectionProvider = MutableSseConnectionProvider(),
    factory: RecordingSseEventSourceFactory = RecordingSseEventSourceFactory(),
    loader: ScriptedProjectSnapshotLoader = ScriptedProjectSnapshotLoader { serverId, directory, workspace, _ ->
        Result.success(loadedSnapshot(serverId, directory, workspace, provider.identity))
    },
    retryDelayMillis: (Int) -> Long = { 1_000L },
    maxBufferedEvents: Int = 256,
    maxBufferedBytes: Int = 1_048_576,
): EventClientTestEnvironment {
    val pathNormalizer = PathNormalizer()
    val redactor = Redactor()
    val store = InMemoryOpenCodeStore(pathNormalizer, OpenCodeEventReducer(redactor))
    val coordinator = ProjectSnapshotCoordinator(loader, store, scope)
    val reductions = mutableListOf<ProjectEventReduction>()
    val client = OpenCodeEventClient(
        connectionProvider = provider,
        store = store,
        pathNormalizer = pathNormalizer,
        json = Json { ignoreUnknownKeys = true },
        redactor = redactor,
        snapshotCoordinator = coordinator,
        notifyProjectEvent = reductions::add,
        eventSourceFactory = factory,
        scope = scope,
        retryDelayMillis = retryDelayMillis,
        maxBufferedEvents = maxBufferedEvents,
        maxBufferedBytes = maxBufferedBytes,
    )
    return EventClientTestEnvironment(client, store, coordinator, reductions)
}

internal fun testSseConnection(
    serverId: String = "server",
    identity: ServerTransportIdentity = testTransport(),
): SseConnection = SseConnection(
    server = ServerConfig(
        id = serverId,
        name = serverId,
        baseUrl = "http://127.0.0.1:4096",
    ),
    password = null,
    effectiveBaseUrl = "http://127.0.0.1:4096",
    transportIdentity = identity,
)

internal fun testTransport(
    configEpoch: Long = 1L,
    controlEpoch: Long? = null,
): ServerTransportIdentity = ServerTransportIdentity(
    configEpoch = configEpoch,
    stcpGeneration = controlEpoch?.let {
        FrpcStcpVisitorGeneration(
            serverId = "server",
            configEpoch = configEpoch,
            ordinal = 1L,
        )
    },
    stcpControlEpoch = controlEpoch,
)

internal fun loadedSnapshot(
    serverId: String,
    directory: String,
    workspace: String? = null,
    identity: ServerTransportIdentity = testTransport(),
    sessions: List<OpenCodeSession> = emptyList(),
    activeSessionMessages: LoadedActiveSessionMessages? = null,
): LoadedProjectSnapshot = LoadedProjectSnapshot(
    snapshot = projectSnapshot(serverId, directory, workspace, sessions),
    transportIdentity = identity,
    activeSessionMessages = activeSessionMessages,
)

internal fun projectSnapshot(
    serverId: String,
    directory: String,
    workspace: String? = null,
    sessions: List<OpenCodeSession> = emptyList(),
): OpenCodeProjectSnapshot = OpenCodeProjectSnapshot(
    serverId = serverId,
    normalizedDirectory = directory,
    workspace = workspace,
    pathInfo = null,
    sessions = sessions,
    statuses = emptyMap(),
    providerCount = 0,
    models = emptyList(),
    agents = emptyList<OpenCodeAgent>(),
    commands = emptyList<OpenCodeCommand>(),
    promptCapabilities = PromptCapabilities(isLoaded = true),
    mcps = emptyList(),
    lsps = emptyList(),
    plugins = emptyList(),
    commandCount = 0,
    permissionCount = 0,
    questionCount = 0,
)

internal fun testSession(
    id: String,
    directory: String = "E:/work/app",
    title: String = id,
    updatedAt: Long = 1L,
): OpenCodeSession = OpenCodeSession(
    id = id,
    title = title,
    normalizedDirectory = directory,
    path = null,
    parentId = null,
    agent = null,
    modelLabel = null,
    updatedAt = updatedAt,
    archivedAt = null,
)

internal fun sessionUpdatedEvent(
    id: String,
    title: String,
    directory: String = "E:/work/app",
    updatedAt: Long = 1L,
): String =
    """{"type":"session.updated","properties":{"info":{"id":"$id","title":"$title","directory":"$directory","time":{"updated":"$updatedAt"}}}}"""

internal fun capabilityEvent(type: String): String = """{"type":"$type"}"""

internal fun partDeltaEvent(
    messageId: String,
    partId: String,
    delta: String,
): String =
    """{"type":"message.part.delta","properties":{"messageID":"$messageId","partID":"$partId","field":"text","delta":"$delta"}}"""

internal fun globalSyncEvent(directory: String, event: String, workspace: String? = null): String {
    val workspaceField = workspace?.let { ",\"workspace\":\"$it\"" }.orEmpty()
    return """{"directory":"$directory"$workspaceField,"payload":{"type":"sync","syncEvent":$event}}"""
}
