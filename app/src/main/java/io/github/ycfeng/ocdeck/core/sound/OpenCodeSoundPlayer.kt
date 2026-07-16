package io.github.ycfeng.ocdeck.core.sound

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

class OpenCodeSoundPlayer(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) {
    private val applicationContext = context.applicationContext
    private var currentPlayer: MediaPlayer? = null
    private var pendingPlayback: Runnable? = null
    private var run = 0

    fun play(id: String?) {
        schedule(id, delayMillis = 0L)
    }

    fun preview(id: String?) {
        schedule(id, delayMillis = PreviewDelayMillis)
    }

    fun stop() {
        mainHandler.post { stopCurrentPlayback() }
    }

    private fun schedule(id: String?, delayMillis: Long) {
        val rawResId = OpenCodeSoundCatalog.rawResId(id)
        mainHandler.post {
            stopCurrentPlayback()
            if (rawResId == null) return@post

            val scheduledRun = ++run
            val playback = Runnable {
                if (scheduledRun == run) playRaw(rawResId)
            }
            pendingPlayback = playback
            mainHandler.postDelayed(playback, delayMillis)
        }
    }

    private fun stopCurrentPlayback() {
        run++
        pendingPlayback?.let(mainHandler::removeCallbacks)
        pendingPlayback = null
        currentPlayer?.let { player ->
            player.setOnCompletionListener(null)
            player.setOnErrorListener(null)
            runCatching { player.stop() }
            runCatching { player.release() }
        }
        currentPlayer = null
    }

    private fun playRaw(rawResId: Int) {
        pendingPlayback = null
        val player = runCatching { MediaPlayer.create(applicationContext, rawResId) }.getOrNull() ?: return
        currentPlayer = player
        player.setOnCompletionListener { completed ->
            if (currentPlayer === completed) currentPlayer = null
            runCatching { completed.release() }
        }
        player.setOnErrorListener { failed, _, _ ->
            if (currentPlayer === failed) currentPlayer = null
            runCatching { failed.release() }
            true
        }
        runCatching { player.start() }.onFailure {
            if (currentPlayer === player) currentPlayer = null
            runCatching { player.release() }
        }
    }

    private companion object {
        const val PreviewDelayMillis = 100L
    }
}
