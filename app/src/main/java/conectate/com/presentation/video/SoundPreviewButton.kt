package conectate.com.presentation.video

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import conectate.com.presentation.common.AppFeedbackBus
import conectate.com.util.SoundPreviewPlayback

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun SoundPreviewButton(sourceUrls: List<String>, onPlayingSource: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val candidates = remember(sourceUrls) { sourceUrls.filter { it.startsWith("https://") }.distinct() }
    val currentOnPlayingSource by rememberUpdatedState(onPlayingSource)
    var isPlaying by remember(candidates) { mutableStateOf(false) }
    var isLoading by remember(candidates) { mutableStateOf(false) }
    var requested by remember(candidates) { mutableStateOf(false) }
    var failed by remember(candidates) { mutableStateOf(false) }
    var sourceIndex by remember(candidates) { mutableIntStateOf(0) }
    val player = remember(candidates) {
        candidates.takeIf { it.isNotEmpty() }?.let { SoundPreviewPlayback.create(context) }
    }
    DisposableEffect(player, lifecycleOwner) {
        fun prepareSource() {
            val source = candidates.getOrNull(sourceIndex) ?: return
            player?.setMediaItem(SoundPreviewPlayback.mediaItem(source))
            player?.prepare()
            player?.playWhenReady = requested
        }
        fun tryNextSource() {
            if (sourceIndex + 1 < candidates.size) {
                sourceIndex++
                prepareSource()
            } else {
                val notify = requested
                requested = false
                player?.pause()
                failed = true
                isLoading = false
                isPlaying = false
                if (notify) AppFeedbackBus.showError("No se pudo escuchar este sonido. Puedes reintentarlo.")
            }
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (playing) candidates.getOrNull(sourceIndex)?.let(currentOnPlayingSource)
            }
            override fun onPlaybackStateChanged(state: Int) {
                isLoading = state == Player.STATE_BUFFERING && requested
            }
            override fun onPlayerError(error: PlaybackException) = tryNextSource()
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isNotEmpty() && !tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) tryNextSource()
            }
        }
        player?.addListener(listener)
        val observer = LifecycleEventObserver { _, _ ->
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                requested = false
                isLoading = false
                player?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Warm up only the opened sound sheet; no autoplay or whole-file download.
        prepareSource()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player?.removeListener(listener)
            player?.release()
        }
    }
    IconButton(onClick = {
        if (requested || isPlaying) {
            requested = false
            isLoading = false
            player?.pause()
        } else {
            requested = true
            isLoading = player?.playbackState != Player.STATE_READY
            if (failed) {
                sourceIndex = 0
                failed = false
                candidates.firstOrNull()?.let { player?.setMediaItem(SoundPreviewPlayback.mediaItem(it)) }
            }
            if (player?.playbackState == Player.STATE_IDLE) player.prepare()
            player?.play()
        }
    }, enabled = player != null, modifier = Modifier.semantics {
        contentDescription = if (isLoading) "Cancelar carga del sonido" else if (failed) "Reintentar escuchar sonido"
            else if (isPlaying) "Pausar sonido" else "Escuchar sonido"
    }) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (failed) "Reintentar escuchar sonido" else if (isPlaying) "Pausar sonido" else "Escuchar sonido")
        }
    }
}
