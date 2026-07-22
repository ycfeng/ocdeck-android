package io.github.ycfeng.ocdeck.app

import android.content.Context
import io.github.ycfeng.ocdeck.core.navigation.SessionVisibilityRegistry
import io.github.ycfeng.ocdeck.core.notification.OpenCodeNotificationChannelRegistry
import io.github.ycfeng.ocdeck.core.notification.OpenCodeSystemNotifier
import io.github.ycfeng.ocdeck.core.network.AppConnectionCoordinator
import io.github.ycfeng.ocdeck.core.network.DefaultForegroundHealthChecker
import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorManager
import io.github.ycfeng.ocdeck.core.network.DefaultOpenCodeHealthProbeFactory
import io.github.ycfeng.ocdeck.core.network.OpenCodeApiFactory
import io.github.ycfeng.ocdeck.core.network.OpenCodeEventClient
import io.github.ycfeng.ocdeck.core.network.ProjectSnapshotCoordinator
import io.github.ycfeng.ocdeck.core.network.SessionListWindowCoordinator
import io.github.ycfeng.ocdeck.core.network.SshTunnelManager
import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.core.security.SecureCredentialStore
import io.github.ycfeng.ocdeck.core.sound.OpenCodeSoundPlayer
import io.github.ycfeng.ocdeck.core.store.InMemoryOpenCodeStore
import io.github.ycfeng.ocdeck.core.store.OpenCodeEventReducer
import io.github.ycfeng.ocdeck.core.util.PathNormalizer
import io.github.ycfeng.ocdeck.core.util.ProjectFilePathNormalizer
import io.github.ycfeng.ocdeck.data.opencode.OpenCodeRepository
import io.github.ycfeng.ocdeck.data.opencode.OpenCodeProviderRepository
import io.github.ycfeng.ocdeck.data.project.AndroidRecentProjectFailureReporter
import io.github.ycfeng.ocdeck.data.project.RecentProjectRecorder
import io.github.ycfeng.ocdeck.data.project.RecentProjectRepository
import io.github.ycfeng.ocdeck.data.project.RecentProjectStore
import io.github.ycfeng.ocdeck.data.settings.AppSettingsStore
import io.github.ycfeng.ocdeck.data.server.ServerPreferencesStore
import io.github.ycfeng.ocdeck.data.server.ServerRepository
import io.github.ycfeng.ocdeck.domain.prompt.OpenCodePromptSender
import io.github.ycfeng.ocdeck.domain.prompt.PromptOperationGate
import io.github.ycfeng.ocdeck.domain.prompt.SessionOperationCoordinator
import io.github.ycfeng.ocdeck.frpcstcpvisitor.GoMobileFrpcStcpVisitorClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    val redactor = Redactor()
    val pathNormalizer = PathNormalizer()
    val projectFilePathNormalizer = ProjectFilePathNormalizer()
    val secureCredentialStore = SecureCredentialStore(applicationContext)
    val sshTunnelManager = SshTunnelManager()
    val appSettingsStore = AppSettingsStore(applicationContext)
    val soundPlayer = OpenCodeSoundPlayer(applicationContext)
    val notificationChannelRegistry = OpenCodeNotificationChannelRegistry(applicationContext)
    val systemNotifier = OpenCodeSystemNotifier(
        applicationContext,
        appSettingsStore,
        soundPlayer,
        notificationChannelRegistry,
        applicationScope,
    )
    val serverPreferencesStore = ServerPreferencesStore(applicationContext, json)
    val openCodeApiFactory = OpenCodeApiFactory(json, redactor)
    val frpcStcpVisitorManager = FrpcStcpVisitorManager(
        client = GoMobileFrpcStcpVisitorClient(json),
        healthProbeFactory = DefaultOpenCodeHealthProbeFactory(openCodeApiFactory),
    )
    val serverRepository = ServerRepository(
        preferencesStore = serverPreferencesStore,
        credentialStore = secureCredentialStore,
        apiFactory = openCodeApiFactory,
        sshTunnelManager = sshTunnelManager,
        frpcStcpVisitorManager = frpcStcpVisitorManager,
    )
    private val recentProjectFailureReporter = AndroidRecentProjectFailureReporter()
    val recentProjectRepository: RecentProjectRepository = RecentProjectStore(
        context = applicationContext,
        json = json,
        pathNormalizer = pathNormalizer,
        failureReporter = recentProjectFailureReporter,
    )
    val recentProjectRecorder = RecentProjectRecorder(
        repository = recentProjectRepository,
        pathNormalizer = pathNormalizer,
        scope = applicationScope,
        failureReporter = recentProjectFailureReporter,
    )
    val openCodeStore = InMemoryOpenCodeStore(pathNormalizer, OpenCodeEventReducer(redactor))
    val sessionVisibilityRegistry = SessionVisibilityRegistry(openCodeStore)
    val openCodeRepository = OpenCodeRepository(
        serverRepository = serverRepository,
        pathNormalizer = pathNormalizer,
        projectFilePathNormalizer = projectFilePathNormalizer,
        json = json,
        redactor = redactor,
        fileContentDispatcher = Dispatchers.IO,
    )
    val openCodeProviderRepository = OpenCodeProviderRepository(
        serverRepository = serverRepository,
        pathNormalizer = pathNormalizer,
        json = json,
    )
    val projectSnapshotCoordinator = ProjectSnapshotCoordinator(
        loader = openCodeRepository,
        store = openCodeStore,
        scope = applicationScope,
    )
    val sessionListWindowCoordinator = SessionListWindowCoordinator(
        loader = openCodeRepository,
        store = openCodeStore,
        pathNormalizer = pathNormalizer,
        scope = applicationScope,
    )
    val openCodeEventClient = OpenCodeEventClient(
        connectionProvider = serverRepository,
        store = openCodeStore,
        pathNormalizer = pathNormalizer,
        json = json,
        redactor = redactor,
        snapshotCoordinator = projectSnapshotCoordinator,
        notifyProjectEvent = systemNotifier::notifyProjectEvent,
        scope = applicationScope,
    )
    val appConnectionCoordinator = AppConnectionCoordinator(
        controller = openCodeEventClient,
        healthChecker = DefaultForegroundHealthChecker(serverRepository),
        scope = applicationScope,
    )
    val promptOperationGate = PromptOperationGate(pathNormalizer)
    val promptSender = OpenCodePromptSender(
        gateway = openCodeRepository,
        store = openCodeStore,
        pathNormalizer = pathNormalizer,
        projectFilePathNormalizer = projectFilePathNormalizer,
        operationGate = promptOperationGate,
    )
    val sessionOperationCoordinator = SessionOperationCoordinator(
        promptGateway = openCodeRepository,
        revertGateway = openCodeRepository,
        operationGate = promptOperationGate,
        pathNormalizer = pathNormalizer,
    )
    val viewModelProvider = AppViewModelProvider(this)
}
