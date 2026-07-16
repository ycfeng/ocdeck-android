package io.github.ycfeng.ocdeck.feature.server

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.security.SshPrivateKeyLimits
import io.github.ycfeng.ocdeck.core.security.SshPrivateKeyTooLargeException
import io.github.ycfeng.ocdeck.data.server.FrpcTransportProtocol
import io.github.ycfeng.ocdeck.data.server.FrpcWireProtocol
import io.github.ycfeng.ocdeck.data.server.NewServerFrpcStcpVisitor
import io.github.ycfeng.ocdeck.data.server.NewServerSshTunnel
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerBaseUrlFailure
import io.github.ycfeng.ocdeck.data.server.ServerBaseUrlValidationException
import io.github.ycfeng.ocdeck.data.server.ServerConfigMutationOutcomeUnknownException
import io.github.ycfeng.ocdeck.data.server.ServerEditorRepository
import io.github.ycfeng.ocdeck.data.server.SshAuthMethod
import io.github.ycfeng.ocdeck.data.server.SshHostKeyPolicy
import io.github.ycfeng.ocdeck.data.server.isNonLoopbackHttpServerBaseUrl
import io.github.ycfeng.ocdeck.data.server.normalizeServerBaseUrl
import io.github.ycfeng.ocdeck.data.server.safeServerBaseUrlForDisplay
import io.github.ycfeng.ocdeck.ui.text.UiText
import io.github.ycfeng.ocdeck.ui.text.toErrorUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AddServerViewModel(
    private val serverRepository: ServerEditorRepository,
    private val serverId: String? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddServerUiState())
    val uiState: StateFlow<AddServerUiState> = _uiState.asStateFlow()
    private val saveInFlight = AtomicBoolean(false)
    private var pendingCleartextHttpSave: ValidatedServerSave? = null

    init {
        serverId?.let(::loadServer)
    }

    fun onBaseUrlChanged(value: String) = _uiState.update { it.copy(baseUrl = value, error = null) }
    fun onNameChanged(value: String) = _uiState.update { it.copy(name = value, error = null) }
    fun onUsernameChanged(value: String) = _uiState.update { it.copy(username = value, error = null) }
    fun onPasswordChanged(value: String) = _uiState.update { it.copy(password = value, error = null) }
    fun onConnectionModeChanged(value: ServerConnectionMode) = _uiState.update {
        it.copy(
            baseUrl = if (value.usesLocalTunnel && it.baseUrl.isBlank()) DEFAULT_TUNNEL_BASE_URL else it.baseUrl,
            connectionMode = value,
            error = null,
        )
    }
    fun onSshHostChanged(value: String) = _uiState.update { it.copy(sshHost = value, error = null) }
    fun onSshPortChanged(value: String) = _uiState.update { it.copy(sshPort = value, error = null) }
    fun onSshUsernameChanged(value: String) = _uiState.update { it.copy(sshUsername = value, error = null) }
    fun onSshAuthMethodChanged(value: SshAuthMethod) = _uiState.update { it.copy(sshAuthMethod = value, error = null) }
    fun onSshPasswordChanged(value: String) = _uiState.update { it.copy(sshPassword = value, error = null) }
    fun onPrivateKeySourceChanged(value: PrivateKeySource) = _uiState.update { it.copy(privateKeySource = value, error = null) }
    fun onPrivateKeyChanged(value: String) = _uiState.update { it.copy(privateKey = value, privateKeyFileName = null, error = null) }
    fun onPrivateKeyFileReadStarted() = _uiState.update { it.copy(isReadingPrivateKey = true, error = null) }
    fun onPrivateKeyFileSelected(fileName: String, content: String) = _uiState.update {
        it.copy(
            privateKeySource = PrivateKeySource.File,
            privateKey = content,
            privateKeyFileName = fileName,
            isReadingPrivateKey = false,
            error = null,
        )
    }
    fun onPrivateKeyFileReadFailed() = _uiState.update {
        it.copy(isReadingPrivateKey = false, error = UiText.Resource(R.string.server_private_key_read_failed))
    }
    fun onPrivateKeyFileTooLarge() = _uiState.update {
        it.copy(isReadingPrivateKey = false, error = privateKeyTooLargeUiText())
    }
    fun onPrivateKeyFileCleared() = _uiState.update { it.copy(privateKey = "", privateKeyFileName = null, error = null) }
    fun onPrivateKeyPassphraseChanged(value: String) = _uiState.update { it.copy(privateKeyPassphrase = value, error = null) }
    fun onLocalPortChanged(value: String) = _uiState.update { it.copy(localPort = value, error = null) }
    fun onConnectTimeoutChanged(value: String) = _uiState.update { it.copy(connectTimeoutSeconds = value, error = null) }
    fun onKeepAliveChanged(value: String) = _uiState.update { it.copy(keepAliveSeconds = value, error = null) }
    fun onHostKeyPolicyChanged(value: SshHostKeyPolicy) = _uiState.update {
        it.copy(
            hostKeyPolicy = value,
            hostFingerprint = if (value == SshHostKeyPolicy.AcceptNew) "" else it.hostFingerprint,
            error = null,
        )
    }
    fun onHostFingerprintChanged(value: String) = _uiState.update { it.copy(hostFingerprint = value, error = null) }
    fun onFrpcServerAddrChanged(value: String) = _uiState.update { it.copy(frpcServerAddr = value, error = null) }
    fun onFrpcServerPortChanged(value: String) = _uiState.update { it.copy(frpcServerPort = value, error = null) }
    fun onFrpcAuthTokenChanged(value: String) = _uiState.update { it.copy(frpcAuthToken = value, error = null) }
    fun onFrpcUserChanged(value: String) = _uiState.update { it.copy(frpcUser = value, error = null) }
    fun onFrpcServerUserChanged(value: String) = _uiState.update { it.copy(frpcServerUser = value, error = null) }
    fun onFrpcServerNameChanged(value: String) = _uiState.update { it.copy(frpcServerName = value, error = null) }
    fun onFrpcSecretKeyChanged(value: String) = _uiState.update { it.copy(frpcSecretKey = value, error = null) }
    fun onFrpcBindPortChanged(value: String) = _uiState.update { it.copy(frpcBindPort = value, error = null) }
    fun onFrpcWireProtocolChanged(value: FrpcWireProtocol) = _uiState.update { it.copy(frpcWireProtocol = value, error = null) }

    private fun loadServer(serverId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(serverId = serverId, isLoading = true, error = null) }
            try {
                val server = serverRepository.getServer(serverId)
                _uiState.update { server.toUiState() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        serverId = serverId,
                        isLoading = false,
                        error = exception.toErrorUiText(R.string.server_error_load_failed),
                    )
                }
            }
        }
    }

    fun save() {
        val state = _uiState.value
        if (state.isLoading || state.isSaving || state.isReadingPrivateKey) return
        pendingCleartextHttpSave = null
        if (state.showCleartextHttpCredentialsWarning) {
            _uiState.update { it.copy(showCleartextHttpCredentialsWarning = false) }
        }
        if (state.baseUrl.isBlank()) {
            _uiState.update { it.copy(error = UiText.Resource(R.string.server_error_url_empty)) }
            return
        }
        val normalizedBaseUrl = try {
            normalizeServerBaseUrl(state.baseUrl)
        } catch (exception: ServerBaseUrlValidationException) {
            _uiState.update { it.copy(error = exception.reason.toUiText()) }
            return
        }
        val sshTunnel = if (state.connectionMode == ServerConnectionMode.Ssh) state.toSshTunnelOrNull() ?: return else null
        val frpcStcpVisitor = if (state.connectionMode == ServerConnectionMode.FrpcStcp) state.toFrpcStcpVisitorOrNull() ?: return else null
        val request = ValidatedServerSave(
            serverId = state.serverId,
            baseUrl = normalizedBaseUrl,
            name = state.name,
            username = state.username,
            password = state.password,
            sshTunnel = sshTunnel,
            frpcStcpVisitor = frpcStcpVisitor,
        )
        if (state.requiresCleartextHttpCredentialsWarning(normalizedBaseUrl)) {
            pendingCleartextHttpSave = request
            _uiState.update {
                it.copy(
                    showCleartextHttpCredentialsWarning = true,
                    error = null,
                )
            }
            return
        }
        persist(request)
    }

    fun confirmCleartextHttpSave() {
        pendingCleartextHttpSave?.let(::persist)
    }

    fun dismissCleartextHttpWarning() {
        pendingCleartextHttpSave = null
        _uiState.update { it.copy(showCleartextHttpCredentialsWarning = false) }
    }

    private fun persist(request: ValidatedServerSave) {
        if (!saveInFlight.compareAndSet(false, true)) return
        if (pendingCleartextHttpSave === request) pendingCleartextHttpSave = null
        _uiState.update { it.copy(showCleartextHttpCredentialsWarning = false) }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val server = if (request.serverId == null) {
                    serverRepository.addServer(
                        baseUrl = request.baseUrl,
                        name = request.name,
                        username = request.username,
                        password = request.password,
                        sshTunnel = request.sshTunnel,
                        frpcStcpVisitor = request.frpcStcpVisitor,
                    )
                } else {
                    serverRepository.updateServer(
                        serverId = request.serverId,
                        baseUrl = request.baseUrl,
                        name = request.name,
                        username = request.username,
                        password = request.password,
                        sshTunnel = request.sshTunnel,
                        frpcStcpVisitor = request.frpcStcpVisitor,
                    )
                }
                _uiState.update { it.copy(isSaving = false, savedServerId = server.id) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: ServerConfigMutationOutcomeUnknownException) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = UiText.Resource(R.string.server_error_save_outcome_unknown),
                    )
                }
            } catch (exception: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = exception.toErrorUiText(R.string.server_error_save_failed),
                    )
                }
            } finally {
                saveInFlight.set(false)
                _uiState.update { if (it.isSaving) it.copy(isSaving = false) else it }
            }
        }
    }

    private class ValidatedServerSave(
        val serverId: String?,
        val baseUrl: String,
        val name: String,
        val username: String,
        val password: String,
        val sshTunnel: NewServerSshTunnel?,
        val frpcStcpVisitor: NewServerFrpcStcpVisitor?,
    ) {
        override fun toString(): String = "ValidatedServerSave(<redacted>)"
    }

    private fun AddServerUiState.toSshTunnelOrNull(): NewServerSshTunnel? {
        if (sshHost.isBlank()) return fail(UiText.Resource(R.string.server_error_ssh_host_empty))
        if (sshUsername.isBlank()) return fail(UiText.Resource(R.string.server_error_ssh_username_empty))
        val parsedSshPort = parsePort(sshPort, R.string.server_error_ssh_port_range) ?: return null
        if (sshAuthMethod.usesPassword && sshPassword.isBlank() && !hasSavedSshPassword) return fail(UiText.Resource(R.string.server_error_ssh_password_empty))
        if (sshAuthMethod.usesPrivateKey && privateKey.isBlank() && !hasSavedPrivateKey) return fail(UiText.Resource(R.string.server_error_ssh_private_key_empty))
        if (sshAuthMethod.usesPrivateKey && privateKey.isNotBlank()) {
            try {
                SshPrivateKeyLimits.requireValidUtf8Size(privateKey)
            } catch (_: SshPrivateKeyTooLargeException) {
                return fail(privateKeyTooLargeUiText())
            }
        }
        if (hostKeyPolicy == SshHostKeyPolicy.Fingerprint && hostFingerprint.isBlank()) {
            val endpointChanged = originalSshPort != parsedSshPort ||
                !originalSshHost.orEmpty().trim().equals(sshHost.trim(), ignoreCase = true)
            if (!hasSavedHostFingerprint || endpointChanged) {
                return fail(UiText.Resource(R.string.server_error_ssh_host_fingerprint_empty))
            }
        }
        return NewServerSshTunnel(
            enabled = true,
            host = sshHost,
            port = parsedSshPort,
            username = sshUsername,
            authMethod = sshAuthMethod,
            password = sshPassword,
            privateKey = privateKey,
            privateKeyFileName = privateKeyFileName,
            passphrase = privateKeyPassphrase,
            localPort = parsePort(localPort, R.string.server_error_local_port_range) ?: return null,
            connectTimeoutSeconds = parsePositiveInt(connectTimeoutSeconds, R.string.server_error_connect_timeout_positive) ?: return null,
            keepAliveSeconds = parsePositiveInt(keepAliveSeconds, R.string.server_error_keepalive_positive) ?: return null,
            hostKeyPolicy = hostKeyPolicy,
            hostFingerprint = hostFingerprint,
        )
    }

    private fun AddServerUiState.toFrpcStcpVisitorOrNull(): NewServerFrpcStcpVisitor? {
        if (frpcServerAddr.isBlank()) return fail(UiText.Resource(R.string.server_error_frpc_server_addr_empty))
        if (frpcServerName.isBlank()) return fail(UiText.Resource(R.string.server_error_frpc_server_name_empty))
        if (frpcSecretKey.isBlank() && !hasSavedFrpcSecretKey) return fail(UiText.Resource(R.string.server_error_frpc_secret_key_empty))
        return NewServerFrpcStcpVisitor(
            enabled = true,
            serverAddr = frpcServerAddr,
            serverPort = parsePort(frpcServerPort, R.string.server_error_frpc_server_port_range) ?: return null,
            authToken = frpcAuthToken,
            user = frpcUser,
            serverUser = frpcServerUser,
            serverName = frpcServerName,
            secretKey = frpcSecretKey,
            bindPort = parsePort(frpcBindPort, R.string.server_error_frpc_bind_port_range) ?: return null,
            transportProtocol = frpcTransportProtocol,
            wireProtocol = frpcWireProtocol,
        )
    }

    private fun fail(error: UiText): Nothing? {
        _uiState.update { it.copy(error = error) }
        return null
    }

    private fun parsePort(value: String, errorResourceId: Int): Int? {
        val port = value.toIntOrNull()
        if (port == null || port !in 1..65_535) return fail(UiText.Resource(errorResourceId))
        return port
    }

    private fun parsePositiveInt(value: String, errorResourceId: Int): Int? {
        val number = value.toIntOrNull()
        if (number == null || number <= 0) return fail(UiText.Resource(errorResourceId))
        return number
    }

    private fun ServerConfig.toUiState(): AddServerUiState {
        val tunnel = sshTunnel
        val stcp = frpcStcpVisitor
        val displayBaseUrl = safeServerBaseUrlForDisplay(baseUrl)
        return AddServerUiState(
            serverId = id,
            baseUrl = displayBaseUrl.orEmpty(),
            name = if (displayBaseUrl == null) "" else name,
            username = username.orEmpty(),
            password = "",
            connectionMode = when {
                stcp != null -> ServerConnectionMode.FrpcStcp
                tunnel != null -> ServerConnectionMode.Ssh
                else -> ServerConnectionMode.Direct
            },
            sshHost = tunnel?.host.orEmpty(),
            sshPort = tunnel?.port?.toString() ?: "22",
            sshUsername = tunnel?.username.orEmpty(),
            sshAuthMethod = tunnel?.authMethod ?: SshAuthMethod.PrivateKey,
            sshPassword = "",
            privateKeySource = if (tunnel?.privateKeyFileName != null) PrivateKeySource.File else PrivateKeySource.Text,
            privateKey = "",
            privateKeyFileName = tunnel?.privateKeyFileName,
            privateKeyPassphrase = "",
            localPort = tunnel?.localPort?.toString() ?: "4096",
            connectTimeoutSeconds = tunnel?.connectTimeoutSeconds?.toString() ?: "10",
            keepAliveSeconds = tunnel?.keepAliveSeconds?.toString() ?: "30",
            hostKeyPolicy = tunnel?.hostKeyPolicy ?: SshHostKeyPolicy.AcceptNew,
            hostFingerprint = "",
            frpcServerAddr = stcp?.serverAddr.orEmpty(),
            frpcServerPort = stcp?.serverPort?.toString() ?: "7000",
            frpcAuthToken = "",
            frpcUser = stcp?.user.orEmpty(),
            frpcServerUser = stcp?.serverUser.orEmpty(),
            frpcServerName = stcp?.serverName.orEmpty(),
            frpcSecretKey = "",
            frpcBindPort = stcp?.bindPort?.toString() ?: "4096",
            frpcTransportProtocol = stcp?.transportProtocol ?: FrpcTransportProtocol.Tcp,
            frpcWireProtocol = stcp?.wireProtocol ?: FrpcWireProtocol.V1,
            hasSavedOpenCodePassword = passwordKey != null,
            hasSavedSshPassword = tunnel?.passwordKey != null,
            hasSavedPrivateKey = tunnel?.privateKeyKey != null,
            hasSavedPrivateKeyPassphrase = tunnel?.passphraseKey != null,
            hasSavedHostFingerprint = tunnel?.hostFingerprintKey != null,
            originalSshHost = tunnel?.host,
            originalSshPort = tunnel?.port,
            hasSavedFrpcAuthToken = stcp?.authTokenKey != null,
            hasSavedFrpcSecretKey = stcp?.secretKeyKey != null,
            isLoading = false,
            error = if (displayBaseUrl == null) UiText.Resource(R.string.server_error_url_invalid) else null,
        )
    }
}

data class AddServerUiState(
    val serverId: String? = null,
    val baseUrl: String = "",
    val name: String = "",
    val username: String = "",
    val password: String = "",
    val connectionMode: ServerConnectionMode = ServerConnectionMode.Direct,
    val sshHost: String = "",
    val sshPort: String = "22",
    val sshUsername: String = "",
    val sshAuthMethod: SshAuthMethod = SshAuthMethod.PrivateKey,
    val sshPassword: String = "",
    val privateKeySource: PrivateKeySource = PrivateKeySource.Text,
    val privateKey: String = "",
    val privateKeyFileName: String? = null,
    val privateKeyPassphrase: String = "",
    val localPort: String = "4096",
    val connectTimeoutSeconds: String = "10",
    val keepAliveSeconds: String = "30",
    val hostKeyPolicy: SshHostKeyPolicy = SshHostKeyPolicy.AcceptNew,
    val hostFingerprint: String = "",
    val frpcServerAddr: String = "",
    val frpcServerPort: String = "7000",
    val frpcAuthToken: String = "",
    val frpcUser: String = "",
    val frpcServerUser: String = "",
    val frpcServerName: String = "",
    val frpcSecretKey: String = "",
    val frpcBindPort: String = "4096",
    val frpcTransportProtocol: FrpcTransportProtocol = FrpcTransportProtocol.Tcp,
    val frpcWireProtocol: FrpcWireProtocol = FrpcWireProtocol.V1,
    val hasSavedOpenCodePassword: Boolean = false,
    val hasSavedSshPassword: Boolean = false,
    val hasSavedPrivateKey: Boolean = false,
    val hasSavedPrivateKeyPassphrase: Boolean = false,
    val hasSavedHostFingerprint: Boolean = false,
    val originalSshHost: String? = null,
    val originalSshPort: Int? = null,
    val hasSavedFrpcAuthToken: Boolean = false,
    val hasSavedFrpcSecretKey: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isReadingPrivateKey: Boolean = false,
    val showCleartextHttpCredentialsWarning: Boolean = false,
    val error: UiText? = null,
    val savedServerId: String? = null,
) {
    val isEditMode: Boolean
        get() = serverId != null

    override fun toString(): String =
        "AddServerUiState(serverId=${if (serverId == null) "null" else "<redacted>"}, connectionMode=$connectionMode, " +
            "hasSavedOpenCodePassword=$hasSavedOpenCodePassword, hasSavedSshPassword=$hasSavedSshPassword, " +
            "hasSavedPrivateKey=$hasSavedPrivateKey, hasSavedPrivateKeyPassphrase=$hasSavedPrivateKeyPassphrase, " +
            "hasSavedHostFingerprint=$hasSavedHostFingerprint, hasSavedFrpcAuthToken=$hasSavedFrpcAuthToken, " +
            "hasSavedFrpcSecretKey=$hasSavedFrpcSecretKey, isLoading=$isLoading, isSaving=$isSaving, " +
            "showCleartextHttpCredentialsWarning=$showCleartextHttpCredentialsWarning)"
}

enum class PrivateKeySource {
    Text,
    File,
}

enum class ServerConnectionMode {
    Direct,
    Ssh,
    FrpcStcp,
}

private val ServerConnectionMode.usesLocalTunnel: Boolean
    get() = this == ServerConnectionMode.Ssh || this == ServerConnectionMode.FrpcStcp

private fun AddServerUiState.requiresCleartextHttpCredentialsWarning(normalizedBaseUrl: String): Boolean =
    connectionMode == ServerConnectionMode.Direct &&
        isNonLoopbackHttpServerBaseUrl(normalizedBaseUrl) &&
        username.isNotBlank() &&
        (password.isNotBlank() || hasSavedOpenCodePassword)

private const val DEFAULT_TUNNEL_BASE_URL = "http://127.0.0.1:4096"

private val SshAuthMethod.usesPassword: Boolean
    get() = this == SshAuthMethod.Password || this == SshAuthMethod.PasswordAndPrivateKey

private val SshAuthMethod.usesPrivateKey: Boolean
    get() = this == SshAuthMethod.PrivateKey || this == SshAuthMethod.PasswordAndPrivateKey

private fun ServerBaseUrlFailure.toUiText(): UiText = UiText.Resource(
    when (this) {
        ServerBaseUrlFailure.Invalid -> R.string.server_error_url_invalid
        ServerBaseUrlFailure.UserInfo -> R.string.server_error_url_user_info
        ServerBaseUrlFailure.Query -> R.string.server_error_url_query
        ServerBaseUrlFailure.Fragment -> R.string.server_error_url_fragment
    },
)

private fun privateKeyTooLargeUiText(): UiText = UiText.Resource(
    R.string.server_private_key_too_large,
    listOf(SshPrivateKeyLimits.MAX_BYTES / 1024),
)
