package io.github.ycfeng.ocdeck.data.server

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyDefaultServerMigrationTest {
    @Test
    fun removesUnmodifiedGeneratedDefaultServerForBothHistoricalUrls() {
        val retained = server(id = "remote", name = "Remote", baseUrl = "https://opencode.example.com")

        val migrated = removeUnmodifiedLegacyDefaultServer(
            listOf(
                server(baseUrl = "http://localhost:4096"),
                retained,
                server(baseUrl = "http://127.0.0.1:4096"),
            ),
        )

        assertEquals(listOf(retained), migrated)
    }

    @Test
    fun preservesLegacyIdWhenAnyGeneratedFieldWasChanged() {
        val changedServers = listOf(
            server(id = "local-copy"),
            server(name = "My OpenCode"),
            server(baseUrl = "http://opencode.example.test:4096"),
            server(username = "opencode"),
            server(passwordKey = "password-alias"),
            server(
                sshTunnel = ServerSshTunnelConfig(
                    host = "ssh.example.com",
                    username = "ubuntu",
                ),
            ),
            server(
                frpcStcpVisitor = ServerFrpcStcpVisitorConfig(
                    serverAddr = "frps.example.com",
                    serverName = "opencode",
                    secretKeyKey = "secret-key-alias",
                ),
            ),
        )

        assertEquals(changedServers, removeUnmodifiedLegacyDefaultServer(changedServers))
    }

    private fun server(
        id: String = "local",
        name: String = "Localhost",
        baseUrl: String = "http://127.0.0.1:4096",
        username: String? = null,
        passwordKey: String? = null,
        sshTunnel: ServerSshTunnelConfig? = null,
        frpcStcpVisitor: ServerFrpcStcpVisitorConfig? = null,
    ): ServerConfig = ServerConfig(
        id = id,
        name = name,
        baseUrl = baseUrl,
        username = username,
        passwordKey = passwordKey,
        sshTunnel = sshTunnel,
        frpcStcpVisitor = frpcStcpVisitor,
    )
}
