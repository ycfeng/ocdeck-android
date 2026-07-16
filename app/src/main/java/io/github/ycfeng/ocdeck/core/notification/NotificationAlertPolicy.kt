package io.github.ycfeng.ocdeck.core.notification

import android.app.NotificationManager
import io.github.ycfeng.ocdeck.data.settings.AppNotificationSettings
import io.github.ycfeng.ocdeck.data.settings.AppSoundSettings

internal enum class OpenCodeAlertType {
    Agent,
    Question,
    Permission,
    Error,
    ;

    val channelKind: OpenCodeNotificationChannelKind
        get() = when (this) {
            Agent, Question -> OpenCodeNotificationChannelKind.Agent
            Permission -> OpenCodeNotificationChannelKind.Permission
            Error -> OpenCodeNotificationChannelKind.Error
        }

    fun systemNotificationEnabled(settings: AppNotificationSettings): Boolean = when (this) {
        Agent, Question -> settings.agentEnabled
        Permission -> settings.permissionsEnabled
        Error -> settings.errorsEnabled
    }

    fun appSoundId(settings: AppSoundSettings): String? = when (this) {
        Agent, Question -> settings.agent.takeIf { settings.agentEnabled }
        Permission -> settings.permissions.takeIf { settings.permissionsEnabled }
        Error -> settings.errors.takeIf { settings.errorsEnabled }
    }
}

internal enum class NotificationSoundOwner {
    None,
    App,
    System,
}

internal data class NotificationAlertInput(
    val eventAlreadyViewed: Boolean,
    val sessionVisible: Boolean,
    val platformNotificationsAllowed: Boolean,
    val channelImportance: Int,
    val channelHasSystemSound: Boolean,
    val appSoundAllowed: Boolean,
    val systemNotificationEnabled: Boolean,
    val appSoundId: String?,
    val cooldownAllowed: Boolean,
)

internal data class NotificationAlertDecision(
    val publishSystemNotification: Boolean,
    val appSoundId: String?,
    val soundOwner: NotificationSoundOwner,
) {
    val hasAlert: Boolean
        get() = publishSystemNotification || appSoundId != null

    companion object {
        val None = NotificationAlertDecision(
            publishSystemNotification = false,
            appSoundId = null,
            soundOwner = NotificationSoundOwner.None,
        )
    }
}

internal object NotificationAlertPolicy {
    fun decide(input: NotificationAlertInput): NotificationAlertDecision {
        if (
            input.eventAlreadyViewed ||
            input.sessionVisible ||
            !input.platformNotificationsAllowed ||
            input.channelImportance == NotificationManager.IMPORTANCE_NONE ||
            !input.cooldownAllowed
        ) {
            return NotificationAlertDecision.None
        }

        val publishSystemNotification = input.systemNotificationEnabled
        val channelAllowsSound = input.channelImportance >= NotificationManager.IMPORTANCE_DEFAULT
        val appSoundId = input.appSoundId.takeIf {
            input.appSoundAllowed &&
                channelAllowsSound &&
                (!publishSystemNotification || !input.channelHasSystemSound)
        }
        val soundOwner = when {
            publishSystemNotification && channelAllowsSound && input.channelHasSystemSound -> {
                NotificationSoundOwner.System
            }
            appSoundId != null -> NotificationSoundOwner.App
            else -> NotificationSoundOwner.None
        }
        return NotificationAlertDecision(
            publishSystemNotification = publishSystemNotification,
            appSoundId = appSoundId,
            soundOwner = soundOwner,
        )
    }
}
