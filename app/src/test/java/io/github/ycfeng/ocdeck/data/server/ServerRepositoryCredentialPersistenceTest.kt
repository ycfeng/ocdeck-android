package io.github.ycfeng.ocdeck.data.server

import io.github.ycfeng.ocdeck.core.network.FrpcStcpVisitorManager
import io.github.ycfeng.ocdeck.core.network.OpenCodeApiFactory
import io.github.ycfeng.ocdeck.core.network.OpenCodeHealthProbe
import io.github.ycfeng.ocdeck.core.network.OpenCodeHealthProbeFactory
import io.github.ycfeng.ocdeck.core.network.ServerHealthDto
import io.github.ycfeng.ocdeck.core.network.SshTunnelManager
import io.github.ycfeng.ocdeck.core.security.CredentialStore
import io.github.ycfeng.ocdeck.core.security.CredentialStoreException
import io.github.ycfeng.ocdeck.core.security.CredentialStoreOperation
import io.github.ycfeng.ocdeck.core.security.Redactor
import io.github.ycfeng.ocdeck.frpcstcpvisitor.UnavailableFrpcStcpVisitorClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryCredentialPersistenceTest {
    @Test
    fun addRollsBackAllCandidatesWhenSecondSecretWriteFails() = runTest {
        val preferences = FakeServerConfigStore()
        val credentials = FakeCredentialStore().apply {
            failPutAt = 2
            writeBeforePutFailure = true
        }
        val failure = CredentialStoreException(CredentialStoreOperation.Write)
        credentials.putFailure = failure

        val thrown = captureFailure {
            repository(preferences, credentials).addServer(
                baseUrl = BASE_URL,
                name = "Remote",
                username = "opencode",
                password = OPEN_CODE_SECRET,
                sshTunnel = sshInput(
                    authMethod = SshAuthMethod.Password,
                    password = SSH_PASSWORD_SECRET,
                ),
            )
        }

        assertSame(failure, thrown)
        assertTrue(preferences.getServers().isEmpty())
        assertTrue(credentials.values.isEmpty())
        assertEquals(2, credentials.putAliases.size)
        assertTrue(credentials.putAliases[0].contains("credential-candidate-open-code-password"))
        assertTrue(credentials.putAliases[1].contains("credential-candidate-ssh-password"))
        assertAliasesDoNotContainSecrets(credentials.putAliases)
        assertFalse(thrown.toString().contains(OPEN_CODE_SECRET))
        assertFalse(thrown.toString().contains(SSH_PASSWORD_SECRET))
    }

    @Test
    fun addRollsBackAllCandidatesWhenConfigCommitFails() = runTest {
        val failure = IllegalStateException("config commit failed")
        val preferences = FakeServerConfigStore().apply { upsertFailure = failure }
        val credentials = FakeCredentialStore()

        val thrown = captureFailure {
            repository(preferences, credentials).addServer(
                baseUrl = BASE_URL,
                name = "Remote",
                username = null,
                password = OPEN_CODE_SECRET,
                sshTunnel = sshInput(
                    authMethod = SshAuthMethod.Password,
                    password = SSH_PASSWORD_SECRET,
                ),
            )
        }

        assertSame(failure, thrown)
        assertTrue(preferences.getServers().isEmpty())
        assertTrue(credentials.values.isEmpty())
        assertEquals(credentials.putAliases.toSet(), credentials.removeAliases.toSet())
    }

    @Test
    fun updateTreatsPersistThenThrowAsCommittedAndInvalidatesRuntime() = runTest {
        val current = sshServer()
        val failure = IllegalStateException("late config failure")
        val preferences = FakeServerConfigStore(listOf(current)).apply {
            upsertFailureAfterPersist = failure
        }
        val credentials = FakeCredentialStore(sshSecretValues())
        val runtime = FakeServerRuntimeInvalidator()
        val repository = repository(preferences, credentials, runtime)

        val updated = repository.updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = NEW_OPEN_CODE_SECRET,
            sshTunnel = null,
        )

        val newAlias = requireNotNull(updated.passwordKey)
        assertEquals(listOf(updated), preferences.getServers())
        assertEquals(NEW_OPEN_CODE_SECRET, credentials.values[newAlias])
        assertFalse(credentials.removeAliases.contains(newAlias))
        assertEquals(
            listOf(RuntimeInvalidationCall(current.id, minimumConfigEpoch = 2L, ssh = true, stcp = false)),
            runtime.calls,
        )
        assertEquals(2L, repository.getConnection(current.id).transportIdentity.configEpoch)
    }

    @Test
    fun cancellationBeforeCommitRollsBackCandidates() = runTest {
        val preferences = FakeServerConfigStore()
        val credentials = FakeCredentialStore().apply {
            afterPut = {
                currentCoroutineContext().cancel(CancellationException("cancel before commit"))
            }
        }
        val repository = repository(preferences, credentials)

        val operation = async {
            repository.addServer(
                baseUrl = BASE_URL,
                name = "Remote",
                username = null,
                password = OPEN_CODE_SECRET,
            )
        }
        val thrown = captureFailure { operation.await() }

        assertTrue(thrown is CancellationException)
        assertTrue(preferences.getServers().isEmpty())
        assertTrue(credentials.values.isEmpty())
        assertEquals(credentials.putAliases, credentials.removeAliases)
    }

    @Test
    fun cancellationThrownAfterPersistDoesNotRollBackCommittedCandidate() = runTest {
        val current = directServer(passwordKey = OLD_OPEN_CODE_ALIAS)
        val preferences = FakeServerConfigStore(listOf(current)).apply {
            upsertFailureAfterPersist = CancellationException("late cancellation")
        }
        val credentials = FakeCredentialStore(mapOf(OLD_OPEN_CODE_ALIAS to OLD_OPEN_CODE_SECRET))
        val repository = repository(preferences, credentials)

        val updated = repository.updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = NEW_OPEN_CODE_SECRET,
        )

        val newAlias = requireNotNull(updated.passwordKey)
        assertEquals(listOf(updated), preferences.getServers())
        assertEquals(NEW_OPEN_CODE_SECRET, credentials.values[newAlias])
        assertFalse(credentials.removeAliases.contains(newAlias))
        assertEquals(2L, repository.getConnection(current.id).transportIdentity.configEpoch)
    }

    @Test
    fun unknownCommitOutcomeKeepsCandidate() = runTest {
        val preferences = FakeServerConfigStore().apply {
            upsertFailureAfterPersist = IllegalStateException("late config failure")
            failReadsAfterMutationAttempt = true
        }
        val credentials = FakeCredentialStore()
        val runtime = FakeServerRuntimeInvalidator()

        val thrown = captureFailure {
            repository(preferences, credentials, runtime).addServer(
                baseUrl = BASE_URL,
                name = "Remote",
                username = null,
                password = OPEN_CODE_SECRET,
            )
        }

        assertTrue(thrown is ServerConfigMutationOutcomeUnknownException)
        val persisted = preferences.persistedServers.single()
        assertTrue(credentials.values.containsKey(persisted.passwordKey))
        assertEquals(1, credentials.values.size)
        assertTrue(credentials.removeAliases.isEmpty())
        assertFalse(thrown.toString().contains(OPEN_CODE_SECRET))
        assertTrue(thrown.message.orEmpty().contains("outcome is unknown"))
        assertEquals(
            listOf(
                RuntimeInvalidationCall(
                    serverId = persisted.id,
                    minimumConfigEpoch = 2L,
                    ssh = false,
                    stcp = false,
                ),
            ),
            runtime.calls,
        )
    }

    @Test
    fun updateUnknownOutcomeAdvancesEpochAndInvalidatesRuntime() = runTest {
        val current = sshServer()
        val preferences = FakeServerConfigStore(listOf(current)).apply {
            upsertFailureAfterPersist = IllegalStateException("late update failure")
            failReadsAfterMutationAttempt = true
        }
        val credentials = FakeCredentialStore(sshSecretValues())
        val runtime = FakeServerRuntimeInvalidator()

        val thrown = captureFailure {
            repository(preferences, credentials, runtime).updateServer(
                serverId = current.id,
                baseUrl = current.baseUrl,
                name = current.name,
                username = current.username,
                password = NEW_OPEN_CODE_SECRET,
                sshTunnel = null,
            )
        }

        assertTrue(thrown is ServerConfigMutationOutcomeUnknownException)
        assertNull(preferences.persistedServers.single().sshTunnel)
        assertEquals(
            listOf(RuntimeInvalidationCall(current.id, minimumConfigEpoch = 2L, ssh = true, stcp = false)),
            runtime.calls,
        )
    }

    @Test
    fun rollbackCleanupFailureDoesNotOverridePrimaryFailure() = runTest {
        val failure = IllegalStateException("config commit failed")
        val preferences = FakeServerConfigStore().apply { upsertFailure = failure }
        val credentials = FakeCredentialStore().apply { failAllRemoves = true }

        val thrown = captureFailure {
            repository(preferences, credentials).addServer(
                baseUrl = BASE_URL,
                name = "Remote",
                username = null,
                password = OPEN_CODE_SECRET,
            )
        }

        assertSame(failure, thrown)
        assertTrue(preferences.getServers().isEmpty())
        assertEquals(credentials.putAliases, credentials.removeAliases)
        assertFalse(thrown.toString().contains(OPEN_CODE_SECRET))
    }

    @Test
    fun updateConfigFailurePreservesOldAliasAndValue() = runTest {
        val current = directServer(passwordKey = OLD_OPEN_CODE_ALIAS)
        val failure = IllegalStateException("config commit failed")
        val preferences = FakeServerConfigStore(listOf(current)).apply { upsertFailure = failure }
        val credentials = FakeCredentialStore(mapOf(OLD_OPEN_CODE_ALIAS to OLD_OPEN_CODE_SECRET))

        val thrown = captureFailure {
            repository(preferences, credentials).updateServer(
                serverId = current.id,
                baseUrl = current.baseUrl,
                name = current.name,
                username = current.username,
                password = NEW_OPEN_CODE_SECRET,
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf(current), preferences.getServers())
        assertEquals(mapOf(OLD_OPEN_CODE_ALIAS to OLD_OPEN_CODE_SECRET), credentials.values)
        assertFalse(credentials.putAliases.single().contains(NEW_OPEN_CODE_SECRET))
    }

    @Test
    fun updateSuccessSwitchesAliasThenDeletesReplacedAlias() = runTest {
        val current = directServer(passwordKey = OLD_OPEN_CODE_ALIAS)
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(mapOf(OLD_OPEN_CODE_ALIAS to OLD_OPEN_CODE_SECRET))

        val updated = repository(preferences, credentials).updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = NEW_OPEN_CODE_SECRET,
        )

        val newAlias = requireNotNull(updated.passwordKey)
        assertNotEquals(OLD_OPEN_CODE_ALIAS, newAlias)
        assertEquals(NEW_OPEN_CODE_SECRET, credentials.values[newAlias])
        assertFalse(credentials.values.containsKey(OLD_OPEN_CODE_ALIAS))
        assertEquals(listOf(updated), preferences.getServers())
        assertTrue(newAlias.contains("credential-candidate-open-code-password"))
    }

    @Test
    fun updateOnlyRotatesCredentialWithNewValue() = runTest {
        val current = sshServer()
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(sshSecretValues())

        val updated = repository(preferences, credentials).updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = "",
            sshTunnel = sshInput(
                authMethod = SshAuthMethod.PasswordAndPrivateKey,
                password = NEW_SSH_PASSWORD_SECRET,
                hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
                hostFingerprint = "",
            ),
        )

        val updatedSsh = requireNotNull(updated.sshTunnel)
        assertEquals(OLD_OPEN_CODE_ALIAS, updated.passwordKey)
        assertNotEquals(OLD_SSH_PASSWORD_ALIAS, updatedSsh.passwordKey)
        assertEquals(OLD_PRIVATE_KEY_ALIAS, updatedSsh.privateKeyKey)
        assertEquals(OLD_PASSPHRASE_ALIAS, updatedSsh.passphraseKey)
        assertEquals(OLD_FINGERPRINT_ALIAS, updatedSsh.hostFingerprintKey)
        assertEquals("id_ed25519", updatedSsh.privateKeyFileName)
        assertEquals(1, credentials.putAliases.size)
        assertEquals(NEW_SSH_PASSWORD_SECRET, credentials.values[updatedSsh.passwordKey])
        assertFalse(credentials.values.containsKey(OLD_SSH_PASSWORD_ALIAS))
        assertEquals(OLD_PRIVATE_KEY_SECRET, credentials.values[OLD_PRIVATE_KEY_ALIAS])
        assertEquals(OLD_PASSPHRASE_SECRET, credentials.values[OLD_PASSPHRASE_ALIAS])
        assertEquals(OLD_FINGERPRINT_SECRET, credentials.values[OLD_FINGERPRINT_ALIAS])
    }

    @Test
    fun updateClearsCredentialReferenceAndDeletesOldAliasAfterCommit() = runTest {
        val current = sshServer()
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(sshSecretValues())

        val updated = repository(preferences, credentials).updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = null,
            sshTunnel = sshInput(
                authMethod = SshAuthMethod.PrivateKey,
                hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
                hostFingerprint = null,
            ),
        )

        val updatedSsh = requireNotNull(updated.sshTunnel)
        assertNull(updatedSsh.passwordKey)
        assertEquals(OLD_PRIVATE_KEY_ALIAS, updatedSsh.privateKeyKey)
        assertTrue(credentials.putAliases.isEmpty())
        assertFalse(credentials.values.containsKey(OLD_SSH_PASSWORD_ALIAS))
        assertTrue(credentials.removeAliases.contains(OLD_SSH_PASSWORD_ALIAS))
    }

    @Test
    fun tofuConfigFailureCleansCandidateAndPreservesOldPin() = runTest {
        val current = tofuServer()
        val failure = IllegalStateException("config commit failed")
        val preferences = FakeServerConfigStore(listOf(current)).apply { upsertFailure = failure }
        val credentials = FakeCredentialStore(sshSecretValues())
        val repository = repository(preferences, credentials)

        val thrown = captureFailure {
            repository.persistAcceptedSshFingerprint(
                snapshotServer = current,
                snapshotConfig = requireNotNull(current.sshTunnel),
                hostFingerprint = NEW_FINGERPRINT_SECRET,
            )
        }

        assertSame(failure, thrown)
        assertEquals(listOf(current), preferences.getServers())
        assertEquals(OLD_FINGERPRINT_SECRET, credentials.values[OLD_FINGERPRINT_ALIAS])
        assertEquals(sshSecretValues(), credentials.values)
    }

    @Test
    fun tofuSuccessSwitchesPinAliasAndDeletesOldPin() = runTest {
        val current = tofuServer()
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(sshSecretValues())
        val repository = repository(preferences, credentials)

        repository.persistAcceptedSshFingerprint(
            snapshotServer = current,
            snapshotConfig = requireNotNull(current.sshTunnel),
            hostFingerprint = NEW_FINGERPRINT_SECRET,
        )

        val updated = preferences.getServers().single()
        val newAlias = requireNotNull(updated.sshTunnel?.hostFingerprintKey)
        assertNotEquals(OLD_FINGERPRINT_ALIAS, newAlias)
        assertEquals(NEW_FINGERPRINT_SECRET, credentials.values[newAlias])
        assertFalse(credentials.values.containsKey(OLD_FINGERPRINT_ALIAS))
    }

    @Test
    fun tofuUnknownOutcomeAdvancesEpochAndInvalidatesRuntime() = runTest {
        val current = tofuServer()
        val preferences = FakeServerConfigStore(listOf(current)).apply {
            upsertFailureAfterPersist = IllegalStateException("late TOFU failure")
            failReadsAfterMutationAttempt = true
        }
        val credentials = FakeCredentialStore(sshSecretValues())
        val runtime = FakeServerRuntimeInvalidator()

        val thrown = captureFailure {
            repository(preferences, credentials, runtime).persistAcceptedSshFingerprint(
                snapshotServer = current,
                snapshotConfig = requireNotNull(current.sshTunnel),
                hostFingerprint = NEW_FINGERPRINT_SECRET,
            )
        }

        assertTrue(thrown is ServerConfigMutationOutcomeUnknownException)
        val newAlias = requireNotNull(preferences.persistedServers.single().sshTunnel?.hostFingerprintKey)
        assertNotEquals(OLD_FINGERPRINT_ALIAS, newAlias)
        assertEquals(NEW_FINGERPRINT_SECRET, credentials.values[newAlias])
        assertEquals(
            listOf(RuntimeInvalidationCall(current.id, minimumConfigEpoch = 2L, ssh = true, stcp = false)),
            runtime.calls,
        )
    }

    @Test
    fun tofuSnapshotMismatchDoesNotWriteOrReplacePin() = runTest {
        val snapshot = tofuServer()
        val current = snapshot.copy(
            sshTunnel = requireNotNull(snapshot.sshTunnel).copy(host = "new-ssh.example.com"),
        )
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(sshSecretValues())

        repository(preferences, credentials).persistAcceptedSshFingerprint(
            snapshotServer = snapshot,
            snapshotConfig = requireNotNull(snapshot.sshTunnel),
            hostFingerprint = NEW_FINGERPRINT_SECRET,
        )

        assertEquals(listOf(current), preferences.getServers())
        assertTrue(credentials.putAliases.isEmpty())
        assertEquals(OLD_FINGERPRINT_SECRET, credentials.values[OLD_FINGERPRINT_ALIAS])
    }

    @Test
    fun oldAliasDeleteFailureDoesNotRollBackCommittedConfig() = runTest {
        val current = directServer(passwordKey = OLD_OPEN_CODE_ALIAS)
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(mapOf(OLD_OPEN_CODE_ALIAS to OLD_OPEN_CODE_SECRET)).apply {
            removeFailures += OLD_OPEN_CODE_ALIAS
        }

        val updated = repository(preferences, credentials).updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = NEW_OPEN_CODE_SECRET,
        )

        val newAlias = requireNotNull(updated.passwordKey)
        assertEquals(listOf(updated), preferences.getServers())
        assertEquals(NEW_OPEN_CODE_SECRET, credentials.values[newAlias])
        assertEquals(OLD_OPEN_CODE_SECRET, credentials.values[OLD_OPEN_CODE_ALIAS])
        assertTrue(credentials.removeAliases.contains(OLD_OPEN_CODE_ALIAS))
    }

    @Test
    fun deleteServerKeepsAliasStillReferencedByAnotherServer() = runTest {
        val sharedAlias = "legacy-shared-password-alias"
        val first = directServer(id = "first", passwordKey = sharedAlias)
        val second = directServer(id = "second", passwordKey = sharedAlias)
        val preferences = FakeServerConfigStore(listOf(first, second))
        val credentials = FakeCredentialStore(mapOf(sharedAlias to OLD_OPEN_CODE_SECRET))

        repository(preferences, credentials).deleteServer(first)

        assertEquals(listOf(second), preferences.getServers())
        assertEquals(OLD_OPEN_CODE_SECRET, credentials.values[sharedAlias])
        assertFalse(credentials.removeAliases.contains(sharedAlias))
    }

    @Test
    fun deletePersistThenThrowStillCleansAliasesAndInvalidatesRuntime() = runTest {
        val current = sshServer()
        val preferences = FakeServerConfigStore(listOf(current)).apply {
            deleteFailureAfterPersist = IllegalStateException("late delete failure")
        }
        val credentials = FakeCredentialStore(sshSecretValues())
        val runtime = FakeServerRuntimeInvalidator()

        repository(preferences, credentials, runtime).deleteServer(current)

        assertTrue(preferences.getServers().isEmpty())
        assertTrue(credentials.values.isEmpty())
        assertEquals(sshSecretValues().keys, credentials.removeAliases.toSet())
        assertEquals(
            listOf(RuntimeInvalidationCall(current.id, minimumConfigEpoch = 2L, ssh = true, stcp = true)),
            runtime.calls,
        )
    }

    @Test
    fun deleteUnknownOutcomeAdvancesEpochAndInvalidatesRuntime() = runTest {
        val current = sshServer()
        val preferences = FakeServerConfigStore(listOf(current)).apply {
            deleteFailureAfterPersist = IllegalStateException("late delete failure")
            failReadsAfterMutationAttempt = true
        }
        val credentials = FakeCredentialStore(sshSecretValues())
        val runtime = FakeServerRuntimeInvalidator()

        val thrown = captureFailure {
            repository(preferences, credentials, runtime).deleteServer(current)
        }

        assertTrue(thrown is ServerConfigMutationOutcomeUnknownException)
        assertTrue(preferences.persistedServers.isEmpty())
        assertEquals(
            listOf(RuntimeInvalidationCall(current.id, minimumConfigEpoch = 2L, ssh = true, stcp = true)),
            runtime.calls,
        )
        assertEquals(sshSecretValues(), credentials.values)
    }

    @Test
    fun acceptNewHostChangeClearsInheritedFingerprintForNewTofu() = runTest {
        val current = tofuServer()
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(sshSecretValues())

        val updated = repository(preferences, credentials).updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = null,
            sshTunnel = sshInput(
                host = "new-ssh.example.com",
                authMethod = SshAuthMethod.PasswordAndPrivateKey,
                hostKeyPolicy = SshHostKeyPolicy.AcceptNew,
                hostFingerprint = NEW_FINGERPRINT_SECRET,
            ),
        )

        assertEquals("new-ssh.example.com", updated.sshTunnel?.host)
        assertNull(updated.sshTunnel?.hostFingerprintKey)
        assertFalse(credentials.values.containsKey(OLD_FINGERPRINT_ALIAS))
        assertFalse(credentials.values.containsValue(NEW_FINGERPRINT_SECRET))
        assertTrue(credentials.putAliases.isEmpty())
    }

    @Test
    fun acceptNewSameEndpointIgnoresProvidedFingerprintAndRetainsPersistedTofuPin() = runTest {
        val current = tofuServer()
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(sshSecretValues())

        val updated = repository(preferences, credentials).updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = null,
            sshTunnel = sshInput(
                authMethod = SshAuthMethod.PasswordAndPrivateKey,
                hostKeyPolicy = SshHostKeyPolicy.AcceptNew,
                hostFingerprint = NEW_FINGERPRINT_SECRET,
            ),
        )

        assertEquals(OLD_FINGERPRINT_ALIAS, updated.sshTunnel?.hostFingerprintKey)
        assertEquals(OLD_FINGERPRINT_SECRET, credentials.values[OLD_FINGERPRINT_ALIAS])
        assertFalse(credentials.values.containsValue(NEW_FINGERPRINT_SECRET))
        assertTrue(credentials.putAliases.isEmpty())
    }

    @Test
    fun acceptNewPortChangeClearsInheritedFingerprintForNewTofu() = runTest {
        val current = tofuServer()
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(sshSecretValues())

        val updated = repository(preferences, credentials).updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = null,
            sshTunnel = sshInput(
                port = 2222,
                authMethod = SshAuthMethod.PasswordAndPrivateKey,
                hostKeyPolicy = SshHostKeyPolicy.AcceptNew,
            ),
        )

        assertEquals(2222, updated.sshTunnel?.port)
        assertNull(updated.sshTunnel?.hostFingerprintKey)
        assertFalse(credentials.values.containsKey(OLD_FINGERPRINT_ALIAS))
    }

    @Test
    fun fingerprintPortChangeRequiresNewFingerprint() = runTest {
        val current = sshServer()
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(sshSecretValues())

        val thrown = captureFailure {
            repository(preferences, credentials).updateServer(
                serverId = current.id,
                baseUrl = current.baseUrl,
                name = current.name,
                username = current.username,
                password = null,
                sshTunnel = sshInput(
                    port = 2222,
                    authMethod = SshAuthMethod.PasswordAndPrivateKey,
                    hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
                    hostFingerprint = null,
                ),
            )
        }

        assertTrue(thrown is IllegalArgumentException)
        assertEquals(listOf(current), preferences.getServers())
        assertEquals(sshSecretValues(), credentials.values)
        assertTrue(credentials.putAliases.isEmpty())
    }

    @Test
    fun fingerprintHostChangeWithNewPinRotatesFingerprint() = runTest {
        val current = sshServer()
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(sshSecretValues())

        val updated = repository(preferences, credentials).updateServer(
            serverId = current.id,
            baseUrl = current.baseUrl,
            name = current.name,
            username = current.username,
            password = null,
            sshTunnel = sshInput(
                host = "new-ssh.example.com",
                authMethod = SshAuthMethod.PasswordAndPrivateKey,
                hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
                hostFingerprint = NEW_FINGERPRINT_SECRET,
            ),
        )

        val newAlias = requireNotNull(updated.sshTunnel?.hostFingerprintKey)
        assertNotEquals(OLD_FINGERPRINT_ALIAS, newAlias)
        assertEquals(NEW_FINGERPRINT_SECRET, credentials.values[newAlias])
        assertFalse(credentials.values.containsKey(OLD_FINGERPRINT_ALIAS))
    }

    @Test
    fun secretBearingInputsDoNotExposeValuesThroughToString() {
        val ssh = sshInput(
            authMethod = SshAuthMethod.PasswordAndPrivateKey,
            password = SSH_PASSWORD_SECRET,
            privateKey = OLD_PRIVATE_KEY_SECRET,
            passphrase = OLD_PASSPHRASE_SECRET,
            hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
            hostFingerprint = OLD_FINGERPRINT_SECRET,
        )
        val stcp = NewServerFrpcStcpVisitor(
            enabled = true,
            serverAddr = "frps.example.com",
            serverPort = 7000,
            authToken = "frps-auth-value",
            user = null,
            serverUser = null,
            serverName = "opencode_stcp",
            secretKey = "stcp-secret-value",
            bindPort = 4096,
            transportProtocol = FrpcTransportProtocol.Tcp,
            wireProtocol = FrpcWireProtocol.V1,
        )

        listOf(
            SSH_PASSWORD_SECRET,
            OLD_PRIVATE_KEY_SECRET,
            OLD_PASSPHRASE_SECRET,
            OLD_FINGERPRINT_SECRET,
        ).forEach { assertFalse(ssh.toString().contains(it)) }
        assertFalse(stcp.toString().contains("frps-auth-value"))
        assertFalse(stcp.toString().contains("stcp-secret-value"))
    }

    @Test
    fun checkHealthPropagatesJvmErrorWithoutWrapping() = runTest {
        val current = directServer(passwordKey = OLD_OPEN_CODE_ALIAS)
        val expected = AssertionError("fatal credential read")
        val preferences = FakeServerConfigStore(listOf(current))
        val credentials = FakeCredentialStore(mapOf(OLD_OPEN_CODE_ALIAS to OLD_OPEN_CODE_SECRET)).apply {
            getFailure = expected
        }

        val thrown = captureFailure {
            repository(preferences, credentials).checkHealth(current)
        }

        assertSame(expected, thrown)
    }

    private fun repository(
        preferences: ServerConfigStore,
        credentials: CredentialStore,
        runtimeInvalidator: ServerRuntimeInvalidator? = null,
    ): ServerRepository {
        val redactor = Redactor()
        return ServerRepository(
            preferencesStore = preferences,
            credentialStore = credentials,
            apiFactory = OpenCodeApiFactory(TEST_JSON, redactor),
            sshTunnelManager = SshTunnelManager(),
            frpcStcpVisitorManager = FrpcStcpVisitorManager(
                client = UnavailableFrpcStcpVisitorClient("unavailable in credential persistence tests"),
                healthProbeFactory = OpenCodeHealthProbeFactory { _, _, _ ->
                    OpenCodeHealthProbe { ServerHealthDto(healthy = true, version = "test") }
                },
            ),
            runtimeInvalidator = runtimeInvalidator,
        )
    }

    private fun directServer(
        id: String = "remote",
        passwordKey: String? = null,
    ): ServerConfig = ServerConfig(
        id = id,
        name = "Remote",
        baseUrl = BASE_URL,
        username = "opencode",
        passwordKey = passwordKey,
    )

    private fun sshServer(): ServerConfig = directServer(passwordKey = OLD_OPEN_CODE_ALIAS).copy(
        sshTunnel = ServerSshTunnelConfig(
            host = "ssh.example.com",
            username = "ubuntu",
            authMethod = SshAuthMethod.PasswordAndPrivateKey,
            passwordKey = OLD_SSH_PASSWORD_ALIAS,
            privateKeyKey = OLD_PRIVATE_KEY_ALIAS,
            privateKeyFileName = "id_ed25519",
            passphraseKey = OLD_PASSPHRASE_ALIAS,
            hostKeyPolicy = SshHostKeyPolicy.Fingerprint,
            hostFingerprintKey = OLD_FINGERPRINT_ALIAS,
        ),
    )

    private fun tofuServer(): ServerConfig = sshServer().let { server ->
        server.copy(
            sshTunnel = requireNotNull(server.sshTunnel).copy(hostKeyPolicy = SshHostKeyPolicy.AcceptNew),
        )
    }

    private fun sshInput(
        host: String = "ssh.example.com",
        port: Int = 22,
        authMethod: SshAuthMethod,
        password: String = "",
        privateKey: String = "",
        passphrase: String = "",
        hostKeyPolicy: SshHostKeyPolicy = SshHostKeyPolicy.AcceptNew,
        hostFingerprint: String? = null,
    ): NewServerSshTunnel = NewServerSshTunnel(
        enabled = true,
        host = host,
        port = port,
        username = "ubuntu",
        authMethod = authMethod,
        password = password,
        privateKey = privateKey,
        privateKeyFileName = null,
        passphrase = passphrase,
        localPort = 4096,
        connectTimeoutSeconds = 10,
        keepAliveSeconds = 30,
        hostKeyPolicy = hostKeyPolicy,
        hostFingerprint = hostFingerprint,
    )

    private fun sshSecretValues(): Map<String, String> = mapOf(
        OLD_OPEN_CODE_ALIAS to OLD_OPEN_CODE_SECRET,
        OLD_SSH_PASSWORD_ALIAS to SSH_PASSWORD_SECRET,
        OLD_PRIVATE_KEY_ALIAS to OLD_PRIVATE_KEY_SECRET,
        OLD_PASSPHRASE_ALIAS to OLD_PASSPHRASE_SECRET,
        OLD_FINGERPRINT_ALIAS to OLD_FINGERPRINT_SECRET,
    )

    private fun assertAliasesDoNotContainSecrets(aliases: List<String>) {
        aliases.forEach { alias ->
            listOf(OPEN_CODE_SECRET, SSH_PASSWORD_SECRET).forEach { secret ->
                assertFalse(alias.contains(secret))
            }
        }
        assertEquals(aliases.size, aliases.toSet().size)
    }

    private suspend fun captureFailure(block: suspend () -> Unit): Throwable {
        var captured: Throwable? = null
        try {
            block()
        } catch (failure: Throwable) {
            captured = failure
        }
        return captured ?: throw AssertionError("Expected operation to fail")
    }

    private companion object {
        val TEST_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        const val BASE_URL = "https://remote.example.com:8443"
        const val OLD_OPEN_CODE_ALIAS = "legacy-open-code-password"
        const val OLD_SSH_PASSWORD_ALIAS = "legacy-ssh-password"
        const val OLD_PRIVATE_KEY_ALIAS = "legacy-ssh-private-key"
        const val OLD_PASSPHRASE_ALIAS = "legacy-ssh-passphrase"
        const val OLD_FINGERPRINT_ALIAS = "legacy-ssh-host-fingerprint"
        const val OPEN_CODE_SECRET = "open-code-password-value"
        const val OLD_OPEN_CODE_SECRET = "old-open-code-password-value"
        const val NEW_OPEN_CODE_SECRET = "new-open-code-password-value"
        const val SSH_PASSWORD_SECRET = "ssh-password-value"
        const val NEW_SSH_PASSWORD_SECRET = "new-ssh-password-value"
        const val OLD_PRIVATE_KEY_SECRET = "private-key-value"
        const val OLD_PASSPHRASE_SECRET = "passphrase-value"
        const val OLD_FINGERPRINT_SECRET = "old-fingerprint-value"
        const val NEW_FINGERPRINT_SECRET = "new-fingerprint-value"
    }
}

private class FakeCredentialStore(
    initialValues: Map<String, String> = emptyMap(),
) : CredentialStore {
    val values = initialValues.toMutableMap()
    val putAliases = mutableListOf<String>()
    val removeAliases = mutableListOf<String>()
    val removeFailures = mutableSetOf<String>()
    var failPutAt: Int? = null
    var writeBeforePutFailure = false
    var failAllRemoves = false
    var putFailure: Throwable = CredentialStoreException(CredentialStoreOperation.Write)
    var getFailure: Throwable? = null
    var afterPut: suspend () -> Unit = {}

    override suspend fun putSecret(alias: String, secret: String) {
        putAliases += alias
        if (putAliases.size == failPutAt) {
            if (writeBeforePutFailure) values[alias] = secret
            throw putFailure
        }
        values[alias] = secret
        afterPut()
    }

    override suspend fun getSecret(alias: String): String? {
        getFailure?.let { throw it }
        return values[alias]
    }

    override suspend fun removeSecret(alias: String) {
        removeAliases += alias
        if (failAllRemoves || alias in removeFailures) {
            throw CredentialStoreException(CredentialStoreOperation.Remove)
        }
        values.remove(alias)
    }
}

private class FakeServerConfigStore(
    initialServers: List<ServerConfig> = emptyList(),
) : ServerConfigStore {
    private val state = MutableStateFlow(initialServers)
    private val composerPreferences = MutableStateFlow<Map<String, ServerComposerModelPreference>>(emptyMap())
    private val hiddenModels = MutableStateFlow<List<ServerHiddenModelPreference>>(emptyList())
    var upsertFailure: Throwable? = null
    var upsertFailureAfterPersist: Throwable? = null
    var deleteFailure: Throwable? = null
    var deleteFailureAfterPersist: Throwable? = null
    var failReadsAfterMutationAttempt = false
    private var mutationAttempted = false

    val persistedServers: List<ServerConfig>
        get() = state.value

    override val servers: Flow<List<ServerConfig>> = state

    override suspend fun getServers(): List<ServerConfig> {
        if (failReadsAfterMutationAttempt && mutationAttempted) {
            throw IllegalStateException("config outcome read failed")
        }
        return state.value
    }

    override fun observeComposerModelPreference(serverId: String): Flow<ServerComposerModelPreference?> =
        composerPreferences.map { it[serverId] }

    override suspend fun getComposerModelPreference(serverId: String): ServerComposerModelPreference? =
        composerPreferences.value[serverId]

    override suspend fun getHiddenModelPreferences(serverId: String): List<ServerHiddenModelPreference> =
        hiddenModels.value.filter { it.serverId == serverId }

    override suspend fun upsertServer(server: ServerConfig) {
        mutationAttempted = true
        upsertFailure?.let { throw it }
        state.value = if (state.value.any { it.id == server.id }) {
            state.value.map { if (it.id == server.id) server else it }
        } else {
            state.value + server
        }
        upsertFailureAfterPersist?.let { throw it }
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
        mutationAttempted = true
        deleteFailure?.let { throw it }
        state.value = state.value.filterNot { it.id == serverId }
        deleteFailureAfterPersist?.let { throw it }
    }
}

private class FakeServerRuntimeInvalidator : ServerRuntimeInvalidator {
    val calls = mutableListOf<RuntimeInvalidationCall>()

    override suspend fun invalidate(
        serverId: String,
        minimumConfigEpoch: Long,
        ssh: Boolean,
        stcp: Boolean,
    ) {
        calls += RuntimeInvalidationCall(serverId, minimumConfigEpoch, ssh, stcp)
    }
}

private data class RuntimeInvalidationCall(
    val serverId: String,
    val minimumConfigEpoch: Long,
    val ssh: Boolean,
    val stcp: Boolean,
)
