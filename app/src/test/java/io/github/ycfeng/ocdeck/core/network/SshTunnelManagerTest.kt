package io.github.ycfeng.ocdeck.core.network

import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSchException
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerSshTunnelConfig
import io.github.ycfeng.ocdeck.data.server.SshAuthMethod
import io.github.ycfeng.ocdeck.data.server.SshHostKeyPolicy
import io.github.ycfeng.ocdeck.core.security.SshPrivateKeyLimits
import io.github.ycfeng.ocdeck.core.security.SshPrivateKeyTooLargeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SshTunnelManagerTest {
    @Test
    fun discoveryRejectsBeforeAuthenticationAndReturnsCanonicalPin() = runBlocking {
        val rawHostKey = "discovery-host-key".toByteArray()
        val factory = RecordingSshSessionFactory(rawHostKey)
        val manager = SshTunnelManager(factory)

        val fingerprint = manager.discoverHostKey(sshServer())

        assertEquals(SshHostKeyFingerprint.sha256(rawHostKey), fingerprint)
        val session = factory.sessions.single()
        assertTrue(session.operationIndex("host-key-repository") < session.operationIndex("connect"))
        assertTrue(session.operationIndex("config:StrictHostKeyChecking=yes") < session.operationIndex("connect"))
        assertFalse(session.operations.contains("password"))
        assertFalse(session.operations.contains("identity"))
        assertFalse(session.operations.contains("forward"))
        assertTrue(session.disconnectCount.get() >= 1)
    }

    @Test
    fun acceptNewWithoutPinRequiresDiscoveryBeforeCreatingSession() {
        val factory = RecordingSshSessionFactory("host-key".toByteArray())
        val manager = SshTunnelManager(factory)

        val failure = assertThrows(SshHostKeyDiscoveryRequiredException::class.java) {
            runBlocking {
                manager.ensureTunnel(
                    sshServer(hostKeyPolicy = SshHostKeyPolicy.AcceptNew),
                    credentials(hostFingerprint = null),
                )
            }
        }

        assertEquals("SSH host key discovery is required before authentication", failure.message)
        assertTrue(factory.sessions.isEmpty())
    }

    @Test
    fun fixedFingerprintPolicyWithoutPinFailsBeforeCreatingSession() {
        val factory = RecordingSshSessionFactory("host-key".toByteArray())
        val manager = SshTunnelManager(factory)

        assertThrows(SshHostKeyPinRequiredException::class.java) {
            runBlocking {
                manager.ensureTunnel(
                    sshServer(hostKeyPolicy = SshHostKeyPolicy.Fingerprint),
                    credentials(hostFingerprint = null),
                )
            }
        }

        assertTrue(factory.sessions.isEmpty())
    }

    @Test
    fun discoveryFailureDisconnectsProbeSession() {
        val factory = RecordingSshSessionFactory("host-key".toByteArray()) { _, _ ->
            throw JSchException("Connection failed before host key exchange")
        }
        val manager = SshTunnelManager(factory)

        assertThrows(SshHostKeyDiscoveryException::class.java) {
            runBlocking { manager.discoverHostKey(sshServer()) }
        }

        assertTrue(factory.sessions.single().disconnectCount.get() >= 1)
        assertFalse(factory.sessions.single().operations.contains("password"))
        assertFalse(factory.sessions.single().operations.contains("identity"))
    }

    @Test
    fun discoveryPropagatesJvmErrorWithoutWrapping() {
        val expected = AssertionError("fatal discovery failure")
        val factory = RecordingSshSessionFactory("host-key".toByteArray()) { _, _ ->
            throw expected
        }
        val manager = SshTunnelManager(factory)

        val thrown = assertThrows(AssertionError::class.java) {
            runBlocking { manager.discoverHostKey(sshServer()) }
        }

        assertTrue(thrown === expected || thrown.cause === expected)
    }

    @Test
    fun malformedPinFailsBeforeCreatingSessionWithoutEchoingInput() {
        val malformed = "SHA256:not-valid"
        val factory = RecordingSshSessionFactory("host-key".toByteArray())
        val manager = SshTunnelManager(factory)

        val failure = assertThrows(SshHostKeyFingerprintFormatException::class.java) {
            runBlocking {
                manager.ensureTunnel(
                    sshServer(hostKeyPolicy = SshHostKeyPolicy.Fingerprint),
                    credentials(hostFingerprint = malformed),
                )
            }
        }

        assertFalse(failure.message.orEmpty().contains(malformed))
        assertTrue(factory.sessions.isEmpty())
    }

    @Test
    fun authenticationSessionInstallsStrictPinBeforeCredentialsAndConnect() = runBlocking {
        val rawHostKey = "pinned-host-key".toByteArray()
        val fingerprint = SshHostKeyFingerprint.sha256(rawHostKey)
        val factory = RecordingSshSessionFactory(rawHostKey)
        val manager = SshTunnelManager(factory)

        val tunnel = manager.ensureTunnel(
            server = sshServer(
                authMethod = SshAuthMethod.PasswordAndPrivateKey,
                hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
            ),
            credentials = credentials(hostFingerprint = fingerprint),
        )

        val session = factory.sessions.single()
        val repositoryIndex = session.operationIndex("host-key-repository")
        assertTrue(repositoryIndex < session.operationIndex("identity"))
        assertTrue(repositoryIndex < session.operationIndex("password"))
        assertTrue(session.operationIndex("config:StrictHostKeyChecking=yes") < session.operationIndex("connect"))
        assertTrue(session.operationIndex("identity") < session.operationIndex("connect"))
        assertTrue(session.operationIndex("password") < session.operationIndex("connect"))
        assertTrue(session.operationIndex("connect") < session.operationIndex("forward"))
        assertEquals("http://127.0.0.1:4096/opencode", tunnel.effectiveBaseUrl)
        assertEquals(fingerprint, tunnel.hostFingerprint)
    }

    @Test
    fun hostKeyMismatchIsTypedAndDoesNotExposeFingerprints() {
        val rawHostKey = "actual-host-key".toByteArray()
        val expected = SshHostKeyFingerprint.sha256("different-host-key".toByteArray())
        val actual = SshHostKeyFingerprint.sha256(rawHostKey)
        val factory = RecordingSshSessionFactory(rawHostKey)
        val manager = SshTunnelManager(factory)

        val failure = assertThrows(SshHostKeyMismatchException::class.java) {
            runBlocking {
                manager.ensureTunnel(
                    sshServer(hostKeyPolicy = SshHostKeyPolicy.Fingerprint),
                    credentials(hostFingerprint = expected),
                )
            }
        }

        assertFalse(failure.message.orEmpty().contains(expected))
        assertFalse(failure.message.orEmpty().contains(actual))
        assertFalse(factory.sessions.single().operations.contains("forward"))
        assertTrue(factory.sessions.single().disconnectCount.get() >= 1)
    }

    @Test
    fun credentialContentDigestInvalidatesCachedTunnel() = runBlocking {
        val rawHostKey = "cache-host-key".toByteArray()
        val fingerprint = SshHostKeyFingerprint.sha256(rawHostKey)
        val factory = RecordingSshSessionFactory(rawHostKey)
        val manager = SshTunnelManager(factory)
        val server = sshServer(
            authMethod = SshAuthMethod.Password,
            hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
        )

        manager.ensureTunnel(server, credentials(password = "first-password", hostFingerprint = fingerprint))
        manager.ensureTunnel(server, credentials(password = "first-password", hostFingerprint = fingerprint))
        assertEquals(1, factory.sessions.size)

        manager.ensureTunnel(server, credentials(password = "updated-password", hostFingerprint = fingerprint))

        assertEquals(2, factory.sessions.size)
        assertTrue(factory.sessions.first().disconnectCount.get() >= 1)
    }

    @Test
    fun closeDuringConnectRejectsLateGenerationAndDoesNotCacheIt() = runBlocking {
        val connectStarted = CountDownLatch(1)
        val allowConnectToFinish = CountDownLatch(1)
        val rawHostKey = "late-host-key".toByteArray()
        val fingerprint = SshHostKeyFingerprint.sha256(rawHostKey)
        val factory = RecordingSshSessionFactory(rawHostKey) { index, _ ->
            if (index == 1) {
                connectStarted.countDown()
                check(allowConnectToFinish.await(5, TimeUnit.SECONDS))
            }
        }
        val manager = SshTunnelManager(factory)
        val server = sshServer(hostKeyPolicy = SshHostKeyPolicy.Fingerprint)

        val opening = async(Dispatchers.Default) {
            runCatching { manager.ensureTunnel(server, credentials(hostFingerprint = fingerprint)) }
        }
        assertTrue(connectStarted.await(5, TimeUnit.SECONDS))

        manager.closeTunnel(server.id, minimumConfigEpoch = 2L)
        allowConnectToFinish.countDown()
        val failure = withTimeout(5_000) { opening.await().exceptionOrNull() }

        assertTrue(failure is SshTunnelGenerationSupersededException)
        assertTrue(factory.sessions.first().disconnectCount.get() >= 1)
        assertFalse(factory.sessions.first().operations.contains("forward"))

        val replacement = manager.ensureTunnel(
            server = server,
            credentials = credentials(hostFingerprint = fingerprint),
            configEpoch = 2L,
        )
        assertEquals(2, factory.sessions.size)
        assertEquals(fingerprint, replacement.hostFingerprint)
    }

    @Test
    fun staleConfigEpochCannotDisconnectOrReplaceNewActiveTunnel() = runBlocking {
        val rawHostKey = "epoch-host-key".toByteArray()
        val fingerprint = SshHostKeyFingerprint.sha256(rawHostKey)
        val factory = RecordingSshSessionFactory(rawHostKey)
        val manager = SshTunnelManager(factory)
        val server = sshServer(
            authMethod = SshAuthMethod.Password,
            hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
        )

        manager.closeTunnel(server.id, minimumConfigEpoch = 2L)
        manager.ensureTunnel(
            server = server,
            credentials = credentials(password = "new-password", hostFingerprint = fingerprint),
            configEpoch = 2L,
        )

        assertThrows(SshTunnelGenerationSupersededException::class.java) {
            runBlocking {
                manager.ensureTunnel(
                    server = server,
                    credentials = credentials(password = "old-password", hostFingerprint = fingerprint),
                    configEpoch = 1L,
                )
            }
        }
        manager.closeTunnel(server.id, minimumConfigEpoch = 1L)
        manager.ensureTunnel(
            server = server,
            credentials = credentials(password = "new-password", hostFingerprint = fingerprint),
            configEpoch = 2L,
        )

        assertEquals(1, factory.sessions.size)
        assertEquals(0, factory.sessions.single().disconnectCount.get())
    }

    @Test
    fun openTunnelPropagatesJvmErrorWithoutWrapping() {
        val rawHostKey = "fatal-open-host-key".toByteArray()
        val fingerprint = SshHostKeyFingerprint.sha256(rawHostKey)
        val expected = AssertionError("fatal open failure")
        val factory = RecordingSshSessionFactory(rawHostKey) { _, _ ->
            throw expected
        }
        val manager = SshTunnelManager(factory)

        val thrown = assertThrows(AssertionError::class.java) {
            runBlocking {
                manager.ensureTunnel(
                    server = sshServer(hostKeyPolicy = SshHostKeyPolicy.Fingerprint),
                    credentials = credentials(hostFingerprint = fingerprint),
                )
            }
        }

        assertTrue(thrown === expected || thrown.cause === expected)
    }

    @Test
    fun credentialAndTunnelToStringRedactSensitiveValues() {
        val credentials = SshTunnelCredentials(
            password = "unique-password-value",
            privateKey = "unique-private-key-value",
            passphrase = "unique-passphrase-value",
            hostFingerprint = "SHA256:unique-fingerprint-value",
        )
        val tunnel = SshTunnel(
            effectiveBaseUrl = "http://127.0.0.1:4096",
            hostFingerprint = "SHA256:another-unique-fingerprint",
        )

        val credentialsText = credentials.toString()
        assertFalse(credentialsText.contains(credentials.password!!))
        assertFalse(credentialsText.contains(credentials.privateKey!!))
        assertFalse(credentialsText.contains(credentials.passphrase!!))
        assertFalse(credentialsText.contains(credentials.hostFingerprint!!))
        assertFalse(tunnel.toString().contains(tunnel.hostFingerprint!!))
        assertTrue(credentialsText.contains("<redacted>"))
        assertTrue(tunnel.toString().contains("<redacted>"))
    }

    @Test
    fun oversizedPrivateKeyFailsBeforeCreatingAuthenticationSession() {
        val rawHostKey = "host-key".toByteArray()
        val fingerprint = SshHostKeyFingerprint.sha256(rawHostKey)
        val factory = RecordingSshSessionFactory(rawHostKey)
        val manager = SshTunnelManager(factory)

        assertThrows(SshPrivateKeyTooLargeException::class.java) {
            runBlocking {
                manager.ensureTunnel(
                    sshServer(hostKeyPolicy = SshHostKeyPolicy.Fingerprint),
                    credentials(
                        privateKey = "a".repeat(SshPrivateKeyLimits.MAX_BYTES + 1),
                        hostFingerprint = fingerprint,
                    ),
                )
            }
        }

        assertTrue(factory.sessions.isEmpty())
    }
}

private class RecordingSshSessionFactory(
    private val rawHostKey: ByteArray,
    private val onConnect: (index: Int, session: RecordingSshSession) -> Unit = { _, _ -> },
) : SshSessionFactory {
    val sessions = CopyOnWriteArrayList<RecordingSshSession>()

    override fun create(username: String, host: String, port: Int): SshSessionHandle {
        val session = RecordingSshSession(
            host = host,
            rawHostKey = rawHostKey,
            connectIndex = sessions.size + 1,
            onConnect = onConnect,
        )
        sessions += session
        return session
    }
}

private class RecordingSshSession(
    private val host: String,
    private val rawHostKey: ByteArray,
    private val connectIndex: Int,
    private val onConnect: (index: Int, session: RecordingSshSession) -> Unit,
) : SshSessionHandle {
    val operations = CopyOnWriteArrayList<String>()
    val disconnectCount = AtomicInteger()

    @Volatile
    private var repository: HostKeyRepository? = null

    @Volatile
    override var isConnected: Boolean = false
        private set

    override fun setHostKeyRepository(repository: HostKeyRepository) {
        operations += "host-key-repository"
        this.repository = repository
    }

    override fun setConfig(key: String, value: String) {
        operations += "config:$key=$value"
    }

    override fun setServerAliveInterval(intervalMillis: Int) {
        operations += "keep-alive"
    }

    override fun addIdentity(name: String, privateKey: String, passphrase: String?) {
        operations += "identity"
    }

    override fun setPassword(password: String) {
        operations += "password"
    }

    override fun connect(timeoutMillis: Int) {
        operations += "connect"
        onConnect(connectIndex, this)
        val result = requireNotNull(repository).check(host, rawHostKey)
        if (result != HostKeyRepository.OK) {
            throw JSchException("Host key rejected before authentication")
        }
        isConnected = true
    }

    override fun setPortForwardingL(
        bindAddress: String,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ): Int {
        operations += "forward"
        return localPort
    }

    override fun disconnect() {
        operations += "disconnect"
        disconnectCount.incrementAndGet()
        isConnected = false
    }

    fun operationIndex(operation: String): Int {
        val index = operations.indexOf(operation)
        check(index >= 0) { "Missing operation: $operation in $operations" }
        return index
    }
}

private fun sshServer(
    authMethod: SshAuthMethod = SshAuthMethod.PrivateKey,
    hostKeyPolicy: SshHostKeyPolicy = SshHostKeyPolicy.AcceptNew,
): ServerConfig = ServerConfig(
    id = "ssh-server",
    name = "SSH server",
    baseUrl = "http://opencode.example.test:4096/opencode",
    sshTunnel = ServerSshTunnelConfig(
        host = "ssh.example.test",
        port = 22,
        username = "ssh-user",
        authMethod = authMethod,
        passwordKey = if (authMethod != SshAuthMethod.PrivateKey) "ssh-password-key" else null,
        privateKeyKey = if (authMethod != SshAuthMethod.Password) "ssh-private-key-key" else null,
        passphraseKey = if (authMethod != SshAuthMethod.Password) "ssh-passphrase-key" else null,
        localPort = 4096,
        connectTimeoutSeconds = 5,
        keepAliveSeconds = 30,
        hostKeyPolicy = hostKeyPolicy,
        hostFingerprintKey = if (hostKeyPolicy == SshHostKeyPolicy.Fingerprint) "ssh-host-key-pin" else null,
    ),
)

private fun credentials(
    password: String? = "test-password",
    privateKey: String? = "test-private-key",
    passphrase: String? = "test-passphrase",
    hostFingerprint: String?,
): SshTunnelCredentials = SshTunnelCredentials(
    password = password,
    privateKey = privateKey,
    passphrase = passphrase,
    hostFingerprint = hostFingerprint,
)
