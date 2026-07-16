package io.github.ycfeng.ocdeck.core.network

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerSshTunnelConfig
import io.github.ycfeng.ocdeck.data.server.SshAuthMethod
import io.github.ycfeng.ocdeck.data.server.SshHostKeyPolicy
import io.github.ycfeng.ocdeck.data.server.normalizeServerBaseUrl
import io.github.ycfeng.ocdeck.core.security.SshPrivateKeyLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.MessageDigest
import java.security.Security
import java.util.Base64

class SshTunnelManager internal constructor(
    private val sessionFactory: SshSessionFactory,
) {
    constructor() : this(JschSshSessionFactory)

    private val lock = Any()
    private val slots = mutableMapOf<String, ServerTunnelSlot>()

    suspend fun discoverHostKey(server: ServerConfig): String = withContext(Dispatchers.IO) {
        val config = requireNotNull(server.sshTunnel) { "SSH tunnel is not configured" }
        validateDiscoveryConfig(config)
        val repository = DiscoveringSshHostKeyRepository()
        val session = sessionFactory.create(config.username, config.host, config.port)

        try {
            session.setHostKeyRepository(repository)
            session.setConfig(STRICT_HOST_KEY_CHECKING, "yes")
            val connectFailure = try {
                session.connect(config.connectTimeoutSeconds.toTimeoutMillis())
                null
            } catch (exception: Exception) {
                exception
            }

            if (connectFailure == null) {
                throw SshHostKeyDiscoveryException()
            }
            if (connectFailure is CancellationException) {
                throw connectFailure
            }
            repository.discoveredFingerprint()
                ?: throw SshHostKeyDiscoveryException(connectFailure)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: SshHostKeyDiscoveryException) {
            throw failure
        } catch (exception: Exception) {
            throw SshHostKeyDiscoveryException(exception)
        } finally {
            session.disconnectSafely()
        }
    }

    suspend fun ensureTunnel(
        server: ServerConfig,
        credentials: SshTunnelCredentials,
        configEpoch: Long = INITIAL_CONFIG_EPOCH,
    ): SshTunnel = withContext(Dispatchers.IO) {
        require(configEpoch >= INITIAL_CONFIG_EPOCH) { "SSH config epoch is invalid" }
        ensureConfigEpochAccepted(server.id, configEpoch)
        val config = requireNotNull(server.sshTunnel) { "SSH tunnel is not configured" }
        validateConnectionConfig(config)
        val hostKeyRepository = pinnedRepository(config, credentials)
        validateAuthenticationCredentials(config, credentials)
        val remote = SshTunnelUrlResolver.remoteEndpoint(server.baseUrl)
        val signature = config.signature(server.baseUrl, credentials)
        val selection = selectTunnel(server.id, configEpoch, signature)
        selection.sessionsToDisconnect.forEach { it.disconnectSafely() }

        when (val action = selection.action) {
            is TunnelAction.Cached -> action.tunnel.toTunnel()
            is TunnelAction.Await -> {
                action.flight.completion.await()
                currentTunnel(server.id, configEpoch, signature)
                    ?: throw SshTunnelGenerationSupersededException(server.id)
            }
            is TunnelAction.Open -> {
                openAndPublish(
                    server = server,
                    config = config,
                    credentials = credentials,
                    remote = remote,
                    hostKeyRepository = hostKeyRepository,
                    flight = action.flight,
                )
            }
        }
    }

    fun closeTunnel(serverId: String, minimumConfigEpoch: Long) {
        require(minimumConfigEpoch >= INITIAL_CONFIG_EPOCH) { "SSH config epoch is invalid" }
        val sessionsToDisconnect = synchronized(lock) {
            val slot = slots.getOrPut(serverId, ::ServerTunnelSlot)
            if (minimumConfigEpoch <= slot.minimumConfigEpoch) {
                return@synchronized emptyList()
            }
            slot.minimumConfigEpoch = minimumConfigEpoch
            slot.generation += 1
            slot.signature = null
            buildList {
                slot.active?.let { active ->
                    add(active.session)
                    slot.active = null
                }
                slot.inFlight?.let { flight ->
                    invalidateFlightLocked(flight)
                    flight.session?.let(::add)
                    slot.predecessorFinished = flight.finished
                    slot.inFlight = null
                }
            }
        }
        sessionsToDisconnect.forEach { it.disconnectSafely() }
    }

    private fun selectTunnel(
        serverId: String,
        configEpoch: Long,
        signature: SshTunnelSignature,
    ): TunnelSelection {
        val sessionsToDisconnect = mutableListOf<SshSessionHandle>()
        val action = synchronized(lock) {
            val slot = slots.getOrPut(serverId, ::ServerTunnelSlot)
            if (configEpoch < slot.minimumConfigEpoch) {
                throw SshTunnelGenerationSupersededException(serverId)
            }
            if (configEpoch > slot.minimumConfigEpoch) {
                slot.minimumConfigEpoch = configEpoch
            }
            slot.active
                ?.takeIf {
                    it.configEpoch == configEpoch &&
                        it.signature == signature &&
                        it.session.isConnected
                }
                ?.let { return@synchronized TunnelAction.Cached(it) }

            slot.active?.let { active ->
                sessionsToDisconnect += active.session
                slot.active = null
            }

            val currentFlight = slot.inFlight
            if (
                currentFlight != null &&
                !currentFlight.invalidated &&
                currentFlight.configEpoch == configEpoch &&
                slot.signature == signature &&
                currentFlight.signature == signature
            ) {
                return@synchronized TunnelAction.Await(currentFlight)
            }

            val predecessorFinished = currentFlight?.let { flight ->
                invalidateFlightLocked(flight)
                flight.session?.let(sessionsToDisconnect::add)
                flight.finished
            } ?: slot.predecessorFinished
            slot.predecessorFinished = null
            slot.generation += 1
            slot.signature = signature
            val flight = TunnelFlight(
                serverId = serverId,
                generation = slot.generation,
                configEpoch = configEpoch,
                signature = signature,
                predecessorFinished = predecessorFinished,
            )
            slot.inFlight = flight
            TunnelAction.Open(flight)
        }
        return TunnelSelection(action, sessionsToDisconnect)
    }

    private fun ensureConfigEpochAccepted(serverId: String, configEpoch: Long) {
        val accepted = synchronized(lock) {
            val slot = slots.getOrPut(serverId, ::ServerTunnelSlot)
            configEpoch >= slot.minimumConfigEpoch
        }
        if (!accepted) throw SshTunnelGenerationSupersededException(serverId)
    }

    private suspend fun openAndPublish(
        server: ServerConfig,
        config: ServerSshTunnelConfig,
        credentials: SshTunnelCredentials,
        remote: SshRemoteEndpoint,
        hostKeyRepository: PinnedSshHostKeyRepository,
        flight: TunnelFlight,
    ): SshTunnel {
        try {
            flight.predecessorFinished?.await()
            ensureFlightCurrent(flight)
            val active = openTunnel(
                server = server,
                config = config,
                credentials = credentials,
                remote = remote,
                hostKeyRepository = hostKeyRepository,
                flight = flight,
            )
            val tunnel = active.toTunnel()
            val accepted = synchronized(lock) {
                val slot = slots[server.id]
                if (
                    slot?.inFlight !== flight ||
                    slot.generation != flight.generation ||
                    flight.configEpoch < slot.minimumConfigEpoch ||
                    flight.invalidated
                ) {
                    false
                } else {
                    flight.session = null
                    slot.inFlight = null
                    slot.active = active
                    flight.completion.complete(tunnel)
                    true
                }
            }
            if (!accepted) {
                active.session.disconnectSafely()
                throw SshTunnelGenerationSupersededException(server.id)
            }
            return tunnel
        } catch (throwable: Throwable) {
            val sessionToDisconnect = synchronized(lock) {
                val slot = slots[server.id]
                if (slot?.inFlight === flight) {
                    slot.inFlight = null
                    slot.predecessorFinished = flight.finished
                }
                flight.completion.completeExceptionally(throwable)
                flight.session.also { flight.session = null }
            }
            sessionToDisconnect?.disconnectSafely()
            throw throwable
        } finally {
            flight.finished.complete(Unit)
        }
    }

    private fun openTunnel(
        server: ServerConfig,
        config: ServerSshTunnelConfig,
        credentials: SshTunnelCredentials,
        remote: SshRemoteEndpoint,
        hostKeyRepository: PinnedSshHostKeyRepository,
        flight: TunnelFlight,
    ): ActiveSshTunnel {
        val session = sessionFactory.create(config.username, config.host, config.port)
        if (!registerSession(flight, session)) {
            session.disconnectSafely()
            throw SshTunnelGenerationSupersededException(server.id)
        }

        try {
            session.setHostKeyRepository(hostKeyRepository)
            session.setConfig(STRICT_HOST_KEY_CHECKING, "yes")
            session.setConfig(PREFERRED_AUTHENTICATIONS, config.authMethod.preferredAuthentications)
            session.setServerAliveInterval(config.keepAliveSeconds.toTimeoutMillis())
            ensureFlightCurrent(flight)

            if (config.authMethod.usesPrivateKey) {
                session.addIdentity(
                    name = "ocdeck-${server.id}",
                    privateKey = requireNotNull(credentials.privateKey),
                    passphrase = credentials.passphrase?.takeIf { it.isNotBlank() },
                )
            }
            if (config.authMethod.usesPassword) {
                session.setPassword(requireNotNull(credentials.password))
            }
            ensureFlightCurrent(flight)

            session.connect(config.connectTimeoutSeconds.toTimeoutMillis())
            ensureFlightCurrent(flight)
            if (hostKeyRepository.mismatchObserved) {
                throw SshHostKeyMismatchException()
            }
            val verifiedFingerprint = hostKeyRepository.matchedSha256Fingerprint
                ?: throw SshHostKeyVerificationException()
            val localPort = try {
                session.setPortForwardingL("127.0.0.1", config.localPort, remote.host, remote.port)
            } catch (exception: Exception) {
                throw exception.toLocalPortConflict(config.localPort) ?: exception
            }
            ensureFlightCurrent(flight)
            return ActiveSshTunnel(
                session = session,
                configEpoch = flight.configEpoch,
                signature = flight.signature,
                effectiveBaseUrl = SshTunnelUrlResolver.effectiveBaseUrl(server.baseUrl, localPort),
                hostFingerprint = verifiedFingerprint,
            )
        } catch (exception: Exception) {
            session.disconnectSafely()
            if (hostKeyRepository.mismatchObserved && exception !is SshHostKeyMismatchException) {
                throw SshHostKeyMismatchException(exception)
            }
            throw exception
        }
    }

    private fun registerSession(flight: TunnelFlight, session: SshSessionHandle): Boolean = synchronized(lock) {
        if (!isFlightCurrentLocked(flight)) {
            false
        } else {
            flight.session = session
            true
        }
    }

    private fun ensureFlightCurrent(flight: TunnelFlight) {
        val current = synchronized(lock) { isFlightCurrentLocked(flight) }
        if (!current) {
            throw SshTunnelGenerationSupersededException(flight.serverId)
        }
    }

    private fun isFlightCurrentLocked(flight: TunnelFlight): Boolean {
        val slot = slots[flight.serverId]
        return slot?.inFlight === flight &&
            slot.generation == flight.generation &&
            flight.configEpoch >= slot.minimumConfigEpoch &&
            !flight.invalidated
    }

    private fun currentTunnel(
        serverId: String,
        configEpoch: Long,
        signature: SshTunnelSignature,
    ): SshTunnel? = synchronized(lock) {
        slots[serverId]
            ?.active
            ?.takeIf {
                it.configEpoch == configEpoch &&
                    it.signature == signature &&
                    it.session.isConnected
            }
            ?.toTunnel()
    }

    private fun invalidateFlightLocked(flight: TunnelFlight) {
        if (flight.invalidated) return
        flight.invalidated = true
        flight.completion.completeExceptionally(SshTunnelGenerationSupersededException(flight.serverId))
    }

    private fun pinnedRepository(
        config: ServerSshTunnelConfig,
        credentials: SshTunnelCredentials,
    ): PinnedSshHostKeyRepository {
        val fingerprint = credentials.hostFingerprint?.takeIf { it.isNotBlank() }
            ?: when (config.hostKeyPolicy) {
                SshHostKeyPolicy.AcceptNew -> throw SshHostKeyDiscoveryRequiredException()
                SshHostKeyPolicy.Fingerprint -> throw SshHostKeyPinRequiredException()
            }
        return PinnedSshHostKeyRepository(fingerprint)
    }

    private fun validateDiscoveryConfig(config: ServerSshTunnelConfig) {
        require(config.host.isNotBlank()) { "SSH host is required" }
        require(config.port in 1..65_535) { "SSH port is invalid" }
        require(config.username.isNotBlank()) { "SSH username is required" }
        config.connectTimeoutSeconds.toTimeoutMillis()
    }

    private fun validateConnectionConfig(config: ServerSshTunnelConfig) {
        validateDiscoveryConfig(config)
        require(config.localPort in 1..65_535) { "Local forwarding port is invalid" }
        config.keepAliveSeconds.toTimeoutMillis()
    }

    private fun validateAuthenticationCredentials(
        config: ServerSshTunnelConfig,
        credentials: SshTunnelCredentials,
    ) {
        if (config.authMethod.usesPassword && credentials.password.isNullOrBlank()) {
            error("SSH password is required")
        }
        if (config.authMethod.usesPrivateKey && credentials.privateKey.isNullOrBlank()) {
            error("SSH private key is required")
        }
        if (config.authMethod.usesPrivateKey) {
            SshPrivateKeyLimits.requireValidUtf8Size(requireNotNull(credentials.privateKey))
        }
    }

    private companion object {
        const val INITIAL_CONFIG_EPOCH = 1L
        const val STRICT_HOST_KEY_CHECKING = "StrictHostKeyChecking"
        const val PREFERRED_AUTHENTICATIONS = "PreferredAuthentications"
    }
}

class SshTunnelCredentials(
    val password: String?,
    val privateKey: String?,
    val passphrase: String?,
    val hostFingerprint: String?,
) {
    override fun toString(): String =
        "SshTunnelCredentials(password=<redacted>, privateKey=<redacted>, " +
            "passphrase=<redacted>, hostFingerprint=<redacted>)"
}

class SshTunnel(
    val effectiveBaseUrl: String,
    val hostFingerprint: String?,
) {
    override fun toString(): String =
        "SshTunnel(effectiveBaseUrl=<redacted>, " +
            "hostFingerprint=${if (hostFingerprint == null) "null" else "<redacted>"})"
}

class SshTunnelGenerationSupersededException(serverId: String) :
    OpenCodeRequestException(OpenCodeFailure.TransportChanged)

internal interface SshSessionFactory {
    fun create(username: String, host: String, port: Int): SshSessionHandle
}

internal interface SshSessionHandle {
    val isConnected: Boolean

    fun setHostKeyRepository(repository: HostKeyRepository)

    fun setConfig(key: String, value: String)

    fun setServerAliveInterval(intervalMillis: Int)

    fun addIdentity(name: String, privateKey: String, passphrase: String?)

    fun setPassword(password: String)

    fun connect(timeoutMillis: Int)

    fun setPortForwardingL(
        bindAddress: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ): Int

    fun disconnect()
}

internal object SshTunnelUrlResolver {
    fun remoteEndpoint(baseUrl: String): SshRemoteEndpoint {
        val url = normalizeServerBaseUrl(baseUrl).toHttpUrl()
        return SshRemoteEndpoint(host = url.host, port = url.port)
    }

    fun effectiveBaseUrl(baseUrl: String, localPort: Int): String {
        val url = normalizeServerBaseUrl(baseUrl).toHttpUrl()
        return url.newBuilder()
            .host("127.0.0.1")
            .port(localPort)
            .build()
            .toString()
            .trimEnd('/')
    }
}

internal data class SshRemoteEndpoint(
    val host: String,
    val port: Int,
) {
    override fun toString(): String = "SshRemoteEndpoint(host=<redacted>, port=$port)"
}

private object JschSshSessionFactory : SshSessionFactory {
    override fun create(username: String, host: String, port: Int): SshSessionHandle {
        ensureBouncyCastleProvider()
        val jsch = JSch()
        return JschSshSessionHandle(jsch, jsch.getSession(username, host, port))
    }
}

private class JschSshSessionHandle(
    private val jsch: JSch,
    private val session: Session,
) : SshSessionHandle {
    override val isConnected: Boolean
        get() = session.isConnected

    override fun setHostKeyRepository(repository: HostKeyRepository) {
        jsch.setHostKeyRepository(repository)
        session.setHostKeyRepository(repository)
    }

    override fun setConfig(key: String, value: String) {
        session.setConfig(key, value)
    }

    override fun setServerAliveInterval(intervalMillis: Int) {
        session.serverAliveInterval = intervalMillis
    }

    override fun addIdentity(name: String, privateKey: String, passphrase: String?) {
        jsch.addIdentity(
            name,
            privateKey.toByteArray(Charsets.UTF_8),
            null,
            passphrase?.toByteArray(Charsets.UTF_8),
        )
    }

    override fun setPassword(password: String) {
        session.setPassword(password.toByteArray(Charsets.UTF_8))
    }

    override fun connect(timeoutMillis: Int) {
        session.connect(timeoutMillis)
    }

    override fun setPortForwardingL(
        bindAddress: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ): Int = session.setPortForwardingL(bindAddress, localPort, remoteHost, remotePort)

    override fun disconnect() {
        session.disconnect()
    }
}

private data class SshTunnelSignature(
    val baseUrl: String,
    val host: String,
    val port: Int,
    val username: String,
    val authMethod: SshAuthMethod,
    val passwordKey: String?,
    val privateKeyKey: String?,
    val passphraseKey: String?,
    val localPort: Int,
    val connectTimeoutSeconds: Int,
    val keepAliveSeconds: Int,
    val hostKeyPolicy: SshHostKeyPolicy,
    val hostFingerprintKey: String?,
    val credentialDigest: String,
) {
    override fun toString(): String =
        "SshTunnelSignature(baseUrl=<redacted>, host=<redacted>, port=$port, username=<redacted>, " +
            "authMethod=$authMethod, passwordKey=${redactedIfPresent(passwordKey)}, " +
            "privateKeyKey=${redactedIfPresent(privateKeyKey)}, " +
            "passphraseKey=${redactedIfPresent(passphraseKey)}, localPort=$localPort, " +
            "connectTimeoutSeconds=$connectTimeoutSeconds, keepAliveSeconds=$keepAliveSeconds, " +
            "hostKeyPolicy=$hostKeyPolicy, hostFingerprintKey=${redactedIfPresent(hostFingerprintKey)}, " +
            "credentialDigest=<redacted>)"
}

private class ActiveSshTunnel(
    val session: SshSessionHandle,
    val configEpoch: Long,
    val signature: SshTunnelSignature,
    val effectiveBaseUrl: String,
    val hostFingerprint: String,
) {
    fun toTunnel(): SshTunnel = SshTunnel(
        effectiveBaseUrl = effectiveBaseUrl,
        hostFingerprint = hostFingerprint,
    )
}

private class ServerTunnelSlot {
    var minimumConfigEpoch: Long = 1L
    var generation: Long = 0
    var signature: SshTunnelSignature? = null
    var active: ActiveSshTunnel? = null
    var inFlight: TunnelFlight? = null
    var predecessorFinished: CompletableDeferred<Unit>? = null
}

private class TunnelFlight(
    val serverId: String,
    val generation: Long,
    val configEpoch: Long,
    val signature: SshTunnelSignature,
    val predecessorFinished: CompletableDeferred<Unit>?,
) {
    val completion = CompletableDeferred<SshTunnel>()
    val finished = CompletableDeferred<Unit>()
    var session: SshSessionHandle? = null
    var invalidated: Boolean = false
}

private data class TunnelSelection(
    val action: TunnelAction,
    val sessionsToDisconnect: List<SshSessionHandle>,
)

private sealed interface TunnelAction {
    data class Cached(val tunnel: ActiveSshTunnel) : TunnelAction
    data class Await(val flight: TunnelFlight) : TunnelAction
    data class Open(val flight: TunnelFlight) : TunnelAction
}

private fun ServerSshTunnelConfig.signature(
    baseUrl: String,
    credentials: SshTunnelCredentials,
): SshTunnelSignature = SshTunnelSignature(
    baseUrl = baseUrl,
    host = host,
    port = port,
    username = username,
    authMethod = authMethod,
    passwordKey = passwordKey,
    privateKeyKey = privateKeyKey,
    passphraseKey = passphraseKey,
    localPort = localPort,
    connectTimeoutSeconds = connectTimeoutSeconds,
    keepAliveSeconds = keepAliveSeconds,
    hostKeyPolicy = hostKeyPolicy,
    hostFingerprintKey = hostFingerprintKey,
    credentialDigest = credentials.processLocalDigest(),
)

private fun SshTunnelCredentials.processLocalDigest(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateNullable(password)
    digest.updateNullable(privateKey)
    digest.updateNullable(passphrase)
    digest.updateNullable(hostFingerprint)
    return Base64.getEncoder().withoutPadding().encodeToString(digest.digest())
}

private fun MessageDigest.updateNullable(value: String?) {
    if (value == null) {
        update(0.toByte())
        return
    }
    update(1.toByte())
    val bytes = value.toByteArray(Charsets.UTF_8)
    update((bytes.size ushr 24).toByte())
    update((bytes.size ushr 16).toByte())
    update((bytes.size ushr 8).toByte())
    update(bytes.size.toByte())
    update(bytes)
    bytes.fill(0)
}

private val SshAuthMethod.usesPassword: Boolean
    get() = this == SshAuthMethod.Password || this == SshAuthMethod.PasswordAndPrivateKey

private val SshAuthMethod.usesPrivateKey: Boolean
    get() = this == SshAuthMethod.PrivateKey || this == SshAuthMethod.PasswordAndPrivateKey

private val SshAuthMethod.preferredAuthentications: String
    get() = when (this) {
        SshAuthMethod.Password -> "password"
        SshAuthMethod.PrivateKey -> "publickey"
        SshAuthMethod.PasswordAndPrivateKey -> "publickey,password"
    }

private fun Int.toTimeoutMillis(): Int {
    require(this in 1..Int.MAX_VALUE / 1_000) { "SSH timeout is invalid" }
    return this * 1_000
}

private fun SshSessionHandle.disconnectSafely() {
    try {
        disconnect()
    } catch (_: Exception) {
        // A stale disconnect failure must not let an old generation replace current state.
    }
}

private fun Exception.toLocalPortConflict(localPort: Int): LocalPortInUseException? {
    val isConflict = generateSequence<Throwable>(this) { it.cause }.any { throwable ->
        throwable is java.net.BindException ||
            throwable.message?.contains("PortForwardingL", ignoreCase = true) == true ||
            throwable.message?.contains("address already in use", ignoreCase = true) == true
    }
    return if (isConflict) {
        LocalPortInUseException(localPort, this)
    } else {
        null
    }
}

private fun redactedIfPresent(value: Any?): String = if (value == null) "null" else "<redacted>"

private val bouncyCastleProviderLock = Any()

private fun ensureBouncyCastleProvider() {
    synchronized(bouncyCastleProviderLock) {
        val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (current?.javaClass?.name == BouncyCastleProvider::class.java.name) return
        if (current != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}
