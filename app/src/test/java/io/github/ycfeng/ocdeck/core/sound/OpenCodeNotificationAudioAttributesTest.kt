package io.github.ycfeng.ocdeck.core.sound

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCodeNotificationAudioAttributesTest {
    @Test
    fun actualAndPreviewPlaybackUseNotificationSonificationProfile() {
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_EVENT, OpenCodeNotificationAudioAttributes.Usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, OpenCodeNotificationAudioAttributes.ContentType)
    }
}
