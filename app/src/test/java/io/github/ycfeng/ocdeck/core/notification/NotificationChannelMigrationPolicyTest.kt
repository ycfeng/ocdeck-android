package io.github.ycfeng.ocdeck.core.notification

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationChannelMigrationPolicyTest {
    @Test
    fun v2ChannelIdsAreStableAndCategorySpecific() {
        assertEquals("ocdeck_agent_alerts_v2", OpenCodeNotificationChannelKind.Agent.id)
        assertEquals("ocdeck_permission_alerts_v2", OpenCodeNotificationChannelKind.Permission.id)
        assertEquals("ocdeck_error_alerts_v2", OpenCodeNotificationChannelKind.Error.id)
    }

    @Test
    fun freshInstallCreatesDefaultImportanceSilentChannels() {
        val plan = NotificationChannelMigrationPolicy.plan(null)

        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, plan.importance)
        assertFalse(plan.inheritLegacySound)
        assertFalse(plan.suppressAppSound)
    }

    @Test
    fun accidentalLegacyDefaultSoundIsNotMigrated() {
        val plan = NotificationChannelMigrationPolicy.plan(
            legacy(
                importance = NotificationManager.IMPORTANCE_DEFAULT,
                hasSound = true,
                soundIsDefault = true,
                userSetImportance = false,
                userSetSound = false,
            ),
        )

        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, plan.importance)
        assertFalse(plan.inheritLegacySound)
        assertFalse(plan.suppressAppSound)
    }

    @Test
    fun blockedAndLowLegacyImportanceArePreservedWithoutUserFlags() {
        listOf(
            NotificationManager.IMPORTANCE_NONE,
            NotificationManager.IMPORTANCE_MIN,
            NotificationManager.IMPORTANCE_LOW,
        ).forEach { importance ->
            val plan = NotificationChannelMigrationPolicy.plan(
                legacy(importance = importance, userSetImportance = null),
            )

            assertEquals(importance, plan.importance)
        }
    }

    @Test
    fun explicitUserImportanceIsPreserved() {
        val plan = NotificationChannelMigrationPolicy.plan(
            legacy(
                importance = NotificationManager.IMPORTANCE_HIGH,
                userSetImportance = true,
            ),
        )

        assertEquals(NotificationManager.IMPORTANCE_HIGH, plan.importance)
    }

    @Test
    fun explicitUserSoundIsMigratedIncludingDefaultSound() {
        val plan = NotificationChannelMigrationPolicy.plan(
            legacy(
                hasSound = true,
                soundIsDefault = true,
                userSetSound = true,
            ),
        )

        assertTrue(plan.inheritLegacySound)
    }

    @Test
    fun preApi30NonDefaultLegacySoundIsTreatedAsCustom() {
        val plan = NotificationChannelMigrationPolicy.plan(
            legacy(
                hasSound = true,
                soundIsDefault = false,
                userSetSound = null,
            ),
        )

        assertTrue(plan.inheritLegacySound)
    }

    @Test
    fun explicitLegacySilenceRemainsSilent() {
        val plan = NotificationChannelMigrationPolicy.plan(
            legacy(
                hasSound = false,
                soundIsDefault = false,
                userSetSound = true,
            ),
        )

        assertFalse(plan.inheritLegacySound)
        assertTrue(plan.suppressAppSound)
    }

    @Test
    fun preApi30LegacySilenceConservativelySuppressesAppSound() {
        val plan = NotificationChannelMigrationPolicy.plan(
            legacy(
                hasSound = false,
                soundIsDefault = false,
                userSetSound = null,
            ),
        )

        assertTrue(plan.suppressAppSound)
    }

    private fun legacy(
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
        hasSound: Boolean = false,
        soundIsDefault: Boolean = false,
        userSetImportance: Boolean? = false,
        userSetSound: Boolean? = false,
    ): LegacyNotificationChannelState = LegacyNotificationChannelState(
        importance = importance,
        hasSound = hasSound,
        soundIsDefault = soundIsDefault,
        userSetImportance = userSetImportance,
        userSetSound = userSetSound,
    )
}
