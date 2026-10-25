package conectate.com.util

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.MainThread

/** One native player per attempt. Late callbacks and invalid states never reach the UI. */
@MainThread
internal class MessageAudioPlayer {
    private var native: MediaPlayer? = null
    private var prepared = false
    private var onFailure: ((Exception) -> Unit)? = null
    private var estimatedPositionBaseMs = 0L
    private var estimatedPlaybackStartedAtMs: Long? = null
    private var estimatedPlaybackSpeed = 1f

    val isPrepared: Boolean get() = prepared
    val isPlaying: Boolean get() = access(false) { it.isPlaying }
    val duration: Int get() = access(0) { it.duration.coerceAtLeast(0) }
    val currentPosition: Int
        get() {
            val player = native?.takeIf { prepared } ?: return 0
            val reported = try { player.currentPosition.coerceAtLeast(0) }
            catch (error: Exception) { fail(error); return 0 }
            if (reported.toLong() > estimatedPositionBaseMs) {
                estimatedPositionBaseMs = reported.toLong()
                val playing = try { player.isPlaying } catch (error: Exception) { fail(error); return 0 }
                estimatedPlaybackStartedAtMs = SystemClock.elapsedRealtime().takeIf { playing }
            }
            return maxOf(reported.toLong(), estimatedPositionNow()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

    fun prepare(
        source: String,
        onPrepared: (MessageAudioPlayer) -> Unit,
        onCompletion: () -> Unit,
        onError: (Exception) -> Unit,
    ) {
        release()
        onFailure = onError
        try {
            val player = MediaPlayer()
            native = player
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setOnPreparedListener { ready ->
                if (native !== ready) return@setOnPreparedListener
                prepared = true
                estimatedPositionBaseMs = 0L
                estimatedPlaybackStartedAtMs = null
                estimatedPlaybackSpeed = 1f
                try { onPrepared(this) } catch (error: Exception) { fail(error) }
            }
            player.setOnCompletionListener { completed ->
                if (native === completed) {
                    val reported = runCatching { completed.currentPosition.coerceAtLeast(0) }.getOrDefault(0)
                    estimatedPositionBaseMs = maxOf(estimatedPositionNow(), reported.toLong())
                    estimatedPlaybackStartedAtMs = null
                    try { onCompletion() } catch (error: Exception) { fail(error) }
                }
            }
            player.setOnErrorListener { failed, what, extra ->
                if (native === failed) fail(java.io.IOException("Audio playback failed ($what/$extra)"))
                true
            }
            val local = if (source.startsWith("file:", true)) Uri.parse(source).path else source
            player.setDataSource(requireNotNull(local))
            player.prepareAsync()
        } catch (error: Exception) { fail(error) }
    }

    fun start(speed: Float) = access(false) { player ->
        val positionBeforeStart = currentPosition.toLong()
        // PlaybackParams is optional on some vendor decoders; normal playback still works.
        estimatedPlaybackSpeed = runCatching {
            player.playbackParams = player.playbackParams.setSpeed(speed).setPitch(1f)
            speed.coerceIn(0.25f, 4f)
        }.getOrDefault(1f)
        player.start()
        estimatedPositionBaseMs = positionBeforeStart
        estimatedPlaybackStartedAtMs = SystemClock.elapsedRealtime()
        true
    }

    fun pause() {
        val position = currentPosition.toLong()
        access(false) {
            it.pause()
            estimatedPositionBaseMs = position
            estimatedPlaybackStartedAtMs = null
            true
        }
    }

    fun seekTo(position: Int) = access(false) { player ->
        val target = position.coerceAtLeast(0)
        player.seekTo(target)
        estimatedPositionBaseMs = target.toLong()
        estimatedPlaybackStartedAtMs = SystemClock.elapsedRealtime().takeIf {
            runCatching { player.isPlaying }.getOrDefault(false)
        }
        true
    }

    fun setSpeed(speed: Float) {
        if (!prepared) return
        val player = native ?: return
        val position = currentPosition.toLong()
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        estimatedPlaybackSpeed = runCatching {
            player.playbackParams = player.playbackParams.setSpeed(speed).setPitch(1f)
            speed.coerceIn(0.25f, 4f)
        }.getOrDefault(1f)
        estimatedPositionBaseMs = position
        estimatedPlaybackStartedAtMs = SystemClock.elapsedRealtime().takeIf { playing }
    }

    fun release() {
        val previous = native
        native = null
        prepared = false
        onFailure = null
        estimatedPositionBaseMs = 0L
        estimatedPlaybackStartedAtMs = null
        estimatedPlaybackSpeed = 1f
        runCatching { previous?.setOnPreparedListener(null) }
        runCatching { previous?.setOnCompletionListener(null) }
        runCatching { previous?.setOnErrorListener(null) }
        runCatching { previous?.release() }
    }

    private inline fun <T> access(fallback: T, action: (MediaPlayer) -> T): T {
        val player = native?.takeIf { prepared } ?: return fallback
        return try { action(player) } catch (error: Exception) { fail(error); fallback }
    }

    private fun fail(error: Exception) {
        val callback = onFailure
        release()
        callback?.invoke(error)
    }

    private fun estimatedPositionNow(): Long {
        val startedAt = estimatedPlaybackStartedAtMs ?: return estimatedPositionBaseMs
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        return estimatedPositionBaseMs + (elapsed * estimatedPlaybackSpeed).toLong()
    }
}
