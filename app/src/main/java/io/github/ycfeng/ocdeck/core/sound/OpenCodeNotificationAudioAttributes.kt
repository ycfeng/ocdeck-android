package io.github.ycfeng.ocdeck.core.sound

import android.media.AudioAttributes

internal object OpenCodeNotificationAudioAttributes {
    const val Usage: Int = AudioAttributes.USAGE_NOTIFICATION_EVENT
    const val ContentType: Int = AudioAttributes.CONTENT_TYPE_SONIFICATION

    fun create(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(Usage)
        .setContentType(ContentType)
        .build()
}
