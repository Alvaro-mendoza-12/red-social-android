package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import conectate.com.util.InteractiveAudioBuffer
import conectate.com.util.MediaPlaybackDataSources
import conectate.com.util.UbuntuMedia
import kotlinx.coroutines.delay
import java.net.URI

private val supportedAudioExtensions = setOf("mp3", "m4a", "mp4", "aac", "ogg", "oga", "opus", "flac", "wav", "webm")

internal fun isSupportedDirectAudioSource(rawUrl: String): Boolean = runCatching {
    val uri = URI(rawUrl.trim())
    when (uri.scheme?.lowercase()) {
        "content", "file", "android.resource" -> true
        "https" -> !uri.host.isNullOrBlank() && uri.userInfo == null && (
            uri.path.orEmpty().substringAfterLast('.', "").lowercase() in supportedAudioExtensions ||
                Regex("/storage/v1/object/(public|sign)/audios/.+").matches(uri.path.orEmpty())
            )
        else -> false
    }
}.getOrDefault(false)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun CommentAudioPlayer(audioUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resolved = remember(audioUrl) { UbuntuMedia.resolveUrl(audioUrl) ?: audioUrl }
    var player by remember(resolved) { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember(resolved) { mutableStateOf(false) }
    var isLoading by remember(resolved) { mutableStateOf(false) }
    var progress by remember(resolved) { mutableStateOf(0f) }
    var error by remember(resolved) { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        val owned = player
        onDispose { owned?.release() }
    }

    DisposableEffect(player, lifecycle) {
        val owned = player
        val observer = LifecycleEventObserver { _, event ->
            if (conectate.com.BuildConfig.DEBUG) android.util.Log.d("InteractiveAudioState",
                "lifecycle_event=$event player_attached=${owned != null}")
            if (event == Lifecycle.Event.ON_STOP) { owned?.pause(); isPlaying = false; isLoading = false }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isPlaying, player) {
        while (isPlaying) {
            val active = player ?: break
            if (active.duration > 0) progress = (active.currentPosition.toFloat() / active.duration).coerceIn(0f, 1f)
            delay(100)
        }
    }

    fun togglePlay() {
        if (!isSupportedDirectAudioSource(resolved)) {
            error = "Este enlace no es un archivo de audio directo compatible."
            return
        }
        var active = player
        try {
            if (active == null) {
                active = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(MediaPlaybackDataSources.streamingFactory(context)))
                    .setLoadControl(InteractiveAudioBuffer.loadControl())
                    .setHandleAudioBecomingNoisy(true).build()
                val owned = active
                player = owned
                owned.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
                owned.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(value: Boolean) {
                        if (player !== owned) return
                        isPlaying = value
                        if (value) error = null
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (player !== owned) return
                        // prepare() can report BUFFERING before play() sets the
                        // playback intent. Keep the spinner in sync when that
                        // intent changes, including a pause during loading.
                        isLoading = playWhenReady && owned.playbackState == Player.STATE_BUFFERING
                        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                            isLoading = false
                            error = "La reproducción se interrumpió. Toca reproducir para continuar."
                        }
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (player !== owned) return
                        isLoading = state == Player.STATE_BUFFERING && owned.playWhenReady
                        if (state == Player.STATE_ENDED) { progress = 0f; isPlaying = false }
                    }
                    override fun onEvents(eventPlayer: Player, events: Player.Events) {
                        if (conectate.com.BuildConfig.DEBUG && player === owned) {
                            android.util.Log.d("InteractiveAudioState",
                                "state=${eventPlayer.playbackState} play_when_ready=${eventPlayer.playWhenReady} " +
                                    "playing=${eventPlayer.isPlaying} suppression=${eventPlayer.playbackSuppressionReason} " +
                                    "position_ms=${eventPlayer.currentPosition} owner_state=${lifecycle.currentState}")
                        }
                    }
                    override fun onPlayerError(failure: PlaybackException) {
                        if (player !== owned) return
                        isLoading = false; isPlaying = false
                        error = "No se pudo reproducir este audio. Toca reproducir para reintentar."
                    }
                })
            }
            val owned = active
            error = null
            if (owned.playWhenReady && owned.playbackState != Player.STATE_ENDED && owned.playerError == null) {
                owned.pause(); isLoading = false; isPlaying = false
            } else {
                if (owned.playbackState == Player.STATE_IDLE) {
                    owned.setMediaItem(MediaItem.fromUri(resolved)); isLoading = true; owned.prepare()
                } else if (owned.playbackState == Player.STATE_ENDED) owned.seekTo(0)
                owned.play()
                if (!owned.playWhenReady) {
                    isLoading = false
                    error = "No se pudo iniciar el audio. Toca reproducir para reintentar."
                }
            }
        } catch (_: Exception) {
            // Release even if setup failed before Compose installed its disposal effect.
            active?.release()
            if (player === active) player = null
            isLoading = false; isPlaying = false; error = "No se pudo reproducir este audio."
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f), CircleShape)
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::togglePlay, modifier = Modifier.size(36.dp)) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "Pausar" else "Reproducir", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .2f))
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
    }
}
