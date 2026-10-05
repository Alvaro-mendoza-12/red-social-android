package conectate.com.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
    onFirstFrameRendered: (() -> Unit)? = null,
) {
    val firstFrameCallback by rememberUpdatedState(onFirstFrameRendered)
    if (sharedEglContext == null) {
        Box(modifier = modifier)
        return
    }

    // id() reads native memory. A queued recomposition can outlive a disposed
    // track when a call ends; use the Java object's identity as the UI key.
    var renderer by remember(sharedEglContext, videoTrack) { mutableStateOf<SurfaceViewRenderer?>(null) }

    key(sharedEglContext, videoTrack) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also { view ->
                    runCatching {
                        view.init(sharedEglContext, object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() { firstFrameCallback?.invoke() }
                            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
                        })
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
            runCatching { videoTrack.setEnabled(true) }
            runCatching { videoTrack.addSink(sink) }
        }

        onDispose {
            if (sink != null) {
                runCatching { videoTrack?.removeSink(sink) }
            }
        }
    }
}
