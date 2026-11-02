package conectate.com.presentation.messages

import conectate.com.util.MessageAudioPlayer
import java.io.File

internal interface VoicePreviewPlayer {
    val prepared: Boolean
    val playing: Boolean
    val duration: Int
    val position: Int
    fun prepare(file: File, onPrepared: () -> Unit, onCompletion: () -> Unit, onError: (Exception) -> Unit)
    fun play(): Boolean
    fun pause()
    fun seekTo(position: Int): Boolean
    fun release()
}

/** Reuses the guarded native player used by received chat audio. */
internal class LocalVoicePreviewPlayer : VoicePreviewPlayer {
    private val player = MessageAudioPlayer()
    override val prepared get() = player.isPrepared
    override val playing get() = player.isPlaying
    override val duration get() = player.duration
    override val position get() = player.currentPosition
    override fun prepare(file: File, onPrepared: () -> Unit, onCompletion: () -> Unit, onError: (Exception) -> Unit) =
        player.prepare(file.absolutePath, { onPrepared() }, onCompletion, onError)
    override fun play() = player.start(1f)
    override fun pause() = player.pause()
    override fun seekTo(position: Int) = player.seekTo(position)
    override fun release() = player.release()
}
