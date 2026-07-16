package io.github.ycfeng.ocdeck.feature.server

import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.data.server.NewServerFrpcStcpVisitor
import io.github.ycfeng.ocdeck.data.server.NewServerSshTunnel
import io.github.ycfeng.ocdeck.data.server.ServerConfig
import io.github.ycfeng.ocdeck.data.server.ServerConfigMutationOutcomeUnknownException
import io.github.ycfeng.ocdeck.data.server.ServerEditorRepository
import io.github.ycfeng.ocdeck.data.server.ServerSshTunnelConfig
import io.github.ycfeng.ocdeck.data.server.SshHostKeyPolicy
import io.github.ycfeng.ocdeck.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddServerViewModelTest {
    @Test
    fun newServerFormDoesNotSuggestALocalServer() {
        val state = AddServerUiState()

        assertEquals("", state.baseUrl)
        assertEquals("", state.name)
        assertEquals("4096", state.localPort)
        assertEquals("4096", state.frpcBindPort)
    }

    @Test
    fun sshModeFillsDefaultOpenCodeServiceUrlWhenEmpty() {
        val viewModel = AddServerViewModel(BlockingServerEditorRepository())

        viewModel.onConnectionModeChanged(ServerConnectionMode.Ssh)

        assertEquals("http://127.0.0.1:4096", viewModel.uiState.value.baseUrl)
    }

    @Test
    fun frpcStcpModeFillsDefaultOpenCodeServiceUrlWhenBlank() {
        val viewModel = AddServerViewModel(BlockingServerEditorRepository())
        viewModel.onBaseUrlChanged("   ")

        viewModel.onConnectionModeChanged(ServerConnectionMode.FrpcStcp)

        assertEquals("http://127.0.0.1:4096", viewModel.uiState.value.baseUrl)
    }

    @Test
    fun tunnelModeDoesNotReplaceUserEnteredOpenCodeServiceUrl() {
        val viewModel = AddServerViewModel(BlockingServerEditorRepository())
        viewModel.onBaseUrlChanged("https://opencode.example.com")

        viewModel.onConnectionModeChanged(ServerConnectionMode.Ssh)

        assertEquals("https://opencode.example.com", viewModel.uiState.value.baseUrl)
    }

    @Test
    fun saveIsAtomicSingleFlight() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = BlockingServerEditorRepository()
            val viewModel = AddServerViewModel(repository)
            viewModel.onBaseUrlChanged("https://opencode.example.com")

            viewModel.save()
            viewModel.save()
            runCurrent()

            assertEquals(1, repository.addCalls)
            assertTrue(viewModel.uiState.value.isSaving)

            repository.release.complete(Unit)
            runCurrent()

            assertEquals("server-1", viewModel.uiState.value.savedServerId)
            viewModel.save()
            runCurrent()
            assertEquals(2, repository.addCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun directRemoteHttpWithNewBasicCredentialsRequiresConfirmation() {
        val repository = BlockingServerEditorRepository()
        val viewModel = AddServerViewModel(repository)
        val password = "synthetic-cleartext-password"
        viewModel.onBaseUrlChanged("http://opencode.example.com:4096")
        viewModel.onUsernameChanged("alice")
        viewModel.onPasswordChanged(password)

        viewModel.save()

        assertTrue(viewModel.uiState.value.showCleartextHttpCredentialsWarning)
        assertNull(viewModel.uiState.value.error)
        assertEquals(0, repository.addCalls)
        assertFalse(viewModel.uiState.value.toString().contains(password))
    }

    @Test
    fun cleartextHttpConfirmationConsumesPendingSaveOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = BlockingServerEditorRepository()
            val viewModel = AddServerViewModel(repository)
            viewModel.onBaseUrlChanged("http://opencode.example.com")
            viewModel.onUsernameChanged("alice")
            viewModel.onPasswordChanged("synthetic-password")
            viewModel.save()

            viewModel.confirmCleartextHttpSave()
            viewModel.confirmCleartextHttpSave()
            runCurrent()

            assertFalse(viewModel.uiState.value.showCleartextHttpCredentialsWarning)
            assertEquals(1, repository.addCalls)
            assertTrue(viewModel.uiState.value.isSaving)

            repository.release.complete(Unit)
            runCurrent()

            assertEquals("server-1", viewModel.uiState.value.savedServerId)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun dismissingCleartextHttpWarningDoesNotSave() {
        val repository = BlockingServerEditorRepository()
        val viewModel = AddServerViewModel(repository)
        viewModel.onBaseUrlChanged("http://opencode.example.com")
        viewModel.onUsernameChanged("alice")
        viewModel.onPasswordChanged("synthetic-password")
        viewModel.save()

        viewModel.dismissCleartextHttpWarning()
        viewModel.confirmCleartextHttpSave()

        assertFalse(viewModel.uiState.value.showCleartextHttpCredentialsWarning)
        assertEquals(0, repository.addCalls)
    }

    @Test
    fun warningConfirmationSavesTheValidatedSnapshot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repository = RecordingServerEditorRepository()
            val viewModel = AddServerViewModel(repository)
            viewModel.onBaseUrlChanged("http://opencode.example.com")
            viewModel.onUsernameChanged("alice")
            viewModel.onPasswordChanged("first-synthetic-password")
            viewModel.save()

            viewModel.onPasswordChanged("later-synthetic-password")
            viewModel.confirmCleartextHttpSave()
            runCurrent()

            assertEquals(1, repository.addCalls)
            assertEquals("first-synthetic-password", repository.lastPassword)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun safeDirectConfigurationsSaveWithoutConfirmation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val cases = listOf(
                Triple("https://opencode.example.com", "alice", "synthetic-password"),
                Triple("http://localhost:4096", "alice", "synthetic-password"),
                Triple("http://127.42.0.9:4096", "alice", "synthetic-password"),
                Triple("http://opencode.example.com", "alice", ""),
                Triple("http://opencode.example.com", "", "synthetic-password"),
            )

            cases.forEach { (baseUrl, username, password) ->
                val repository = RecordingServerEditorRepository()
                val viewModel = AddServerViewModel(repository)
                viewModel.onBaseUrlChanged(baseUrl)
                viewModel.onUsernameChanged(username)
                viewModel.onPasswordChanged(password)

                viewModel.save()
                runCurrent()

                assertFalse(baseUrl, viewModel.uiState.value.showCleartextHttpCredentialsWarning)
                assertEquals(baseUrl, 1, repository.addCalls)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun sshAndStcpModesDoNotShowDirectHttpWarning() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val sshRepository = RecordingServerEditorRepository()
            val sshViewModel = AddServerViewModel(sshRepository)
            sshViewModel.onBaseUrlChanged("http://opencode.example.com")
            sshViewModel.onUsernameChanged("alice")
            sshViewModel.onPasswordChanged("synthetic-password")
            sshViewModel.onConnectionModeChanged(ServerConnectionMode.Ssh)
            sshViewModel.onSshHostChanged("ssh.example.com")
            sshViewModel.onSshUsernameChanged("ubuntu")
            sshViewModel.onPrivateKeyChanged("synthetic-private-key")

            sshViewModel.save()
            runCurrent()

            assertFalse(sshViewModel.uiState.value.showCleartextHttpCredentialsWarning)
            assertEquals(1, sshRepository.addCalls)

            val stcpRepository = RecordingServerEditorRepository()
            val stcpViewModel = AddServerViewModel(stcpRepository)
            stcpViewModel.onBaseUrlChanged("http://opencode.example.com")
            stcpViewModel.onUsernameChanged("alice")
            stcpViewModel.onPasswordChanged("synthetic-password")
            stcpViewModel.onConnectionModeChanged(ServerConnectionMode.FrpcStcp)
            stcpViewModel.onFrpcServerAddrChanged("frps.example.com")
            stcpViewModel.onFrpcServerNameChanged("opencode")
            stcpViewModel.onFrpcSecretKeyChanged("synthetic-stcp-secret")

            stcpViewModel.save()
            runCurrent()

            assertFalse(stcpViewModel.uiState.value.showCleartextHttpCredentialsWarning)
            assertEquals(1, stcpRepository.addCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun editWithSavedBasicPasswordRequiresConfirmationUnlessUsernameIsCleared() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val server = ServerConfig(
                id = "server-1",
                name = "Remote",
                baseUrl = "http://opencode.example.com",
                username = "alice",
                passwordKey = "synthetic-password-alias",
            )
            val warningRepository = RecordingServerEditorRepository(server)
            val warningViewModel = AddServerViewModel(warningRepository, server.id)
            runCurrent()

            warningViewModel.save()

            assertTrue(warningViewModel.uiState.value.showCleartextHttpCredentialsWarning)
            assertEquals(0, warningRepository.updateCalls)

            val clearedRepository = RecordingServerEditorRepository(server)
            val clearedViewModel = AddServerViewModel(clearedRepository, server.id)
            runCurrent()
            clearedViewModel.onUsernameChanged("   ")

            clearedViewModel.save()
            runCurrent()

            assertFalse(clearedViewModel.uiState.value.showCleartextHttpCredentialsWarning)
            assertEquals(1, clearedRepository.updateCalls)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun invalidUrlFailsBeforeCleartextHttpWarning() {
        val repository = RecordingServerEditorRepository()
        val viewModel = AddServerViewModel(repository)
        viewModel.onBaseUrlChanged("http://opencode.example.com?token=synthetic")
        viewModel.onUsernameChanged("alice")
        viewModel.onPasswordChanged("synthetic-password")

        viewModel.save()

        assertEquals(UiText.Resource(R.string.server_error_url_query), viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.showCleartextHttpCredentialsWarning)
        assertEquals(0, repository.addCalls)
    }

    @Test
    fun fingerprintEndpointChangeRequiresNewPinBeforeRepositoryUpdate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val server = ServerConfig(
                id = "server-1",
                name = "Remote",
                baseUrl = "https://remote.example.com",
                sshTunnel = ServerSshTunnelConfig(
                    host = "ssh.example.com",
                    username = "ubuntu",
                    privateKeyKey = "private-key-alias",
                    hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
                    hostFingerprintKey = "fingerprint-alias",
                ),
            )
            val repository = RecordingServerEditorRepository(server)
            val viewModel = AddServerViewModel(repository, server.id)
            runCurrent()

            viewModel.onSshHostChanged("new-ssh.example.com")
            viewModel.save()

            assertEquals(0, repository.updateCalls)
            assertEquals(
                UiText.Resource(R.string.server_error_ssh_host_fingerprint_empty),
                viewModel.uiState.value.error,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun acceptNewClearsManuallyEnteredFingerprint() {
        val viewModel = AddServerViewModel(BlockingServerEditorRepository())

        viewModel.onHostKeyPolicyChanged(SshHostKeyPolicy.Fingerprint)
        viewModel.onHostFingerprintChanged("SHA256:manual-fingerprint")
        viewModel.onHostKeyPolicyChanged(SshHostKeyPolicy.AcceptNew)

        assertEquals("", viewModel.uiState.value.hostFingerprint)
    }

    @Test
    fun unknownSaveOutcomeUsesDedicatedLocalizedError() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = AddServerViewModel(UnknownOutcomeServerEditorRepository())
            viewModel.onBaseUrlChanged("https://opencode.example.com")

            viewModel.save()
            runCurrent()

            assertEquals(
                UiText.Resource(R.string.server_error_save_outcome_unknown),
                viewModel.uiState.value.error,
            )
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class BlockingServerEditorRepository : ServerEditorRepository {
    val release = CompletableDeferred<Unit>()
    var addCalls = 0

    override suspend fun getServer(serverId: String): ServerConfig = error("Not used")

    override suspend fun addServer(
        baseUrl: String,
        name: String,
        username: String?,
        password: String?,
        sshTunnel: NewServerSshTunnel?,
        frpcStcpVisitor: NewServerFrpcStcpVisitor?,
    ): ServerConfig {
        addCalls += 1
        release.await()
        return ServerConfig(
            id = "server-$addCalls",
            name = name,
            baseUrl = baseUrl,
        )
    }

    override suspend fun updateServer(
        serverId: String,
        baseUrl: String,
        name: String,
        username: String?,
        password: String?,
        sshTunnel: NewServerSshTunnel?,
        frpcStcpVisitor: NewServerFrpcStcpVisitor?,
    ): ServerConfig = error("Not used")
}

private class RecordingServerEditorRepository(
    private val server: ServerConfig? = null,
) : ServerEditorRepository {
    var addCalls = 0
    var updateCalls = 0
    var lastPassword: String? = null

    override suspend fun getServer(serverId: String): ServerConfig = requireNotNull(server)

    override suspend fun addServer(
        baseUrl: String,
        name: String,
        username: String?,
        password: String?,
        sshTunnel: NewServerSshTunnel?,
        frpcStcpVisitor: NewServerFrpcStcpVisitor?,
    ): ServerConfig {
        addCalls += 1
        lastPassword = password
        return ServerConfig(
            id = "server-$addCalls",
            name = name,
            baseUrl = baseUrl,
            username = username,
        )
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
        updateCalls += 1
        lastPassword = password
        return requireNotNull(server)
    }
}

private class UnknownOutcomeServerEditorRepository : ServerEditorRepository {
    override suspend fun getServer(serverId: String): ServerConfig = error("Not used")

    override suspend fun addServer(
        baseUrl: String,
        name: String,
        username: String?,
        password: String?,
        sshTunnel: NewServerSshTunnel?,
        frpcStcpVisitor: NewServerFrpcStcpVisitor?,
    ): ServerConfig = throw ServerConfigMutationOutcomeUnknownException()

    override suspend fun updateServer(
        serverId: String,
        baseUrl: String,
        name: String,
        username: String?,
        password: String?,
        sshTunnel: NewServerSshTunnel?,
        frpcStcpVisitor: NewServerFrpcStcpVisitor?,
    ): ServerConfig = throw ServerConfigMutationOutcomeUnknownException()
}
