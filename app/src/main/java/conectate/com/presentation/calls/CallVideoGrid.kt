package conectate.com.presentation.calls

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import conectate.com.data.model.CallParticipant
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.WebRtcVideoView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

@Composable
internal fun CallVideoGrid(
    participants: List<CallParticipant>,
    tracks: Map<String, VideoTrack>,
    localTrack: VideoTrack?,
    eglContext: EglBase.Context?,
) {
    LazyVerticalGrid(columns = GridCells.Adaptive(140.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(participants, key = { "${it.userId}:${it.socketId}" }) { participant ->
            val track = if (participant.isVideoEnabled) tracks[participant.socketId] ?: tracks[participant.userId] else null
            CallVideoTile(participant.name, participant.avatarUrl, track, eglContext, mirror = false)
        }
        if (localTrack != null) item(key = "local-camera") {
            CallVideoTile("Tú", null, localTrack, eglContext, mirror = true)
        }
    }
}

@Composable
private fun CallVideoTile(name: String, avatar: String?, track: VideoTrack?, egl: EglBase.Context?, mirror: Boolean) {
    Card(Modifier.fillMaxWidth().aspectRatio(.75f)) {
        Box(Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            if (track != null) WebRtcVideoView(videoTrack = track, sharedEglContext = egl,
                modifier = Modifier.fillMaxSize(), mirror = mirror,
                scalingType = RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            else CircularProfileImage(imageUrl = avatar, size = 64.dp, fallbackName = name, showBorder = false)
            Text(name.ifBlank { "Participante" }, color = Color.White, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha = .6f)).padding(8.dp))
        }
    }
}
