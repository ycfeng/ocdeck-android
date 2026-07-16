package io.github.ycfeng.ocdeck.data.server

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ServerConfigSerializationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun decodesLegacyServerWithoutSshTunnel() {
        val server = json.decodeFromString<ServerConfig>(
            """
            {
              "id": "local",
              "name": "Localhost",
              "baseUrl": "http://localhost:4096",
              "username": "opencode",
              "passwordKey": "server-local-password"
            }
            """.trimIndent(),
        )

        assertEquals("local", server.id)
        assertEquals("http://localhost:4096", server.baseUrl)
        assertNull(server.sshTunnel)
        assertNull(server.frpcStcpVisitor)
    }

    @Test
    fun encodesSshTunnelWithoutSecrets() {
        val server = ServerConfig(
            id = "remote",
            name = "Remote",
            baseUrl = "http://127.0.0.1:4096",
            sshTunnel = ServerSshTunnelConfig(
                host = "example.com",
                username = "ubuntu",
                authMethod = SshAuthMethod.PasswordAndPrivateKey,
                passwordKey = "server-remote-ssh-password",
                privateKeyKey = "server-remote-ssh-private-key",
                passphraseKey = "server-remote-ssh-passphrase",
                localPort = 4096,
            ),
        )

        val encoded = json.encodeToString(server)

        assert(encoded.contains("example.com"))
        assert(encoded.contains("server-remote-ssh-private-key"))
        assert(!encoded.contains("BEGIN OPENSSH PRIVATE KEY"))
    }

    @Test
    fun encodesFrpcStcpVisitorWithoutSecrets() {
        val server = ServerConfig(
            id = "remote",
            name = "Remote",
            baseUrl = "http://opencode.example.test:4096",
            frpcStcpVisitor = ServerFrpcStcpVisitorConfig(
                serverAddr = "frps.example.com",
                serverPort = 7000,
                authTokenKey = "server-remote-frpc-stcp-auth-token",
                user = "mobile",
                serverUser = "opencode",
                serverName = "opencode_stcp",
                secretKeyKey = "server-remote-frpc-stcp-secret-key",
                bindPort = 5096,
                wireProtocol = FrpcWireProtocol.V2,
            ),
        )

        val encoded = json.encodeToString(server)

        assert(encoded.contains("frps.example.com"))
        assert(encoded.contains("server-remote-frpc-stcp-auth-token"))
        assert(encoded.contains("server-remote-frpc-stcp-secret-key"))
        assertFalse(encoded.contains("real-frps-token"))
        assertFalse(encoded.contains("real-stcp-secret"))
    }

    @Test
    fun decodesFrpcStcpVisitorWithoutAuthTokenKey() {
        val server = json.decodeFromString<ServerConfig>(
            """
            {
              "id": "remote",
              "name": "Remote",
              "baseUrl": "http://opencode.example.test:4096",
              "frpcStcpVisitor": {
                "serverAddr": "frps.example.com",
                "serverPort": 7000,
                "serverName": "opencode_stcp",
                "secretKeyKey": "server-remote-frpc-stcp-secret-key",
                "bindPort": 5096
              }
            }
            """.trimIndent(),
        )

        assertNull(server.frpcStcpVisitor?.authTokenKey)
        assertEquals("server-remote-frpc-stcp-secret-key", server.frpcStcpVisitor?.secretKeyKey)
    }
}
