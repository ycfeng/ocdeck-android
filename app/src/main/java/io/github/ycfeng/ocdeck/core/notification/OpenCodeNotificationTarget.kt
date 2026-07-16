package io.github.ycfeng.ocdeck.core.notification

data class OpenCodeNotificationTarget(
    val serverId: String,
    val directory: String,
    val sessionId: String?,
) {
    fun isValid(): Boolean = serverId.isNotBlank() && directory.isNotBlank()

    override fun toString(): String =
        "OpenCodeNotificationTarget(serverId=<redacted>, directory=<redacted>, " +
            "sessionId=${if (sessionId == null) "null" else "<redacted>"})"

    companion object {
        const val ExtraServerId = "io.github.ycfeng.ocdeck.extra.SERVER_ID"
        const val ExtraDirectory = "io.github.ycfeng.ocdeck.extra.DIRECTORY"
        const val ExtraSessionId = "io.github.ycfeng.ocdeck.extra.SESSION_ID"
    }
}
