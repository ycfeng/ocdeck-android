package io.github.ycfeng.ocdeck.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import io.github.ycfeng.ocdeck.R
import io.github.ycfeng.ocdeck.core.sound.OpenCodeNotificationAudioAttributes
import io.github.ycfeng.ocdeck.data.settings.AppLanguagePreference
import io.github.ycfeng.ocdeck.data.settings.localized

enum class OpenCodeNotificationChannelKind(
    val id: String,
    @StringRes val nameRes: Int,
    @StringRes val descriptionRes: Int,
) {
    Agent(
        id = "ocdeck_agent_alerts_v2",
        nameRes = R.string.notification_channel_agent_name,
        descriptionRes = R.string.notification_channel_agent_description,
    ),
    Permission(
        id = "ocdeck_permission_alerts_v2",
        nameRes = R.string.notification_channel_permission_name,
        descriptionRes = R.string.notification_channel_permission_description,
    ),
    Error(
        id = "ocdeck_error_alerts_v2",
        nameRes = R.string.notification_channel_error_name,
        descriptionRes = R.string.notification_channel_error_description,
    ),
}

internal data class NotificationChannelRuntimeState(
    val importance: Int,
    val hasSystemSound: Boolean,
    val appSoundAllowed: Boolean,
)

internal data class LegacyNotificationChannelState(
    val importance: Int,
    val hasSound: Boolean,
    val soundIsDefault: Boolean,
    val userSetImportance: Boolean?,
    val userSetSound: Boolean?,
)

internal data class NotificationChannelCreationPlan(
    val importance: Int,
    val inheritLegacySound: Boolean,
    val suppressAppSound: Boolean,
)

internal object NotificationChannelMigrationPolicy {
    fun plan(legacy: LegacyNotificationChannelState?): NotificationChannelCreationPlan {
        if (legacy == null) {
            return NotificationChannelCreationPlan(
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                inheritLegacySound = false,
                suppressAppSound = false,
            )
        }

        val importanceIsValid = legacy.importance in
            NotificationManager.IMPORTANCE_NONE..NotificationManager.IMPORTANCE_MAX
        val preserveImportance = importanceIsValid && (
            legacy.importance <= NotificationManager.IMPORTANCE_LOW || legacy.userSetImportance == true
            )
        val inheritLegacySound = legacy.hasSound && when (legacy.userSetSound) {
            true -> true
            false -> false
            null -> !legacy.soundIsDefault
        }
        return NotificationChannelCreationPlan(
            importance = if (preserveImportance) legacy.importance else NotificationManager.IMPORTANCE_DEFAULT,
            inheritLegacySound = inheritLegacySound,
            suppressAppSound = !legacy.hasSound && legacy.userSetSound != false,
        )
    }
}

class OpenCodeNotificationChannelRegistry(context: Context) {
    private val applicationContext = context.applicationContext
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
    @Volatile
    private var suppressAppSoundFromLegacy = false

    @Synchronized
    fun ensureChannels(languagePreference: AppLanguagePreference) {
        val manager = notificationManager ?: return
        val localizedContext = applicationContext.localized(languagePreference)
        val legacyChannel = manager.getNotificationChannel(LegacyChannelId)
        val plan = NotificationChannelMigrationPolicy.plan(legacyChannel?.toLegacyState())
        suppressAppSoundFromLegacy = plan.suppressAppSound
        val audioAttributes = OpenCodeNotificationAudioAttributes.create()

        OpenCodeNotificationChannelKind.entries.forEach { kind ->
            val existing = manager.getNotificationChannel(kind.id)
            manager.createNotificationChannel(
                NotificationChannel(
                    kind.id,
                    localizedContext.getString(kind.nameRes),
                    existing?.importance ?: plan.importance,
                ).apply {
                    description = localizedContext.getString(kind.descriptionRes)
                    setSound(
                        existing?.sound ?: legacyChannel?.sound.takeIf { plan.inheritLegacySound },
                        existing?.audioAttributes ?: audioAttributes,
                    )
                },
            )
        }
    }

    internal fun state(kind: OpenCodeNotificationChannelKind): NotificationChannelRuntimeState? =
        notificationManager
            ?.getNotificationChannel(kind.id)
            ?.let { channel ->
                NotificationChannelRuntimeState(
                    importance = channel.importance,
                    hasSystemSound = channel.sound != null,
                    appSoundAllowed = !suppressAppSoundFromLegacy && !channel.isExplicitlyMuted(),
                )
            }

    private fun NotificationChannel.isExplicitlyMuted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasUserSetSound() && sound == null

    private fun NotificationChannel.toLegacyState(): LegacyNotificationChannelState =
        LegacyNotificationChannelState(
            importance = importance,
            hasSound = sound != null,
            soundIsDefault = sound == Settings.System.DEFAULT_NOTIFICATION_URI,
            userSetImportance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hasUserSetImportance()
            } else {
                null
            },
            userSetSound = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                hasUserSetSound()
            } else {
                null
            },
        )

    private companion object {
        const val LegacyChannelId = "ocdeck_sessions"
    }
}
