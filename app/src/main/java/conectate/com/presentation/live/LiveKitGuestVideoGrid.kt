package conectate.com.presentation.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
    modifier: Modifier = Modifier,
    maxSlots: Int = 4,
    showEmptySlots: Boolean = false,
    emptySlotLabel: String = "Solicitar",
    onEmptySlotClick: (() -> Unit)? = null,
) {
    val visibleVideos = videos.take(maxSlots.coerceIn(1, 4))
    val emptyCount = if (showEmptySlots) {
        (maxSlots.coerceIn(1, 4) - visibleVideos.size).coerceAtLeast(0)
    } else {
        0
    }
    if (visibleVideos.isEmpty() && emptyCount == 0) return
    BoxWithConstraints(modifier = modifier) {
        val slots: List<LiveKitRoomBridge.RemoteVideo?> =
            visibleVideos.map { it } + List(emptyCount) { null }
        val pip = visibleVideos.size == 1 && emptyCount == 0
        if (pip) {
            val pipWidth = (maxWidth * 0.29f).coerceIn(112.dp, 204.dp)
            LiveKitGuestVideo(
                video = visibleVideos.first(),
                onAttach = onAttach,
                onDetach = onDetach,
                width = pipWidth,
                height = (pipWidth * 1.52f).coerceIn(170.dp, 314.dp),
                pictureInPicture = true,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}

@Composable
internal fun LiveKitRemoteVideoSurface(
    video: LiveKitRoomBridge.RemoteVideo,
    onAttach: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Boolean,
    onDetach: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit,
    modifier: Modifier = Modifier,
    label: String = video.participantName,
) {
    val context = LocalContext.current
    val renderer = remember(video.trackId, video.track, video.bindingVersion) { TextureViewRenderer(context) }
    DisposableEffect(video.trackId, video.track, video.bindingVersion, renderer) {
        onAttach(video, renderer)
        onDispose { onDetach(video, renderer) }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        key(renderer) { AndroidView(factory = { renderer }, modifier = Modifier.fillMaxSize()) }
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.56f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        )
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
    onDetach: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit,
    width: androidx.compose.ui.unit.Dp = 132.dp,
    height: androidx.compose.ui.unit.Dp = 154.dp,
    pictureInPicture: Boolean = false,
) {
    val context = LocalContext.current
    val renderer = remember(video.trackId, video.track, video.bindingVersion) { TextureViewRenderer(context) }
    DisposableEffect(video.trackId, video.track, video.bindingVersion, renderer) {
        onAttach(video, renderer)
        onDispose { onDetach(video, renderer) }
    }
    if (pictureInPicture) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.68f))
                .border(1.dp, Color.White.copy(alpha = 0.62f), RoundedCornerShape(18.dp))
        ) {
            key(renderer) { AndroidView(factory = { renderer }, modifier = Modifier.fillMaxSize()) }
            LiveQualityBars(
                tint = Color(0xFF5EEAD4),
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopStart)
                    .padding(start = 10.dp, top = 10.dp),
            )
            Text(
                text = "Invitado\n${video.participantName}",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.42f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    } else {
        Column(
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
        ) {
            key(renderer) { AndroidView(
                factory = { renderer },
                modifier = Modifier.fillMaxWidth().height(height)
            ) }
            Text(
                text = video.participantName,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
            )
        }
    }
}
