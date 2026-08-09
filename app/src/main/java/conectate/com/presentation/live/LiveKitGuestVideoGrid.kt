package conectate.com.presentation.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import conectate.com.data.livekit.LiveKitRoomBridge
import io.livekit.android.renderer.TextureViewRenderer

@Composable
internal fun LiveKitGuestVideoGrid(
    videos: List<LiveKitRoomBridge.RemoteVideo>,
    onAttach: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Boolean,
    onDetach: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit,
    maxSlots: Int = 4,
    showEmptySlots: Boolean = false,
    emptySlotLabel: String = "Solicitar",
    onEmptySlotClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val visibleVideos = videos.take(maxSlots.coerceIn(1, 4))
    val emptyCount = if (showEmptySlots) {
        (maxSlots.coerceIn(1, 4) - visibleVideos.size).coerceAtLeast(0)
    } else {
        0
    }
    if (visibleVideos.isEmpty() && emptyCount == 0) return
    val slots: List<LiveKitRoomBridge.RemoteVideo?> =
        visibleVideos.map { it } + List(emptyCount) { null }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        slots.chunked(2).forEach { rowVideos ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowVideos.forEach { video ->
                    if (video != null) {
                        LiveKitGuestVideo(video, onAttach, onDetach)
                    } else {
                        LiveKitEmptyGuestSlot(
                            label = emptySlotLabel,
                            onClick = onEmptySlotClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveKitEmptyGuestSlot(
    label: String,
    onClick: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.68f))
            .border(1.dp, Color.White.copy(alpha = 0.46f), RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(86.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text(
                text = "+",
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Light
            )
        }
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun LiveKitGuestVideo(
    video: LiveKitRoomBridge.RemoteVideo,
    onAttach: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Boolean,
    onDetach: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit
) {
    val context = LocalContext.current
    val renderer = remember(video.trackId) { TextureViewRenderer(context) }
    DisposableEffect(video.trackId, renderer) {
        onAttach(video, renderer)
        onDispose { onDetach(video, renderer) }
    }
    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
    ) {
        AndroidView(
            factory = { renderer },
            modifier = Modifier.fillMaxWidth().height(154.dp)
        )
        Text(
            text = video.participantName,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}
