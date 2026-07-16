package io.github.ycfeng.ocdeck.core.notification

import android.app.NotificationManager
import io.github.ycfeng.ocdeck.data.settings.AppNotificationSettings
import io.github.ycfeng.ocdeck.data.settings.AppSoundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAlertPolicyTest {
    @Test
    fun defaultSilentChannelPublishesNotificationAndLetsAppOwnSound() {
        val decision = NotificationAlertPolicy.decide(baseInput())

        assertTrue(decision.publishSystemNotification)
        assertEquals("agent-sound", decision.appSoundId)
        assertEquals(NotificationSoundOwner.App, decision.soundOwner)
    }

    @Test
    fun customChannelSoundMakesSystemTheOnlySoundOwner() {
        val decision = NotificationAlertPolicy.decide(
            baseInput(channelHasSystemSound = true),
        )

        assertTrue(decision.publishSystemNotification)
        assertNull(decision.appSoundId)
        assertEquals(NotificationSoundOwner.System, decision.soundOwner)
    }

    @Test
    fun appSoundNoneStillPublishesSilentChannelNotification() {
        val decision = NotificationAlertPolicy.decide(baseInput(appSoundId = null))

        assertTrue(decision.publishSystemNotification)
        assertNull(decision.appSoundId)
        assertEquals(NotificationSoundOwner.None, decision.soundOwner)
    }

    @Test
    fun systemNotificationToggleRemainsIndependentFromAppSoundOnSilentChannel() {
        val decision = NotificationAlertPolicy.decide(
            baseInput(systemNotificationEnabled = false),
        )

        assertFalse(decision.publishSystemNotification)
        assertEquals("agent-sound", decision.appSoundId)
        assertEquals(NotificationSoundOwner.App, decision.soundOwner)
    }

    @Test
    fun disabledSystemNotificationLetsAppOwnSoundEvenWhenChannelHasSystemSound() {
        val decision = NotificationAlertPolicy.decide(
            baseInput(
                channelHasSystemSound = true,
                systemNotificationEnabled = false,
            ),
        )

        assertFalse(decision.publishSystemNotification)
        assertEquals("agent-sound", decision.appSoundId)
        assertEquals(NotificationSoundOwner.App, decision.soundOwner)
    }

    @Test
    fun explicitChannelSilenceSuppressesAppSound() {
        val decision = NotificationAlertPolicy.decide(baseInput(appSoundAllowed = false))

        assertTrue(decision.publishSystemNotification)
        assertNull(decision.appSoundId)
        assertEquals(NotificationSoundOwner.None, decision.soundOwner)
    }

    @Test
    fun appWideBlockSuppressesNotificationAndAppSound() {
        val decision = NotificationAlertPolicy.decide(
            baseInput(platformNotificationsAllowed = false),
        )

        assertEquals(NotificationAlertDecision.None, decision)
    }

    @Test
    fun blockedChannelSuppressesNotificationAndAppSound() {
        val decision = NotificationAlertPolicy.decide(
            baseInput(channelImportance = NotificationManager.IMPORTANCE_NONE),
        )

        assertEquals(NotificationAlertDecision.None, decision)
    }

    @Test
    fun lowAndMinChannelsMayPublishButNeverUseAppSound() {
        listOf(
            NotificationManager.IMPORTANCE_MIN,
            NotificationManager.IMPORTANCE_LOW,
        ).forEach { importance ->
            val decision = NotificationAlertPolicy.decide(
                baseInput(channelImportance = importance),
            )

            assertTrue(decision.publishSystemNotification)
            assertNull(decision.appSoundId)
            assertEquals(NotificationSoundOwner.None, decision.soundOwner)
        }
    }

    @Test
    fun visibleOrViewedSessionAndCooldownBlockSuppressAllAlertOutputs() {
        assertEquals(
            NotificationAlertDecision.None,
            NotificationAlertPolicy.decide(baseInput(sessionVisible = true)),
        )
        assertEquals(
            NotificationAlertDecision.None,
            NotificationAlertPolicy.decide(baseInput(eventAlreadyViewed = true)),
        )
        assertEquals(
            NotificationAlertDecision.None,
            NotificationAlertPolicy.decide(baseInput(cooldownAllowed = false)),
        )
    }

    @Test
    fun questionUsesAgentChannelNotificationSettingAndSound() {
        val notificationSettings = AppNotificationSettings(
            agentEnabled = true,
            permissionsEnabled = false,
            errorsEnabled = false,
        )
        val soundSettings = AppSoundSettings(
            agentEnabled = true,
            agent = "agent-sound",
            permissionsEnabled = true,
            permissions = "permission-sound",
            errorsEnabled = true,
            errors = "error-sound",
        )

        assertEquals(OpenCodeNotificationChannelKind.Agent, OpenCodeAlertType.Question.channelKind)
        assertTrue(OpenCodeAlertType.Question.systemNotificationEnabled(notificationSettings))
        assertEquals("agent-sound", OpenCodeAlertType.Question.appSoundId(soundSettings))
    }

    private fun baseInput(
        eventAlreadyViewed: Boolean = false,
        sessionVisible: Boolean = false,
        platformNotificationsAllowed: Boolean = true,
        channelImportance: Int = NotificationManager.IMPORTANCE_DEFAULT,
        channelHasSystemSound: Boolean = false,
        appSoundAllowed: Boolean = true,
        systemNotificationEnabled: Boolean = true,
        appSoundId: String? = "agent-sound",
        cooldownAllowed: Boolean = true,
    ): NotificationAlertInput = NotificationAlertInput(
        eventAlreadyViewed = eventAlreadyViewed,
        sessionVisible = sessionVisible,
        platformNotificationsAllowed = platformNotificationsAllowed,
        channelImportance = channelImportance,
        channelHasSystemSound = channelHasSystemSound,
        appSoundAllowed = appSoundAllowed,
        systemNotificationEnabled = systemNotificationEnabled,
        appSoundId = appSoundId,
        cooldownAllowed = cooldownAllowed,
    )
}
