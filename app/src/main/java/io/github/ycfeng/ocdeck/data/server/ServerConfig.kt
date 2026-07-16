package io.github.ycfeng.ocdeck.data.server

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String? = null,
    val passwordKey: String? = null,
    val sshTunnel: ServerSshTunnelConfig? = null,
    val frpcStcpVisitor: ServerFrpcStcpVisitorConfig? = null,
) {
    override fun toString(): String =
        "ServerConfig(id=$REDACTED, name=$REDACTED, baseUrl=$REDACTED, " +
            "username=${redactedIfPresent(username)}, passwordKey=${redactedIfPresent(passwordKey)}, " +
            "sshTunnelPresent=${sshTunnel != null}, frpcStcpVisitorPresent=${frpcStcpVisitor != null})"
}

@Serializable
data class ServerSshTunnelConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: SshAuthMethod = SshAuthMethod.PrivateKey,
    val passwordKey: String? = null,
    val privateKeyKey: String? = null,
    val privateKeyFileName: String? = null,
    val passphraseKey: String? = null,
    val localPort: Int = 4096,
    val connectTimeoutSeconds: Int = 10,
    val keepAliveSeconds: Int = 30,
    val hostKeyPolicy: SshHostKeyPolicy = SshHostKeyPolicy.AcceptNew,
    val hostFingerprintKey: String? = null,
) {
    override fun toString(): String =
        "ServerSshTunnelConfig(host=$REDACTED, port=$port, username=$REDACTED, " +
            "authMethod=$authMethod, passwordKey=${redactedIfPresent(passwordKey)}, " +
            "privateKeyKey=${redactedIfPresent(privateKeyKey)}, " +
            "privateKeyFileName=${redactedIfPresent(privateKeyFileName)}, " +
            "passphraseKey=${redactedIfPresent(passphraseKey)}, localPort=$localPort, " +
            "connectTimeoutSeconds=$connectTimeoutSeconds, keepAliveSeconds=$keepAliveSeconds, " +
            "hostKeyPolicy=$hostKeyPolicy, hostFingerprintKey=${redactedIfPresent(hostFingerprintKey)})"
}

@Serializable
data class ServerFrpcStcpVisitorConfig(
    val serverAddr: String,
    val serverPort: Int = 7000,
    val authTokenKey: String? = null,
    val user: String? = null,
    val serverUser: String? = null,
    val serverName: String,
    val secretKeyKey: String,
    val bindPort: Int = 4096,
    val transportProtocol: FrpcTransportProtocol = FrpcTransportProtocol.Tcp,
    val wireProtocol: FrpcWireProtocol = FrpcWireProtocol.V1,
) {
    override fun toString(): String =
        "ServerFrpcStcpVisitorConfig(serverAddr=$REDACTED, serverPort=$serverPort, " +
            "authTokenKey=${redactedIfPresent(authTokenKey)}, user=${redactedIfPresent(user)}, " +
            "serverUser=${redactedIfPresent(serverUser)}, serverName=$REDACTED, " +
            "secretKeyKey=$REDACTED, bindPort=$bindPort, transportProtocol=$transportProtocol, " +
            "wireProtocol=$wireProtocol)"
}

@Serializable
enum class SshAuthMethod {
    Password,
    PrivateKey,
    PasswordAndPrivateKey,
}

@Serializable
enum class SshHostKeyPolicy {
    AcceptNew,
    Fingerprint,
}

@Serializable
enum class FrpcTransportProtocol(val value: String) {
    Tcp("tcp"),
}

@Serializable
enum class FrpcWireProtocol(val value: String) {
    V1("v1"),
    V2("v2"),
}

data class ServerHealth(
    val version: String?,
) {
    override fun toString(): String = "ServerHealth(versionPresent=${version != null})"
}

@Serializable
data class ServerComposerModelPreference(
    val serverId: String,
    val providerId: String,
    val modelId: String,
    val variant: String? = null,
) {
    override fun toString(): String =
        "ServerComposerModelPreference(serverId=$REDACTED, providerId=$REDACTED, " +
            "modelId=$REDACTED, variant=${redactedIfPresent(variant)})"
}

@Serializable
data class ServerHiddenModelPreference(
    val serverId: String,
    val providerId: String,
    val modelId: String,
) {
    override fun toString(): String =
        "ServerHiddenModelPreference(serverId=$REDACTED, providerId=$REDACTED, modelId=$REDACTED)"
}

private const val REDACTED = "<redacted>"

private fun redactedIfPresent(value: Any?): String = if (value == null) "null" else REDACTED
