package io.github.ycfeng.ocdeck.data.server

import io.github.ycfeng.ocdeck.core.network.ServerHealthDto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConnectionReadinessTest {
    @Test
    fun initialReadinessEvidenceAvoidsDuplicateHealthRequest() = runTest {
        var freshHealthCalls = 0

        val health = resolveServerHealth(ServerHealthDto(healthy = true, version = "1.2.3")) {
            freshHealthCalls += 1
            ServerHealthDto(healthy = true, version = "unexpected")
        }

        assertEquals("1.2.3", health.version)
        assertEquals(0, freshHealthCalls)
    }

    @Test
    fun readyOrDirectConnectionUsesFreshHealthRequest() = runTest {
        var freshHealthCalls = 0

        val health = resolveServerHealth(null) {
            freshHealthCalls += 1
            ServerHealthDto(healthy = true, version = "1.2.4")
        }

        assertEquals("1.2.4", health.version)
        assertEquals(1, freshHealthCalls)
    }

    @Test
    fun credentialValueUpdateInvalidatesStcpEvenWhenCredentialKeysAreUnchanged() {
        val current = stcpServer()
        val updated = current.copy(name = "Renamed")

        assertTrue(
            hasStcpConnectionChanged(
                current = current,
                updated = updated,
                passwordUpdated = false,
                stcpCredentialsUpdated = true,
            ),
        )
        assertFalse(
            hasStcpConnectionChanged(
                current = current,
                updated = updated,
                passwordUpdated = false,
                stcpCredentialsUpdated = false,
            ),
        )
    }

    private fun stcpServer(): ServerConfig = ServerConfig(
        id = "remote",
        name = "Remote",
        baseUrl = "https://opencode.example.test:8443",
        frpcStcpVisitor = ServerFrpcStcpVisitorConfig(
            serverAddr = "frps.example.com",
            serverName = "opencode_stcp",
            authTokenKey = "server-remote-frpc-stcp-auth-token",
            secretKeyKey = "server-remote-frpc-stcp-secret-key",
        ),
    )
}
