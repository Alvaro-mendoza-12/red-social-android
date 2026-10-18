package conectate.com.presentation.live

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import conectate.com.data.livekit.LiveKitRoomBridge
import conectate.com.presentation.components.containedVideoFraction
import io.livekit.android.renderer.TextureViewRenderer

/** Compose's exact constraints must match the frame ratio: FIT alone cannot
 * override a full-screen exact measure spec inside the SDK renderer. */
@Composable
internal fun ContainedLiveKitVideo(
    video: LiveKitRoomBridge.RemoteVideo,
    onAttach: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Boolean,
    onDetach: (LiveKitRoomBridge.RemoteVideo, TextureViewRenderer) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var frame by remember(video.trackId, video.track, video.bindingVersion) { mutableStateOf(IntSize.Zero) }
    val renderer = remember(video.trackId, video.track, video.bindingVersion) {
        object : TextureViewRenderer(context) {
            override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                super.onFrameResolutionChanged(videoWidth, videoHeight, rotation)
                val size = if (rotation % 180 == 0) IntSize(videoWidth, videoHeight) else IntSize(videoHeight, videoWidth)
                post { frame = size }
            }
        }
    }
    DisposableEffect(renderer) {
        onAttach(video, renderer)
        onDispose { onDetach(video, renderer) }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fraction = containedVideoFraction(maxWidth.value / maxHeight.value, frame.width.toFloat() / frame.height)
        key(renderer) {
            AndroidView(
                factory = { renderer },
                modifier = Modifier.fillMaxWidth(fraction.first).fillMaxHeight(fraction.second)
                    .alpha(if (frame.width > 0 && frame.height > 0) 1f else 0f),
            )
        }
    }
}
