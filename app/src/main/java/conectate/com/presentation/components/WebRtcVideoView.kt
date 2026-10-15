package conectate.com.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
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
    var frameRatio by remember(sharedEglContext, videoTrack) { mutableStateOf(0f) }

    BoxWithConstraints(modifier = modifier) {
    val fit = scalingType == RendererCommon.ScalingType.SCALE_ASPECT_FIT && frameRatio > 0f &&
        maxWidth.value.isFinite() && maxHeight.value.isFinite() && maxHeight.value > 0f
    val fractions = if (fit) containedVideoFraction(maxWidth / maxHeight, frameRatio) else 1f to 1f
    val videoModifier = if (fit) Modifier.align(Alignment.Center).size(maxWidth * fractions.first, maxHeight * fractions.second)
        else Modifier.fillMaxSize()
    key(sharedEglContext, videoTrack) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also { view ->
                    runCatching {
                        view.init(sharedEglContext, object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() { firstFrameCallback?.invoke() }
                            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                                if (width <= 0 || height <= 0) return
                                val ratio = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
                                view.post { frameRatio = ratio }
                            }
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
            modifier = videoModifier
        )
    }
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
