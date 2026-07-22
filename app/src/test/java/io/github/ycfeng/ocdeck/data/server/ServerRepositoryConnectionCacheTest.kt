package io.github.ycfeng.ocdeck.data.server

import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorManager
import io.github.ycfeng.ocdeck.core.network.OpenCodeApiFactory
import io.github.ycfeng.ocdeck.core.network.OpenCodeHealthProbe
import io.github.ycfeng.ocdeck.core.network.OpenCodeHealthProbeFactory
import io.github.ycfeng.ocdeck.core.network.ServerHealthDto
import io.github.ycfeng.ocdeck.core.network.SshTunnelManager
import io.github.ycfeng.ocdeck.core.security.CredentialStore
import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcEnsureVisitorResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcSessionConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorClient
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorConfig
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStcpVisitorState
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcStopSessionResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcVisitorReadyResult
import io.github.ycfeng.ocdeck.frpcstcpvisitor.FrpcVisitorRuntimeState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryConnectionCacheTest {
    @Test
    fun repeatedAndConcurrentDirectConnectionsReuseOneClientBundle() = runTest {
        val server = directServer()
        val repository = repository(ConnectionCacheServerConfigStore(listOf(server)))

        val first = repository.getConnection(server.id)
        val repeated = repository.getConnection(server.id)
        val concurrent = List(50) {
            async { repository.getConnection(server.id) }
        }.awaitAll()

        assertNotSame(first, repeated)
        (listOf(repeated) + concurrent).forEach { connection ->
            assertSame(first.api, connection.api)
            assertSame(first.providerOAuthApi, connection.providerOAuthApi)
            assertSame(first.sessionMessagesTransport, connection.sessionMessagesTransport)
        }
    }

    @Test
    fun committedConfigurationChangeReplacesOnlyThatServersClientBundle() = runTest {
        val firstServer = directServer(id = "first")
        val secondServer = directServer(id = "second")
        val store = ConnectionCacheServerConfigStore(listOf(firstServer, secondServer))
        val repository = repository(store)
        val firstBefore = repository.getConnection(firstServer.id)
        val secondBefore = repository.getConnection(secondServer.id)

        repository.updateServer(
            serverId = firstServer.id,
            baseUrl = firstServer.baseUrl,
            name = "Renamed",
            username = firstServer.username,
            password = null,
        )

        val firstAfter = repository.getConnection(firstServer.id)
        val secondAfter = repository.getConnection(secondServer.id)
        assertNotSame(firstBefore.api, firstAfter.api)
        assertNotSame(firstBefore.providerOAuthApi, firstAfter.providerOAuthApi)
        assertNotSame(firstBefore.sessionMessagesTransport, firstAfter.sessionMessagesTransport)
        assertEquals(2L, firstAfter.transportIdentity.configEpoch)
        assertSame(secondBefore.api, secondAfter.api)
        assertSame(secondBefore.providerOAuthApi, secondAfter.providerOAuthApi)
        assertSame(secondBefore.sessionMessagesTransport, secondAfter.sessionMessagesTransport)
        assertEquals(1L, secondAfter.transportIdentity.configEpoch)
    }

    @Test
    fun rejectedConfigurationChangeKeepsCurrentClientBundle() = runTest {
        val server = directServer()
        val store = ConnectionCacheServerConfigStore(listOf(server))
        val repository = repository(store)
        val before = repository.getConnection(server.id)
        val failure = IllegalStateException("write failed")
        store.upsertFailure = failure

        val result = runCatching {
            repository.updateServer(
                serverId = server.id,
                baseUrl = server.baseUrl,
                name = "Renamed",
                username = server.username,
                password = null,
            )
        }

        assertSame(failure, result.exceptionOrNull())
        val after = repository.getConnection(server.id)
        assertSame(before.api, after.api)
        assertSame(before.providerOAuthApi, after.providerOAuthApi)
        assertSame(before.sessionMessagesTransport, after.sessionMessagesTransport)
        assertEquals(1L, after.transportIdentity.configEpoch)
    }

    @Test
    fun stcpControlEpochChangeReplacesClientWithoutCachingReadinessEvidence() = runTest {
        val server = stcpServer()
        val store = ConnectionCacheServerConfigStore(listOf(server))
        val credentials = ConnectionCacheCredentialStore(
            mapOf(STCP_SECRET_ALIAS to "stcp-secret"),
        )
        val frpcClient = ConnectionCacheFrpcClient(localPort = 5096)
        val probes = ConnectionCacheHealthProbeFactory()
        val manager = FrpcStcpVisitorManager(
            client = frpcClient,
            healthProbeFactory = probes,
            scope = backgroundScope,
        )
        val repository = repository(store, credentials, manager)

        val initial = repository.getConnection(server.id)
        val unchanged = repository.getConnection(server.id)

        assertTrue(initial.readinessHealth != null)
        assertNull(unchanged.readinessHealth)
        assertSame(initial.api, unchanged.api)
        assertEquals(1L, unchanged.transportIdentity.stcpControlEpoch)

        frpcClient.currentControlEpoch = 2L
        val refreshed = repository.getConnection(server.id)
        val repeated = repository.getConnection(server.id)

        assertTrue(refreshed.readinessHealth != null)
        assertEquals(2L, refreshed.transportIdentity.stcpControlEpoch)
        assertNotSame(initial.api, refreshed.api)
        assertNotSame(initial.providerOAuthApi, refreshed.providerOAuthApi)
        assertNotSame(initial.sessionMessagesTransport, refreshed.sessionMessagesTransport)
        assertNull(repeated.readinessHealth)
        assertSame(refreshed.api, repeated.api)
        assertSame(refreshed.providerOAuthApi, repeated.providerOAuthApi)
        assertSame(refreshed.sessionMessagesTransport, repeated.sessionMessagesTransport)
        assertEquals(2, probes.probeCount)
    }

    private fun repository(
        store: ServerConfigStore,
        credentials: CredentialStore = ConnectionCacheCredentialStore(),
        frpcManager: FrpcStcpVisitorManager = FrpcStcpVisitorManager(
            client = ConnectionCacheFrpcClient(localPort = 5096),
            healthProbeFactory = ConnectionCacheHealthProbeFactory(),
        ),
    ): ServerRepository = ServerRepository(
        preferencesStore = store,
        credentialStore = credentials,
        apiFactory = OpenCodeApiFactory(TEST_JSON, Redactor()),
        sshTunnelManager = SshTunnelManager(),
        frpcStcpVisitorManager = frpcManager,
        runtimeInvalidator = ConnectionCacheRuntimeInvalidator,
    )

    private fun directServer(id: String = "remote"): ServerConfig = ServerConfig(
        id = id,
        name = "Remote",
        baseUrl = "https://remote.example.test:8443",
        username = "opencode",
    )

    private fun stcpServer(): ServerConfig = directServer().copy(
        baseUrl = "https://opencode.example.test:8443/opencode/",
        frpcStcpVisitor = ServerFrpcStcpVisitorConfig(
            serverAddr = "frps.example.test",
            serverName = "opencode_stcp",
            secretKeyKey = STCP_SECRET_ALIAS,
            bindPort = 4096,
        ),
    )

    private companion object {
        val TEST_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        const val STCP_SECRET_ALIAS = "server-remote-frpc-stcp-secret-key"
    }
}

private object ConnectionCacheRuntimeInvalidator : ServerRuntimeInvalidator {
    override suspend fun invalidate(serverId: String, minimumConfigEpoch: Long, ssh: Boolean, stcp: Boolean) = Unit
}

private class ConnectionCacheCredentialStore(
    initialValues: Map<String, String> = emptyMap(),
) : CredentialStore {
    private val values = initialValues.toMutableMap()

    override suspend fun putSecret(alias: String, secret: String) {
        values[alias] = secret
    }

    override suspend fun getSecret(alias: String): String? = values[alias]

    override suspend fun removeSecret(alias: String) {
        values.remove(alias)
    }
}

private class ConnectionCacheServerConfigStore(
    initialServers: List<ServerConfig>,
) : ServerConfigStore {
    private val state = MutableStateFlow(initialServers)
    private val composerPreferences = MutableStateFlow<Map<String, ServerComposerModelPreference>>(emptyMap())
    private val hiddenModels = MutableStateFlow<List<ServerHiddenModelPreference>>(emptyList())
    var upsertFailure: Exception? = null

    override val servers: Flow<List<ServerConfig>> = state

    override suspend fun getServers(): List<ServerConfig> = state.value

    override fun observeComposerModelPreference(serverId: String): Flow<ServerComposerModelPreference?> =
        composerPreferences.map { it[serverId] }

    override suspend fun getComposerModelPreference(serverId: String): ServerComposerModelPreference? =
        composerPreferences.value[serverId]

    override suspend fun getHiddenModelPreferences(serverId: String): List<ServerHiddenModelPreference> =
        hiddenModels.value.filter { it.serverId == serverId }

    override suspend fun upsertServer(server: ServerConfig) {
        upsertFailure?.let { throw it }
        state.value = if (state.value.any { it.id == server.id }) {
            state.value.map { if (it.id == server.id) server else it }
        } else {
            state.value + server
        }
    }

    override suspend fun reorderServers(orderedIds: List<String>) {
        state.value = reorderServersByIds(state.value, orderedIds)
    }

    override suspend fun migrateLegacyDefaultServer() = Unit

    override suspend fun setComposerModelPreference(preference: ServerComposerModelPreference) {
        composerPreferences.value = composerPreferences.value + (preference.serverId to preference)
    }

    override suspend fun setModelHidden(serverId: String, providerId: String, modelId: String, hidden: Boolean) {
        hiddenModels.value = hiddenModels.value.filterNot {
            it.serverId == serverId && it.providerId == providerId && it.modelId == modelId
        } + if (hidden) listOf(ServerHiddenModelPreference(serverId, providerId, modelId)) else emptyList()
    }

    override suspend fun deleteServer(serverId: String) {
        state.value = state.value.filterNot { it.id == serverId }
    }
}

private class ConnectionCacheHealthProbeFactory : OpenCodeHealthProbeFactory {
    var probeCount = 0
        private set

    override fun create(server: ServerConfig, password: String?, effectiveBaseUrl: String): OpenCodeHealthProbe =
        OpenCodeHealthProbe {
            probeCount += 1
            ServerHealthDto(healthy = true, version = "test")
        }
}

private class ConnectionCacheFrpcClient(
    private val localPort: Int,
) : FrpcStcpVisitorClient {
    var currentControlEpoch = 1L
    private var desiredRevision = 0L
    private var visitorName: String? = null

    override suspend fun startSession(config: FrpcSessionConfig): String = "session"

    override suspend fun ensureVisitor(
        sessionId: String,
        visitor: FrpcStcpVisitorConfig,
    ): FrpcEnsureVisitorResult {
        desiredRevision += 1
        visitorName = visitor.name
        return FrpcEnsureVisitorResult(bindPort = localPort, desiredRevision = desiredRevision)
    }

    override suspend fun waitVisitorReady(
        sessionId: String,
        visitorName: String,
        desiredRevision: Long,
        timeoutMillis: Long,
    ): FrpcVisitorReadyResult = FrpcVisitorReadyResult(
        name = visitorName,
        desiredRevision = desiredRevision,
        phase = "ready",
        listenerBound = true,
        boundControlEpoch = currentControlEpoch,
    )

    override suspend fun stopVisitor(sessionId: String, visitorName: String) = Unit

    override suspend fun stopSession(sessionId: String, timeoutMillis: Long): FrpcStopSessionResult =
        FrpcStopSessionResult(sessionId = sessionId, phase = "closed")

    override suspend fun getState(sessionId: String): FrpcStcpVisitorState = FrpcStcpVisitorState(
        sessionId = sessionId,
        phase = "open",
        controlEpoch = currentControlEpoch,
        visitors = visitorName?.let { name ->
            mapOf(
                name to FrpcVisitorRuntimeState(
                    desiredRevision = desiredRevision,
                    phase = "ready",
                    listenerBound = true,
                    boundControlEpoch = currentControlEpoch,
                ),
            )
        }.orEmpty(),
    )
}
