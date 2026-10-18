package conectate.com.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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

/** A renderer belongs to its EGL context, not to an SDP callback or a clock tick. */
@Composable
fun WebRtcVideoView(
    videoTrack: VideoTrack?,
    sharedEglContext: EglBase.Context?,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    scalingType: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
    onFirstFrameRendered: (() -> Unit)? = null,
    mediaOverlay: Boolean = false,
    zoomEnabled: Boolean = false,
) {
    val firstFrameCallback by rememberUpdatedState(onFirstFrameRendered)
    if (sharedEglContext == null) { Box(modifier = modifier); return }
    var frameRatio by remember(sharedEglContext) { mutableStateOf(0f) }
    BoxWithConstraints(modifier = modifier.videoZoom(zoomEnabled, videoTrack)) {
        val fit = scalingType == RendererCommon.ScalingType.SCALE_ASPECT_FIT && frameRatio > 0f &&
            maxWidth.value.isFinite() && maxHeight.value.isFinite() && maxHeight.value > 0f
        val fractions = if (fit) containedVideoFraction(maxWidth / maxHeight, frameRatio) else 1f to 1f
        val videoModifier = if (fit) Modifier.align(Alignment.Center).size(maxWidth * fractions.first, maxHeight * fractions.second)
            else Modifier.fillMaxSize()
        key(sharedEglContext, mediaOverlay) {
            AndroidView(
                factory = { ctx ->
                    StableVideoRenderer(ctx).also { view ->
                        runCatching { view.init(sharedEglContext, object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() { view.post { firstFrameCallback?.invoke() } }
                            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                                if (width <= 0 || height <= 0) return
                                val ratio = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
                                view.post { if (kotlin.math.abs(frameRatio - ratio) > 0.001f) frameRatio = ratio }
                            }
                        }) }
                        // Fixed hardware surfaces are resized on resolution changes and can
                        // briefly go black on vendor devices. Let the view own its surface size.
                        view.setEnableHardwareScaler(false)
                        view.setZOrderOnTop(false)
                        view.setZOrderMediaOverlay(mediaOverlay)
                    }
                },
                update = { view -> view.configure(mirror, scalingType); view.bind(videoTrack) },
                onRelease = { view -> view.bind(null); runCatching { view.release() } },
                modifier = videoModifier,
            )
        }
    }
}

private class StableVideoRenderer(context: android.content.Context) : SurfaceViewRenderer(context) {
    private var boundTrack: VideoTrack? = null
    private var configuredMirror: Boolean? = null
    private var configuredScaling: RendererCommon.ScalingType? = null
    fun configure(mirror: Boolean, scaling: RendererCommon.ScalingType) {
        if (configuredMirror != mirror) { setMirror(mirror); configuredMirror = mirror }
        if (configuredScaling != scaling) { setScalingType(scaling); configuredScaling = scaling }
    }
    fun bind(next: VideoTrack?) {
        if (boundTrack === next) return
        boundTrack?.let { old -> runCatching { old.removeSink(this) } }
        boundTrack = next
        next?.let { track -> runCatching { track.addSink(this) } }
    }
}
