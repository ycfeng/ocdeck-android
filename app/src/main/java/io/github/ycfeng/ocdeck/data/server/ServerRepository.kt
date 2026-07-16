package io.github.ycfeng.ocdeck.data.server

import io.github.ycfeng.ocdeck.core.network.OpenCodeApiFactory
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailure
import io.github.ycfeng.ocdeck.core.network.OpenCodeFailureClassifier
import io.github.ycfeng.ocdeck.core.network.OpenCodeRequestException
import io.github.ycfeng.ocdeck.core.network.FrpcStcpConfigLease
import io.github.ycfeng.ocdeck.core.network.FrpcStcpConfigLeaseExpiredException
import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorCredentials
import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorGeneration
import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorManager
import io.github.ycfeng.ocdeck.core.network.ServerHealthDto
import io.github.ycfeng.ocdeck.core.network.SessionMessagesTransport
import io.github.ycfeng.ocdeck.core.network.SseConnection
import io.github.ycfeng.ocdeck.core.network.SseConnectionProvider
import io.github.ycfeng.ocdeck.core.network.SshTunnelCredentials
import io.github.ycfeng.ocdeck.core.network.SshTunnelGenerationSupersededException
import io.github.ycfeng.ocdeck.core.network.SshHostKeyPinUnavailableException
import io.github.ycfeng.ocdeck.core.network.SshTunnelManager
import io.github.ycfeng.ocdeck.core.security.CredentialStore
import io.github.ycfeng.ocdeck.core.security.SshPrivateKeyLimits
import io.github.ycfeng.ocdeck.domain.model.PromptModelSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

interface ServerEditorRepository {
    suspend fun getServer(serverId: String): ServerConfig

    suspend fun addServer(
        baseUrl: String,
        name: String,
        username: String?,
        password: String?,
        sshTunnel: NewServerSshTunnel? = null,
        frpcStcpVisitor: NewServerFrpcStcpVisitor? = null,
    ): ServerConfig

    suspend fun updateServer(
        serverId: String,
        baseUrl: String,
        name: String,
        username: String?,
        password: String?,
        sshTunnel: NewServerSshTunnel? = null,
        frpcStcpVisitor: NewServerFrpcStcpVisitor? = null,
    ): ServerConfig
}

interface ServerRuntimeInvalidator {
    suspend fun invalidate(
        serverId: String,
        minimumConfigEpoch: Long,
        ssh: Boolean,
        stcp: Boolean,
    )
}

interface OpenCodeServerRepository {
    suspend fun getConnection(serverId: String): ServerConnection

    suspend fun getHiddenModelPreferences(serverId: String): List<ServerHiddenModelPreference>

    suspend fun setModelHidden(serverId: String, providerId: String, modelId: String, hidden: Boolean)
}

class ServerRepository(
    private val preferencesStore: ServerConfigStore,
    private val credentialStore: CredentialStore,
    private val apiFactory: OpenCodeApiFactory,
    private val sshTunnelManager: SshTunnelManager,
    private val frpcStcpVisitorManager: FrpcStcpVisitorManager,
    runtimeInvalidator: ServerRuntimeInvalidator? = null,
) : SseConnectionProvider, ServerEditorRepository, OpenCodeServerRepository {
    private val runtimeInvalidator = runtimeInvalidator ?: DefaultServerRuntimeInvalidator(
        sshTunnelManager = sshTunnelManager,
        frpcStcpVisitorManager = frpcStcpVisitorManager,
    )
    private val serverConfigMutationLock = Mutex()
    private val connectionConfigLocks = ConcurrentHashMap<String, Mutex>()
    private val connectionConfigEpochs = ConcurrentHashMap<String, AtomicLong>()

    fun observeServers(): Flow<List<ServerConfig>> = preferencesStore.servers

    suspend fun saveServerOrder(orderedIds: List<String>) {
        preferencesStore.reorderServers(orderedIds)
    }

    fun observeComposerModelPreference(serverId: String): Flow<ServerComposerModelPreference?> =
        preferencesStore.observeComposerModelPreference(serverId)

    suspend fun getComposerModelPreference(serverId: String): ServerComposerModelPreference? =
        preferencesStore.getComposerModelPreference(serverId)

    override suspend fun getHiddenModelPreferences(serverId: String): List<ServerHiddenModelPreference> =
        preferencesStore.getHiddenModelPreferences(serverId)

    suspend fun saveComposerModelPreference(serverId: String, selection: PromptModelSelection) {
        preferencesStore.setComposerModelPreference(
            ServerComposerModelPreference(
                serverId = serverId,
                providerId = selection.providerId,
                modelId = selection.modelId,
                variant = selection.variant,
            ),
        )
    }

    override suspend fun setModelHidden(serverId: String, providerId: String, modelId: String, hidden: Boolean) {
        preferencesStore.setModelHidden(serverId, providerId, modelId, hidden)
    }

    override suspend fun getServer(serverId: String): ServerConfig = preferencesStore.getServers()
        .firstOrNull { it.id == serverId }
        ?: throw OpenCodeRequestException(OpenCodeFailure.OperationRejected())

    override suspend fun getConnection(serverId: String): ServerConnection {
        while (true) {
            val snapshot = connectionConfigLock(serverId).withLock {
                val persistedServer = getServer(serverId)
                val server = persistedServer.copy(baseUrl = normalizeServerBaseUrl(persistedServer.baseUrl))
                val password = server.passwordKey?.let { credentialStore.getSecret(it) }
                val sshConfig = server.sshTunnel
                val stcpConfig = server.frpcStcpVisitor
                requireSingleTunnel(sshConfig, stcpConfig)
                ConnectionConfigSnapshot(
                    server = server,
                    password = password,
                    sshCredentials = sshConfig?.toCredentials(),
                    stcpCredentials = stcpConfig?.toCredentials(),
                    stcpConfigLease = stcpConfig?.let { frpcStcpVisitorManager.captureConfigLease(server.id) },
                    configEpoch = connectionConfigEpoch(server.id),
                )
            }

            try {
                if (prepareSshHostKey(snapshot)) continue
                val connection = openConnection(snapshot)
                val stcpTransportCurrent = snapshot.stcpConfigLease?.let {
                    val generation = connection.stcpGeneration
                    val controlEpoch = connection.stcpControlEpoch
                    generation != null && controlEpoch != null &&
                        frpcStcpVisitorManager.isReadyTransportCurrent(generation, controlEpoch)
                } ?: true
                val isCurrent = connectionConfigLock(serverId).withLock {
                    val stcpLeaseCurrent = snapshot.stcpConfigLease?.let { lease ->
                        frpcStcpVisitorManager.isConfigLeaseCurrent(lease)
                    } ?: true
                    connectionConfigEpoch(serverId) == snapshot.configEpoch && stcpLeaseCurrent && stcpTransportCurrent
                }
                if (isCurrent) return connection
            } catch (_: FrpcStcpConfigLeaseExpiredException) {
                // The server changed while this snapshot was opening. Retry with the latest config.
            } catch (_: SshTunnelGenerationSupersededException) {
                // The SSH config epoch changed while this snapshot was opening. Retry with the latest config.
            }
        }
    }

    override suspend fun getSseConnection(serverId: String): SseConnection = getConnection(serverId).let { connection ->
        SseConnection(
            server = connection.server,
            password = connection.password,
            effectiveBaseUrl = connection.effectiveBaseUrl,
            transportIdentity = connection.transportIdentity,
        )
    }

    suspend fun migrateLegacyDefaultServer() = preferencesStore.migrateLegacyDefaultServer()

    suspend fun deleteServer(server: ServerConfig) {
        val aliasesToCleanup = serverConfigMutationLock.withLock {
            connectionConfigLock(server.id).withLock {
                val persisted = preferencesStore.getServers().firstOrNull { it.id == server.id } ?: server
                currentCoroutineContext().ensureActive()
                val invalidateRuntime: suspend () -> Unit = {
                    val minimumConfigEpoch = bumpConnectionConfigEpoch(server.id)
                    runtimeInvalidator.invalidate(
                        serverId = server.id,
                        minimumConfigEpoch = minimumConfigEpoch,
                        ssh = true,
                        stcp = true,
                    )
                }
                deleteServerInCommitSegment(
                    previous = persisted,
                    onCommitted = invalidateRuntime,
                    onUnknown = invalidateRuntime,
                )
                persisted.secretKeys()
            }
        }
        cleanupUnreferencedAliases(aliasesToCleanup)
    }

    override suspend fun addServer(
        baseUrl: String,
        name: String,
        username: String?,
        password: String?,
        sshTunnel: NewServerSshTunnel?,
        frpcStcpVisitor: NewServerFrpcStcpVisitor?,
    ): ServerConfig {
        val normalizedBaseUrl = normalizeServerBaseUrl(baseUrl)
        val id = UUID.randomUUID().toString()
        return serverConfigMutationLock.withLock {
            commitCandidateServer(
                previous = null,
                build = { candidates ->
                    val passwordKey = password
                        ?.takeIf { it.isNotBlank() }
                        ?.let { candidates.write(CredentialPurpose.OpenCodePassword, it) }
                    val sshTunnelConfig = sshTunnel
                        ?.takeIf { it.enabled }
                        ?.toConfig(current = null, candidates = candidates)
                    val frpcStcpVisitorConfig = frpcStcpVisitor
                        ?.takeIf { it.enabled }
                        ?.toConfig(current = null, candidates = candidates)
                    requireSingleTunnel(sshTunnelConfig, frpcStcpVisitorConfig)
                    ServerConfig(
                        id = id,
                        name = name.takeIf { it.isNotBlank() } ?: normalizedBaseUrl.toDisplayName(),
                        baseUrl = normalizedBaseUrl,
                        username = username?.takeIf { it.isNotBlank() },
                        passwordKey = passwordKey,
                        sshTunnel = sshTunnelConfig,
                        frpcStcpVisitor = frpcStcpVisitorConfig,
                    )
                },
                onCommitted = { committed -> connectionConfigEpoch(committed.id) },
                onUnknown = { uncertain ->
                    val minimumConfigEpoch = bumpConnectionConfigEpoch(uncertain.id)
                    runtimeInvalidator.invalidate(
                        serverId = uncertain.id,
                        minimumConfigEpoch = minimumConfigEpoch,
                        ssh = uncertain.sshTunnel != null,
                        stcp = uncertain.frpcStcpVisitor != null,
                    )
                },
            )
        }
    }

    override suspend fun updateServer(
        serverId: String,
        baseUrl: String,
        name: String,
        username: String?,
        password: String?,
        sshTunnel: NewServerSshTunnel?,
        frpcStcpVisitor: NewServerFrpcStcpVisitor?,
    ): ServerConfig {
        val mutation = serverConfigMutationLock.withLock {
            connectionConfigLock(serverId).withLock {
                val current = getServer(serverId)
                val normalizedBaseUrl = normalizeServerBaseUrl(baseUrl)
                val passwordUpdated = !password.isNullOrBlank()
                val stcpCredentialsUpdated = frpcStcpVisitor
                    ?.takeIf { it.enabled }
                    ?.let { it.authToken.isNotBlank() || it.secretKey.isNotBlank() }
                    ?: false
                val sshCredentialsUpdated = sshTunnel
                    ?.takeIf { it.enabled }
                    ?.let {
                        it.password.isNotBlank() ||
                            it.privateKey.isNotBlank() ||
                            it.passphrase.isNotBlank() ||
                            !it.hostFingerprint.isNullOrBlank()
                    }
                    ?: false
                val invalidateRuntime: suspend (ServerConfig) -> Unit = { candidate ->
                    val minimumConfigEpoch = bumpConnectionConfigEpoch(candidate.id)
                    val sshConnectionChanged =
                        current.baseUrl != candidate.baseUrl ||
                            current.sshTunnel != candidate.sshTunnel ||
                            sshCredentialsUpdated
                    val stcpConnectionChanged = hasStcpConnectionChanged(
                        current = current,
                        updated = candidate,
                        passwordUpdated = passwordUpdated,
                        stcpCredentialsUpdated = stcpCredentialsUpdated,
                    )
                    runtimeInvalidator.invalidate(
                        serverId = candidate.id,
                        minimumConfigEpoch = minimumConfigEpoch,
                        ssh = sshConnectionChanged,
                        stcp = stcpConnectionChanged,
                    )
                }
                val updated = commitCandidateServer(
                    previous = current,
                    build = { candidates ->
                        val passwordKey = password
                            ?.takeIf { it.isNotBlank() }
                            ?.let { candidates.write(CredentialPurpose.OpenCodePassword, it) }
                            ?: current.passwordKey
                        val sshTunnelConfig = sshTunnel
                            ?.takeIf { it.enabled }
                            ?.toConfig(current.sshTunnel, candidates)
                        val frpcStcpVisitorConfig = frpcStcpVisitor
                            ?.takeIf { it.enabled }
                            ?.toConfig(current.frpcStcpVisitor, candidates)
                        requireSingleTunnel(sshTunnelConfig, frpcStcpVisitorConfig)
                        current.copy(
                            name = name.takeIf { it.isNotBlank() } ?: normalizedBaseUrl.toDisplayName(),
                            baseUrl = normalizedBaseUrl,
                            username = username?.takeIf { it.isNotBlank() },
                            passwordKey = passwordKey,
                            sshTunnel = sshTunnelConfig,
                            frpcStcpVisitor = frpcStcpVisitorConfig,
                        )
                    },
                    onCommitted = invalidateRuntime,
                    onUnknown = invalidateRuntime,
                )
                CommittedServerMutation(
                    server = updated,
                    aliasesToCleanup = current.secretKeys().toSet() - updated.secretKeys().toSet(),
                )
            }
        }
        cleanupUnreferencedAliases(mutation.aliasesToCleanup)
        return mutation.server
    }

    suspend fun checkHealth(server: ServerConfig): Result<ServerHealth> = catching {
        val connection = getConnection(server.id)
        val health = resolveServerHealth(connection.readinessHealth, connection.api::getGlobalHealth)
        if (health.healthy == false) {
            throw OpenCodeRequestException(OpenCodeFailure.OperationRejected())
        }
        ServerHealth(version = health.version)
    }

    private suspend fun <T> catching(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (throwable: Throwable) {
        Result.failure(OpenCodeFailureClassifier.toRequestException(throwable))
    }

    private suspend fun openConnection(snapshot: ConnectionConfigSnapshot): ServerConnection {
        val server = snapshot.server
        val sshConfig = server.sshTunnel
        val tunnel = sshConfig?.let { config ->
            sshTunnelManager.ensureTunnel(
                server = server,
                credentials = requireNotNull(snapshot.sshCredentials),
                configEpoch = snapshot.configEpoch,
            )
        }
        val stcpResult = server.frpcStcpVisitor?.let {
            frpcStcpVisitorManager.ensureReadyVisitor(
                server = server,
                credentials = requireNotNull(snapshot.stcpCredentials),
                openCodePassword = snapshot.password,
                configLease = requireNotNull(snapshot.stcpConfigLease),
            )
        }
        val effectiveBaseUrl = tunnel?.effectiveBaseUrl ?: stcpResult?.tunnel?.effectiveBaseUrl ?: server.baseUrl
        val connectionClient = apiFactory.createConnectionClient(server, snapshot.password, effectiveBaseUrl)
        return ServerConnection(
            server = server,
            password = snapshot.password,
            effectiveBaseUrl = effectiveBaseUrl,
            api = connectionClient.api,
            providerOAuthApi = connectionClient.providerOAuthApi,
            stcpGeneration = stcpResult?.generation,
            stcpControlEpoch = stcpResult?.tunnel?.controlEpoch,
            transportIdentity = ServerTransportIdentity(
                configEpoch = snapshot.configEpoch,
                stcpGeneration = stcpResult?.generation,
                stcpControlEpoch = stcpResult?.tunnel?.controlEpoch,
            ),
            readinessHealth = stcpResult?.readinessHealth,
            sessionMessagesTransport = connectionClient.sessionMessagesTransport,
        )
    }

    /**
     * Returns true when a new TOFU pin was persisted and the caller must recapture the config.
     */
    private suspend fun prepareSshHostKey(snapshot: ConnectionConfigSnapshot): Boolean {
        val config = snapshot.server.sshTunnel ?: return false
        if (!config.hostFingerprintKey.isNullOrBlank()) {
            if (snapshot.sshCredentials?.hostFingerprint.isNullOrBlank()) {
                throw SshHostKeyPinUnavailableException()
            }
            return false
        }
        if (config.hostKeyPolicy != SshHostKeyPolicy.AcceptNew) return false

        val discoveredFingerprint = sshTunnelManager.discoverHostKey(snapshot.server)
        persistAcceptedSshFingerprint(snapshot.server, config, discoveredFingerprint)
        return true
    }

    internal suspend fun persistAcceptedSshFingerprint(
        snapshotServer: ServerConfig,
        snapshotConfig: ServerSshTunnelConfig,
        hostFingerprint: String,
    ) {
        require(hostFingerprint.isNotBlank()) { "SSH host fingerprint is required" }
        val aliasesToCleanup = serverConfigMutationLock.withLock {
            connectionConfigLock(snapshotServer.id).withLock connectionLock@{
                val current = getServer(snapshotServer.id)
                if (current.sshTunnel != snapshotConfig) {
                    return@connectionLock emptySet()
                }
                val updated = commitCandidateServer(
                    previous = current,
                    build = { candidates ->
                        val hostFingerprintKey = candidates.write(CredentialPurpose.SshHostFingerprint, hostFingerprint)
                        current.copy(
                            sshTunnel = snapshotConfig.copy(hostFingerprintKey = hostFingerprintKey),
                        )
                    },
                    onCommitted = { committed ->
                        val minimumConfigEpoch = bumpConnectionConfigEpoch(committed.id)
                        runtimeInvalidator.invalidate(
                            serverId = committed.id,
                            minimumConfigEpoch = minimumConfigEpoch,
                            ssh = true,
                            stcp = false,
                        )
                    },
                    onUnknown = { uncertain ->
                        val minimumConfigEpoch = bumpConnectionConfigEpoch(uncertain.id)
                        runtimeInvalidator.invalidate(
                            serverId = uncertain.id,
                            minimumConfigEpoch = minimumConfigEpoch,
                            ssh = true,
                            stcp = false,
                        )
                    },
                )
                current.secretKeys().toSet() - updated.secretKeys().toSet()
            }
        }
        cleanupUnreferencedAliases(aliasesToCleanup)
    }

    private fun connectionConfigLock(serverId: String): Mutex =
        connectionConfigLocks.computeIfAbsent(serverId) { Mutex() }

    private fun connectionConfigEpoch(serverId: String): Long =
        connectionConfigEpochs.computeIfAbsent(serverId) { AtomicLong(1L) }.get()

    private fun bumpConnectionConfigEpoch(serverId: String): Long =
        connectionConfigEpochs.computeIfAbsent(serverId) { AtomicLong(1L) }.incrementAndGet()

    private fun String.toDisplayName(): String = removePrefix("http://")
        .removePrefix("https://")
        .trimEnd('/')
        .ifBlank { "OpenCode Server" }

    private suspend fun ServerSshTunnelConfig.toCredentials(): SshTunnelCredentials {
        val privateKey = privateKeyKey?.let { credentialStore.getSecret(it) }
        privateKey?.let(SshPrivateKeyLimits::requireValidUtf8Size)
        return SshTunnelCredentials(
            password = passwordKey?.let { credentialStore.getSecret(it) },
            privateKey = privateKey,
            passphrase = passphraseKey?.let { credentialStore.getSecret(it) },
            hostFingerprint = hostFingerprintKey?.let { key ->
                credentialStore.getSecret(key) ?: throw SshHostKeyPinUnavailableException()
            },
        )
    }

    private suspend fun ServerFrpcStcpVisitorConfig.toCredentials(): FrpcStcpVisitorCredentials = FrpcStcpVisitorCredentials(
        authToken = authTokenKey?.let { credentialStore.getSecret(it) },
        secretKey = credentialStore.getSecret(secretKeyKey),
    )

    private suspend fun NewServerSshTunnel.toConfig(
        current: ServerSshTunnelConfig?,
        candidates: CandidateCredentialWriter,
    ): ServerSshTunnelConfig {
        require(host.isNotBlank()) { "SSH host is required" }
        require(username.isNotBlank()) { "SSH username is required" }
        require(port in 1..65_535) { "SSH port is invalid" }
        require(localPort in 1..65_535) { "Local forwarding port is invalid" }
        require(connectTimeoutSeconds > 0) { "Connection timeout must be greater than 0" }
        require(keepAliveSeconds > 0) { "KeepAlive interval must be greater than 0" }
        if (authMethod.usesPrivateKey && privateKey.isNotBlank()) {
            SshPrivateKeyLimits.requireValidUtf8Size(privateKey)
        }
        val normalizedHost = host.trim()
        val sameHostEndpoint = current != null &&
            current.port == port &&
            current.host.trim().equals(normalizedHost, ignoreCase = true)
        val providedHostFingerprint = hostFingerprint
            ?.takeIf { hostKeyPolicy == SshHostKeyPolicy.Fingerprint && it.isNotBlank() }
        val retainedHostFingerprintKey = current?.hostFingerprintKey?.takeIf { sameHostEndpoint }
        if (
            hostKeyPolicy == SshHostKeyPolicy.Fingerprint &&
            providedHostFingerprint == null &&
            retainedHostFingerprintKey == null
        ) {
            throw IllegalArgumentException("SSH host fingerprint is required")
        }
        val passwordKey = if (authMethod.usesPassword) {
            password.takeIf { it.isNotBlank() }
                ?.let { candidates.write(CredentialPurpose.SshPassword, it) }
                ?: current?.passwordKey
                ?: throw IllegalArgumentException("SSH password is required")
        } else {
            null
        }
        val hasNewPrivateKey = privateKey.isNotBlank()
        val privateKeyKey = if (authMethod.usesPrivateKey) {
            privateKey.takeIf { it.isNotBlank() }
                ?.let { candidates.write(CredentialPurpose.SshPrivateKey, it) }
                ?: current?.privateKeyKey
                ?: throw IllegalArgumentException("SSH private key is required")
        } else {
            null
        }
        val passphraseKey = if (authMethod.usesPrivateKey) {
            passphrase.takeIf { it.isNotBlank() }
                ?.let { candidates.write(CredentialPurpose.SshPassphrase, it) }
                ?: current?.passphraseKey
        } else {
            null
        }
        val hostFingerprintKey = when (hostKeyPolicy) {
            SshHostKeyPolicy.AcceptNew -> retainedHostFingerprintKey
            SshHostKeyPolicy.Fingerprint -> providedHostFingerprint
                ?.let { candidates.write(CredentialPurpose.SshHostFingerprint, it) }
                ?: retainedHostFingerprintKey
        }
        return ServerSshTunnelConfig(
            host = normalizedHost,
            port = port,
            username = username.trim(),
            authMethod = authMethod,
            passwordKey = passwordKey,
            privateKeyKey = privateKeyKey,
            privateKeyFileName = if (authMethod.usesPrivateKey) {
                if (hasNewPrivateKey) privateKeyFileName?.takeIf { it.isNotBlank() } else current?.privateKeyFileName
            } else {
                null
            },
            passphraseKey = passphraseKey,
            localPort = localPort,
            connectTimeoutSeconds = connectTimeoutSeconds,
            keepAliveSeconds = keepAliveSeconds,
            hostKeyPolicy = hostKeyPolicy,
            hostFingerprintKey = hostFingerprintKey,
        )
    }

    private suspend fun NewServerFrpcStcpVisitor.toConfig(
        current: ServerFrpcStcpVisitorConfig?,
        candidates: CandidateCredentialWriter,
    ): ServerFrpcStcpVisitorConfig {
        require(serverAddr.isNotBlank()) { "frps server address is required" }
        require(serverPort in 1..65_535) { "frps server port is invalid" }
        require(serverName.isNotBlank()) { "STCP server name is required" }
        require(bindPort in 1..65_535) { "STCP local bind port is invalid" }
        val authTokenKey = authToken.takeIf { it.isNotBlank() }
            ?.let { candidates.write(CredentialPurpose.FrpcAuthToken, it) }
            ?: current?.authTokenKey
        val secretKeyKey = secretKey.takeIf { it.isNotBlank() }
            ?.let { candidates.write(CredentialPurpose.FrpcSecretKey, it) }
            ?: current?.secretKeyKey
            ?: throw IllegalArgumentException("STCP secret key is required")
        return ServerFrpcStcpVisitorConfig(
            serverAddr = serverAddr.trim(),
            serverPort = serverPort,
            authTokenKey = authTokenKey,
            user = user?.takeIf { it.isNotBlank() }?.trim(),
            serverUser = serverUser?.takeIf { it.isNotBlank() }?.trim(),
            serverName = serverName.trim(),
            secretKeyKey = secretKeyKey,
            bindPort = bindPort,
            transportProtocol = transportProtocol,
            wireProtocol = wireProtocol,
        )
    }

    private fun ServerConfig.secretKeys(): List<String> = listOfNotNull(
        passwordKey,
        sshTunnel?.passwordKey,
        sshTunnel?.privateKeyKey,
        sshTunnel?.passphraseKey,
        sshTunnel?.hostFingerprintKey,
        frpcStcpVisitor?.authTokenKey,
        frpcStcpVisitor?.secretKeyKey,
    )

    private suspend fun commitCandidateServer(
        previous: ServerConfig?,
        build: suspend (CandidateCredentialWriter) -> ServerConfig,
        onCommitted: suspend (ServerConfig) -> Unit,
        onUnknown: suspend (ServerConfig) -> Unit,
    ): ServerConfig {
        val candidates = CandidateCredentialWriter()
        val target = try {
            build(candidates)
        } catch (failure: Throwable) {
            rollbackCandidateAliases(candidates.aliases)
            throw failure
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            rollbackCandidateAliases(candidates.aliases)
            throw failure
        }
        var unknownRuntimeInvalidationFailed = false
        val outcome = withContext(NonCancellable) {
            when (val commitOutcome = upsertAndConfirm(previous, target)) {
                ServerConfigCommitOutcome.Committed -> {
                    onCommitted(target)
                    commitOutcome
                }
                is ServerConfigCommitOutcome.NotCommitted -> {
                    rollbackCandidateAliases(candidates.aliases)
                    commitOutcome
                }
                ServerConfigCommitOutcome.Unknown -> {
                    try {
                        onUnknown(target)
                    } catch (_: Exception) {
                        unknownRuntimeInvalidationFailed = true
                    }
                    commitOutcome
                }
            }
        }
        return when (outcome) {
            ServerConfigCommitOutcome.Committed -> target
            is ServerConfigCommitOutcome.NotCommitted -> throw outcome.failure
            // The config may already reference these aliases, so an unknown outcome must retain them.
            ServerConfigCommitOutcome.Unknown -> throw ServerConfigMutationOutcomeUnknownException(
                runtimeInvalidationFailed = unknownRuntimeInvalidationFailed,
            )
        }
    }

    private suspend fun upsertAndConfirm(
        previous: ServerConfig?,
        target: ServerConfig,
    ): ServerConfigCommitOutcome {
        var failure: Exception? = null
        try {
            preferencesStore.upsertServer(target)
        } catch (exception: Exception) {
            failure = exception
        }
        val observed = try {
            preferencesStore.getServers().firstOrNull { it.id == target.id }
        } catch (_: Exception) {
            return ServerConfigCommitOutcome.Unknown
        }
        return when {
            observed == target -> ServerConfigCommitOutcome.Committed
            observed == previous -> ServerConfigCommitOutcome.NotCommitted(
                failure ?: ServerConfigCommitRejectedException(),
            )
            else -> ServerConfigCommitOutcome.Unknown
        }
    }

    private suspend fun deleteServerInCommitSegment(
        previous: ServerConfig,
        onCommitted: suspend () -> Unit,
        onUnknown: suspend () -> Unit,
    ) {
        var unknownRuntimeInvalidationFailed = false
        val outcome = withContext(NonCancellable) {
            when (val commitOutcome = deleteAndConfirm(previous)) {
                ServerConfigCommitOutcome.Committed -> {
                    onCommitted()
                    commitOutcome
                }
                is ServerConfigCommitOutcome.NotCommitted -> commitOutcome
                ServerConfigCommitOutcome.Unknown -> {
                    try {
                        onUnknown()
                    } catch (_: Exception) {
                        unknownRuntimeInvalidationFailed = true
                    }
                    commitOutcome
                }
            }
        }
        when (outcome) {
            ServerConfigCommitOutcome.Committed -> Unit
            is ServerConfigCommitOutcome.NotCommitted -> throw outcome.failure
            ServerConfigCommitOutcome.Unknown -> throw ServerConfigMutationOutcomeUnknownException(
                runtimeInvalidationFailed = unknownRuntimeInvalidationFailed,
            )
        }
    }

    private suspend fun deleteAndConfirm(previous: ServerConfig): ServerConfigCommitOutcome {
        var failure: Exception? = null
        try {
            preferencesStore.deleteServer(previous.id)
        } catch (exception: Exception) {
            failure = exception
        }
        val observed = try {
            preferencesStore.getServers().firstOrNull { it.id == previous.id }
        } catch (_: Exception) {
            return ServerConfigCommitOutcome.Unknown
        }
        return when {
            observed == null -> ServerConfigCommitOutcome.Committed
            observed == previous -> ServerConfigCommitOutcome.NotCommitted(
                failure ?: ServerConfigCommitRejectedException(),
            )
            else -> ServerConfigCommitOutcome.Unknown
        }
    }

    private suspend fun rollbackCandidateAliases(aliases: Collection<String>) {
        if (aliases.isEmpty()) return
        withContext(NonCancellable) {
            aliases.forEach { alias ->
                try {
                    credentialStore.removeSecret(alias)
                } catch (_: Exception) {
                    // The primary transaction failure remains authoritative.
                }
            }
        }
    }

    private suspend fun cleanupUnreferencedAliases(aliases: Collection<String>) {
        if (aliases.isEmpty()) return
        withContext(NonCancellable) {
            val referencedAliases = try {
                preferencesStore.getServers().flatMap { it.secretKeys() }.toSet()
            } catch (_: Exception) {
                return@withContext
            }
            aliases.asSequence()
                .distinct()
                .filterNot(referencedAliases::contains)
                .forEach { alias ->
                    try {
                        credentialStore.removeSecret(alias)
                    } catch (_: Exception) {
                        // Configuration is already committed; stale alias cleanup is best effort.
                    }
                }
        }
    }

    private inner class CandidateCredentialWriter {
        val aliases = linkedSetOf<String>()

        suspend fun write(purpose: CredentialPurpose, secret: String): String {
            val alias = "server-credential-candidate-${purpose.aliasSegment}-${UUID.randomUUID()}"
            aliases += alias
            credentialStore.putSecret(alias, secret)
            return alias
        }
    }

    private enum class CredentialPurpose(val aliasSegment: String) {
        OpenCodePassword("open-code-password"),
        SshPassword("ssh-password"),
        SshPrivateKey("ssh-private-key"),
        SshPassphrase("ssh-passphrase"),
        SshHostFingerprint("ssh-host-fingerprint"),
        FrpcAuthToken("frpc-auth-token"),
        FrpcSecretKey("frpc-secret-key"),
    }

    private fun requireSingleTunnel(
        sshTunnel: ServerSshTunnelConfig?,
        frpcStcpVisitor: ServerFrpcStcpVisitorConfig?,
    ) {
        require(sshTunnel == null || frpcStcpVisitor == null) { "SSH tunnel and frpc STCP visitor cannot both be enabled" }
    }

}

internal class ServerConfigMutationOutcomeUnknownException(
    val runtimeInvalidationFailed: Boolean = false,
) : IllegalStateException(
    if (runtimeInvalidationFailed) {
        "Server configuration mutation outcome is unknown and runtime invalidation failed"
    } else {
        "Server configuration mutation outcome is unknown"
    },
)

internal class ServerConfigCommitRejectedException : IllegalStateException()

private sealed interface ServerConfigCommitOutcome {
    data object Committed : ServerConfigCommitOutcome

    class NotCommitted(val failure: Exception) : ServerConfigCommitOutcome

    data object Unknown : ServerConfigCommitOutcome
}

private class DefaultServerRuntimeInvalidator(
    private val sshTunnelManager: SshTunnelManager,
    private val frpcStcpVisitorManager: FrpcStcpVisitorManager,
) : ServerRuntimeInvalidator {
    override suspend fun invalidate(
        serverId: String,
        minimumConfigEpoch: Long,
        ssh: Boolean,
        stcp: Boolean,
    ) {
        if (ssh) sshTunnelManager.closeTunnel(serverId, minimumConfigEpoch)
        if (stcp) frpcStcpVisitorManager.invalidateConfiguration(serverId)
    }
}

class NewServerSshTunnel(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val username: String,
    val authMethod: SshAuthMethod,
    val password: String,
    val privateKey: String,
    val privateKeyFileName: String?,
    val passphrase: String,
    val localPort: Int,
    val connectTimeoutSeconds: Int,
    val keepAliveSeconds: Int,
    val hostKeyPolicy: SshHostKeyPolicy,
    val hostFingerprint: String?,
)

class NewServerFrpcStcpVisitor(
    val enabled: Boolean,
    val serverAddr: String,
    val serverPort: Int,
    val authToken: String,
    val user: String?,
    val serverUser: String?,
    val serverName: String,
    val secretKey: String,
    val bindPort: Int,
    val transportProtocol: FrpcTransportProtocol,
    val wireProtocol: FrpcWireProtocol,
)

data class ServerConnection(
    val server: ServerConfig,
    val password: String?,
    val effectiveBaseUrl: String,
    val api: io.github.ycfeng.ocdeck.core.network.OpenCodeApi,
    val providerOAuthApi: io.github.ycfeng.ocdeck.core.network.OpenCodeApi = api,
    val stcpGeneration: FrpcStcpVisitorGeneration? = null,
    val stcpControlEpoch: Long? = null,
    val transportIdentity: ServerTransportIdentity = ServerTransportIdentity(configEpoch = 1L),
    val readinessHealth: ServerHealthDto? = null,
    val sessionMessagesTransport: SessionMessagesTransport = SessionMessagesTransport.Unavailable,
) {
    override fun toString(): String =
        "ServerConnection(serverId=<redacted>, password=${if (password == null) "null" else "<redacted>"}, " +
            "effectiveBaseUrl=<redacted>, stcpGenerationPresent=${stcpGeneration != null}, " +
            "stcpControlEpochPresent=${stcpControlEpoch != null}, transportIdentity=$transportIdentity, " +
            "readinessHealthPresent=${readinessHealth != null}, " +
            "providerOAuthApiPresent=true, " +
            "sessionMessagesTransportAvailable=${sessionMessagesTransport !== SessionMessagesTransport.Unavailable})"
}

private class ConnectionConfigSnapshot(
    val server: ServerConfig,
    val password: String?,
    val sshCredentials: SshTunnelCredentials?,
    val stcpCredentials: FrpcStcpVisitorCredentials?,
    val stcpConfigLease: FrpcStcpConfigLease?,
    val configEpoch: Long,
)

private class CommittedServerMutation(
    val server: ServerConfig,
    val aliasesToCleanup: Set<String>,
)

internal fun hasStcpConnectionChanged(
    current: ServerConfig,
    updated: ServerConfig,
    passwordUpdated: Boolean,
    stcpCredentialsUpdated: Boolean,
): Boolean {
    val hasStcpConfiguration = current.frpcStcpVisitor != null || updated.frpcStcpVisitor != null
    return hasStcpConfiguration && (
        current.baseUrl != updated.baseUrl ||
            current.username != updated.username ||
            current.passwordKey != updated.passwordKey ||
            passwordUpdated ||
            current.frpcStcpVisitor != updated.frpcStcpVisitor ||
            stcpCredentialsUpdated
        )
}

internal suspend fun resolveServerHealth(
    readinessHealth: ServerHealthDto?,
    freshHealth: suspend () -> ServerHealthDto,
): ServerHealthDto = readinessHealth ?: freshHealth()

private val SshAuthMethod.usesPassword: Boolean
    get() = this == SshAuthMethod.Password || this == SshAuthMethod.PasswordAndPrivateKey

private val SshAuthMethod.usesPrivateKey: Boolean
    get() = this == SshAuthMethod.PrivateKey || this == SshAuthMethod.PasswordAndPrivateKey
