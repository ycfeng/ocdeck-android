package io.github.ycfeng.ocdeck.core.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.ycfeng.ocdeck.MainActivity
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.store.ErrorNotification
import io.github.ycfeng.ocdeck.core.store.OpenCodeProjectState
import io.github.ycfeng.ocdeck.core.store.OpenCodeNotification
import io.github.ycfeng.ocdeck.core.store.ProjectEventReduction
import io.github.ycfeng.ocdeck.core.store.TurnCompleteNotification
import io.github.ycfeng.ocdeck.data.settings.AppSettingsStore
import io.github.ycfeng.ocdeck.data.settings.localized
import io.github.ycfeng.ocdeck.domain.model.OpenCodePermissionRequest
import io.github.ycfeng.ocdeck.domain.model.OpenCodeQuestionRequest
import io.github.ycfeng.ocdeck.core.sound.OpenCodeSoundPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class OpenCodeSystemNotifier(
    context: Context,
    private val settingsStore: AppSettingsStore,
    private val soundPlayer: OpenCodeSoundPlayer,
    private val channelRegistry: OpenCodeNotificationChannelRegistry,
    scope: CoroutineScope,
) {
    private val applicationContext = context.applicationContext
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
    private val alertedAtBySession = ConcurrentHashMap<String, Long>()

    init {
        settingsStore.languagePreference
            .distinctUntilChanged()
            .onEach(channelRegistry::ensureChannels)
            .launchIn(scope)
    }

    suspend fun notifyProjectEvent(reduction: ProjectEventReduction) {
        val notification = reduction.toSystemNotification() ?: return
        val notificationSettings = settingsStore.notificationSettings.first()
        val soundSettings = settingsStore.soundSettings.first()
        val languagePreference = settingsStore.languagePreference.first()
        val localizedContext = applicationContext.localized(languagePreference)
        channelRegistry.ensureChannels(languagePreference)
        val channelState = channelRegistry.state(notification.alertType.channelKind)
            ?: NotificationChannelRuntimeState(
                importance = NotificationManager.IMPORTANCE_NONE,
                hasSystemSound = false,
                appSoundAllowed = false,
            )
        val input = NotificationAlertInput(
            eventAlreadyViewed = notification.alreadyViewed,
            sessionVisible = notification.sessionId != null &&
                reduction.after.activeSessionId == notification.sessionId,
            platformNotificationsAllowed = canPostNotifications(),
            channelImportance = channelState.importance,
            channelHasSystemSound = channelState.hasSystemSound,
            appSoundAllowed = channelState.appSoundAllowed,
            systemNotificationEnabled = notification.alertType.systemNotificationEnabled(notificationSettings),
            appSoundId = notification.alertType.appSoundId(soundSettings),
            cooldownAllowed = true,
        )
        val candidate = NotificationAlertPolicy.decide(input)
        val decision = if (candidate.hasAlert && notification.needsCooldown) {
            NotificationAlertPolicy.decide(
                input.copy(cooldownAllowed = notification.consumeCooldown()),
            )
        } else {
            candidate
        }
        if (!decision.hasAlert) return

        if (decision.publishSystemNotification) {
            notificationManager?.notify(
                notification.notificationId,
                Notification.Builder(applicationContext, notification.alertType.channelKind.id)
                    .setSmallIcon(R.drawable.ic_ocdeck_notification)
                    .setContentTitle(notification.title(localizedContext))
                    .setContentText(notification.body(localizedContext))
                    .setStyle(Notification.BigTextStyle().bigText(notification.body(localizedContext)))
                    .setContentIntent(notification.target.pendingIntent(applicationContext))
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .build(),
            )
        }
        decision.appSoundId?.let(soundPlayer::play)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return notificationManager?.areNotificationsEnabled() == true
    }

    private fun SystemNotification.consumeCooldown(): Boolean {
        val key = "${target.serverId}:${target.directory}:${sessionId.orEmpty()}"
        val now = System.currentTimeMillis()
        var allowed = false
        alertedAtBySession.compute(key) { _, last ->
            if (last == null || now - last >= AttentionCooldownMillis) {
                allowed = true
                now
            } else {
                last
            }
        }
        return allowed
    }
}

private sealed interface SystemNotification {
    val target: OpenCodeNotificationTarget
    val alertType: OpenCodeAlertType
    val alreadyViewed: Boolean
        get() = false
    val sessionId: String?
        get() = target.sessionId
    val needsCooldown: Boolean
        get() = false

    val notificationId: Int
        get() = ("${javaClass.name}:${target.serverId}:${target.directory}:${target.sessionId.orEmpty()}".hashCode() and Int.MAX_VALUE)

    fun title(context: Context): String
    fun body(context: Context): String
}

private data class TurnCompleteSystemNotification(
    override val target: OpenCodeNotificationTarget,
    val sessionTitle: String?,
    override val alreadyViewed: Boolean,
) : SystemNotification {
    override val alertType: OpenCodeAlertType = OpenCodeAlertType.Agent

    override fun title(context: Context): String = context.getString(R.string.notification_session_response_ready_title)

    override fun body(context: Context): String = sessionTitle.takeUnless { it.isNullOrBlank() }
        ?: target.sessionId.orEmpty()

    override fun toString(): String =
        "TurnCompleteSystemNotification(target=$target, sessionTitlePresent=${sessionTitle != null})"
}

private data class ErrorSystemNotification(
    override val target: OpenCodeNotificationTarget,
    val sessionTitle: String?,
    val summary: String,
    override val alreadyViewed: Boolean,
) : SystemNotification {
    override val alertType: OpenCodeAlertType = OpenCodeAlertType.Error

    override fun title(context: Context): String = context.getString(R.string.notification_session_error_title)

    override fun body(context: Context): String {
        val title = sessionTitle.takeUnless { it.isNullOrBlank() }
        return if (title == null) {
            summary
        } else {
            context.getString(R.string.notification_session_error_body, title, summary)
        }
    }

    override fun toString(): String =
        "ErrorSystemNotification(target=$target, sessionTitlePresent=${sessionTitle != null}, summaryPresent=true)"
}

private data class PermissionSystemNotification(
    override val target: OpenCodeNotificationTarget,
    val sessionTitle: String,
    val projectName: String,
) : SystemNotification {
    override val alertType: OpenCodeAlertType = OpenCodeAlertType.Permission
    override val needsCooldown: Boolean = true

    override fun title(context: Context): String = context.getString(R.string.notification_permission_title)

    override fun body(context: Context): String = context.getString(
        R.string.notification_permission_body,
        sessionTitle,
        projectName,
    )

    override fun toString(): String =
        "PermissionSystemNotification(target=$target, sessionTitlePresent=true, projectNamePresent=true)"
}

private data class QuestionSystemNotification(
    override val target: OpenCodeNotificationTarget,
    val sessionTitle: String,
    val projectName: String,
) : SystemNotification {
    override val alertType: OpenCodeAlertType = OpenCodeAlertType.Question
    override val needsCooldown: Boolean = true

    override fun title(context: Context): String = context.getString(R.string.notification_question_title)

    override fun body(context: Context): String = context.getString(
        R.string.notification_question_body,
        sessionTitle,
        projectName,
    )

    override fun toString(): String =
        "QuestionSystemNotification(target=$target, sessionTitlePresent=true, projectNamePresent=true)"
}

private fun ProjectEventReduction.toSystemNotification(): SystemNotification? {
    val type = event.eventType() ?: return null
    return when {
        type.contains("session") && type.contains("idle") -> newNotification<TurnCompleteNotification>()
            ?.let { notification ->
                TurnCompleteSystemNotification(
                    target = OpenCodeNotificationTarget(after.serverId, notification.directory, notification.sessionId),
                    sessionTitle = after.sessionTitle(notification.sessionId),
                    alreadyViewed = notification.viewed,
                )
            }
        type.contains("session") && type.contains("error") -> newNotification<ErrorNotification>()
            ?.let { notification ->
                ErrorSystemNotification(
                    target = OpenCodeNotificationTarget(after.serverId, notification.directory, notification.sessionId),
                    sessionTitle = notification.sessionId?.let(after::sessionTitle),
                    summary = notification.summary,
                    alreadyViewed = notification.viewed,
                )
            }
        type.contains("permission") && type.contains("asked") -> newPermissionRequest()
            ?.let { request ->
                PermissionSystemNotification(
                    target = OpenCodeNotificationTarget(after.serverId, after.normalizedDirectory, request.sessionId),
                    sessionTitle = after.sessionTitle(request.sessionId) ?: request.sessionId,
                    projectName = after.projectDisplayName(),
                )
            }
        type.contains("question") && type.contains("asked") -> newQuestionRequest()
            ?.let { request ->
                QuestionSystemNotification(
                    target = OpenCodeNotificationTarget(after.serverId, after.normalizedDirectory, request.sessionId),
                    sessionTitle = after.sessionTitle(request.sessionId) ?: request.sessionId,
                    projectName = after.projectDisplayName(),
                )
            }
        else -> null
    }
}

private inline fun <reified T : OpenCodeNotification> ProjectEventReduction.newNotification(): T? {
    val beforeItems = before.notifications.items.toSet()
    return after.notifications.items.lastOrNull { it !in beforeItems && it is T } as? T
}

private fun ProjectEventReduction.newPermissionRequest(): OpenCodePermissionRequest? {
    val beforeIds = before.permissionsBySession.values.flatten().map { it.id }.toSet()
    return after.permissionsBySession.values.flatten().firstOrNull { it.id !in beforeIds }
}

private fun ProjectEventReduction.newQuestionRequest(): OpenCodeQuestionRequest? {
    val beforeIds = before.questionsBySession.values.flatten().map { it.id }.toSet()
    return after.questionsBySession.values.flatten().firstOrNull { it.id !in beforeIds }
}

private fun OpenCodeProjectState.sessionTitle(sessionId: String): String? = sessions
    .firstOrNull { it.id == sessionId }
    ?.title
    ?.takeUnless { it.isBlank() }

private fun OpenCodeProjectState.projectDisplayName(): String = project?.displayName?.takeUnless { it.isBlank() }
    ?: normalizedDirectory.trimEnd('/').substringAfterLast('/').takeUnless { it.isBlank() }
    ?: normalizedDirectory

private fun OpenCodeNotificationTarget.pendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        action = "io.github.ycfeng.ocdeck.action.OPEN_NOTIFICATION"
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(OpenCodeNotificationTarget.ExtraServerId, serverId)
        putExtra(OpenCodeNotificationTarget.ExtraDirectory, directory)
        sessionId?.let { putExtra(OpenCodeNotificationTarget.ExtraSessionId, it) }
    }
    return PendingIntent.getActivity(
        context,
        ("$serverId:$directory:${sessionId.orEmpty()}".hashCode() and Int.MAX_VALUE),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun JsonObject.eventType(): String? {
    val payload = payloadObject()
    return (payload.string("type") ?: string("type"))?.lowercase(Locale.US)
}

private fun JsonObject.payloadObject(): JsonObject {
    val payload = objectOrNull("payload") ?: this
    return if (payload.string("type") == "sync") payload.objectOrNull("syncEvent") ?: payload else payload
}

private fun JsonObject.objectOrNull(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.string(name: String): String? = runCatching { this[name]?.jsonPrimitive?.contentOrNull }.getOrNull()

private const val AttentionCooldownMillis = 5_000L
