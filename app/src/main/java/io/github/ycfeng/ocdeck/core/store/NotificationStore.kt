package io.github.ycfeng.ocdeck.core.store

private const val MAX_NOTIFICATIONS = 500
private const val NOTIFICATION_TTL_MS = 30L * 24L * 60L * 60L * 1000L

sealed interface OpenCodeNotification {
    val directory: String
    val sessionId: String?
    val timeMillis: Long
    val viewed: Boolean

    fun viewedCopy(): OpenCodeNotification
}

data class TurnCompleteNotification(
    override val directory: String,
    override val sessionId: String,
    override val timeMillis: Long,
    override val viewed: Boolean,
) : OpenCodeNotification {
    override fun viewedCopy(): OpenCodeNotification = copy(viewed = true)

    override fun toString(): String =
        "TurnCompleteNotification(directory=<redacted>, sessionId=<redacted>, " +
            "timeMillis=$timeMillis, viewed=$viewed)"
}

data class ErrorNotification(
    override val directory: String,
    override val sessionId: String?,
    override val timeMillis: Long,
    override val viewed: Boolean,
    val summary: String,
) : OpenCodeNotification {
    override fun viewedCopy(): OpenCodeNotification = copy(viewed = true)

    override fun toString(): String =
        "ErrorNotification(directory=<redacted>, " +
            "sessionId=${if (sessionId == null) "null" else "<redacted>"}, " +
            "timeMillis=$timeMillis, viewed=$viewed, summaryPresent=${summary.isNotEmpty()})"
}

data class NotificationStore(
    val items: List<OpenCodeNotification> = emptyList(),
) {
    fun append(notification: OpenCodeNotification, nowMillis: Long = notification.timeMillis): NotificationStore = copy(
        items = (items + notification).prune(nowMillis),
    )

    fun markSessionViewed(sessionId: String): NotificationStore {
        if (sessionId.isBlank()) return this
        return copy(items = items.map { notification ->
            if (notification.sessionId == sessionId && !notification.viewed) notification.viewedCopy() else notification
        })
    }

    fun markProjectViewed(directory: String): NotificationStore {
        if (directory.isBlank()) return this
        return copy(items = items.map { notification ->
            if (notification.directory == directory && !notification.viewed) notification.viewedCopy() else notification
        })
    }

    fun sessionUnseenCount(sessionId: String): Int = items.count { notification ->
        notification.sessionId == sessionId && !notification.viewed
    }

    fun projectUnseenCount(directory: String): Int = items.count { notification ->
        notification.directory == directory && !notification.viewed
    }

    fun sessionUnseenHasError(sessionId: String): Boolean = items.any { notification ->
        notification is ErrorNotification && notification.sessionId == sessionId && !notification.viewed
    }

    fun projectUnseenHasError(directory: String): Boolean = items.any { notification ->
        notification is ErrorNotification && notification.directory == directory && !notification.viewed
    }

    override fun toString(): String = "NotificationStore(itemCount=${items.size})"

    private fun List<OpenCodeNotification>.prune(nowMillis: Long): List<OpenCodeNotification> = asSequence()
        .filter { notification -> nowMillis - notification.timeMillis <= NOTIFICATION_TTL_MS }
        .sortedBy { it.timeMillis }
        .toList()
        .takeLast(MAX_NOTIFICATIONS)
}

enum class NotificationDotKind {
    None,
    Unseen,
    Error,
    Permission,
}
