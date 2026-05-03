package conectate.com.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun WebRtcVideoView(
    videoTrack: VideoTrack?,
    sharedEglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL
) {
    if (sharedEglContext == null) {
        Box(modifier = modifier)
        return
    }

    var renderer by remember(sharedEglContext) { mutableStateOf<SurfaceViewRenderer?>(null) }

    key(sharedEglContext) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also { view ->
                    runCatching {
                        view.init(sharedEglContext, null)
                        view.setEnableHardwareScaler(true)
                        view.setMirror(mirror)
                        view.setScalingType(scalingType)
                        view.setZOrderMediaOverlay(false)
                        view.setZOrderOnTop(false)
                        renderer = view
                    }
                }
            },
            update = { view ->
                renderer = view
                view.setMirror(mirror)
                view.setScalingType(scalingType)
            },
            onRelease = { view ->
                renderer = null
                runCatching { videoTrack?.removeSink(view) }
                runCatching { view.clearImage() }
                runCatching { view.release() }
            },
            modifier = modifier
        )
    }

    DisposableEffect(renderer, videoTrack) {
        val sink = renderer
        if (sink != null && videoTrack != null) {
            videoTrack.setEnabled(true)
            videoTrack.addSink(sink)
        }

        onDispose {
            if (sink != null) {
                runCatching { videoTrack?.removeSink(sink) }
            }
        }
    }
}
