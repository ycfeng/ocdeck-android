package io.github.ycfeng.ocdeck.domain.prompt

import java.security.SecureRandom

object OpenCodeIdGenerator {
    private const val counterScale = 4096L
    private const val randomLength = 14
    private const val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    private val random = SecureRandom()
    private var lastTimestamp = 0L
    private var counter = 0L

    fun messageId(): String = ascending("msg")

    fun partId(): String = ascending("prt")

    @Synchronized
    private fun ascending(prefix: String): String {
        val now = System.currentTimeMillis()
        if (now != lastTimestamp) {
            lastTimestamp = now
            counter = 0L
        }
        counter += 1L

        val timeCounter = now * counterScale + counter
        return buildString(prefix.length + 1 + 26) {
            append(prefix)
            append('_')
            appendTimeCounter(timeCounter)
            appendRandomSuffix()
        }
    }

    private fun StringBuilder.appendTimeCounter(value: Long) {
        for (shift in 40 downTo 0 step 8) {
            append(((value ushr shift) and 0xff).toString(16).padStart(2, '0'))
        }
    }

    private fun StringBuilder.appendRandomSuffix() {
        val bytes = ByteArray(randomLength)
        random.nextBytes(bytes)
        for (byte in bytes) {
            append(alphabet[(byte.toInt() and 0xff) % alphabet.length])
        }
    }
}
