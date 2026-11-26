package conectate.com.presentation.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
fun CommentAudioPlayer(audioUrl: String, modifier: Modifier = Modifier, showAsDisc: Boolean = false) {
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
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { owned?.pause(); isPlaying = false; isLoading = false }
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
                    // Progressive audio may reopen immediately after sniffing
                    // its header. Reuse that bounded response for the short
                    // forward seek, as in the community preview player.
                    .setMediaSourceFactory(DefaultMediaSourceFactory(MediaPlaybackDataSources.interactiveAudioFactory(context)))
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
                                    "position_ms=${eventPlayer.currentPosition} buffered_ms=${eventPlayer.totalBufferedDuration} " +
                                    "owner_state=${lifecycle.currentState}")
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

    val accent = MaterialTheme.colorScheme.primary
    val background = if (showAsDisc) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f)
    val shape = if (showAsDisc) MaterialTheme.shapes.large else CircleShape
    Column(modifier.background(background, shape)
        .padding(if (showAsDisc) PaddingValues(12.dp) else PaddingValues(horizontal = 8.dp, vertical = 6.dp)),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val visualSize = if (showAsDisc) 76.dp else 36.dp
            Box(Modifier.size(visualSize), contentAlignment = Alignment.Center) {
                if (showAsDisc) Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color(0xFF211D30))
                    for (ratio in listOf(.83f, .9f, .96f)) {
                        drawCircle(Color.White.copy(alpha = .19f), radius = size.minDimension * .5f * ratio,
                            style = Stroke(width = 1.dp.toPx()))
                    }
                    drawCircle(accent, radius = size.minDimension * .24f)
                }
                val iconTint = if (showAsDisc) MaterialTheme.colorScheme.onPrimary else accent
                IconButton(onClick = ::togglePlay, modifier = Modifier.fillMaxSize()) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = iconTint)
                    else Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pausar" else "Reproducir", tint = iconTint)
                }
            }
            Spacer(Modifier.width(if (showAsDisc) 14.dp else 8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (showAsDisc) Text("Música de entrada", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = accent, trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .2f))
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) }
    }
}
