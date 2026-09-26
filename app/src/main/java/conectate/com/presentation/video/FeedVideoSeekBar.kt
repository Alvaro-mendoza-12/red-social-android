package conectate.com.presentation.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Isolate frequent clock updates from the video surface, avatars and action column. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun FeedVideoSeekBar(videoId: String, player: ExoPlayer, modifier: Modifier = Modifier) {
    var progress by remember(videoId) { mutableFloatStateOf(0f) }
    var durationMs by remember(videoId) { mutableLongStateOf(0L) }
    var isSeeking by remember(videoId) { mutableStateOf(false) }
    LaunchedEffect(videoId, player) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (player.currentMediaItem?.mediaId == videoId) {
                durationMs = player.duration.coerceAtLeast(0L)
                if (!isSeeking && durationMs > 0) {
                    progress = (player.currentPosition.toFloat() / durationMs).coerceIn(0f, 1f)
                }
            }
            delay(250L)
        }
    }
    if (durationMs <= 0) return
    val colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = Color.White.copy(alpha = 0.35f)
    )
    Slider(
        value = progress,
        onValueChange = { isSeeking = true; progress = it.coerceIn(0f, 1f) },
        onValueChangeFinished = {
            // Commit once after dragging. Do not restart decoding for every pointer event.
            if (player.currentMediaItem?.mediaId == videoId) {
                player.seekTo((durationMs * progress).toLong())
            }
            isSeeking = false
        },
        modifier = modifier.semantics { contentDescription = "Posición del video" },
        colors = colors,
        thumb = { Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White)) },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state, modifier = Modifier.height(2.dp), colors = colors,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp
            )
        }
    )
}
