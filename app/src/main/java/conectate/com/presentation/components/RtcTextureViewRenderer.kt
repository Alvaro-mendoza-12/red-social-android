package conectate.com.presentation.components

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceEglRenderer
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.CountDownLatch

/**
 * Texture-backed renderer for the org.webrtc transport. Unlike a SurfaceView,
 * it participates in Compose clipping, zoom and overlapping video previews.
 * LiveKit's renderer uses a different, relocated WebRTC SDK and cannot accept
 * the native call/voice-room tracks directly.
 */
internal open class RtcTextureViewRenderer(context: Context) : TextureView(context),
    VideoSink, TextureView.SurfaceTextureListener {
    private val renderer = SurfaceEglRenderer("NovvinRtcTexture")
    private val layoutMeasure = RendererCommon.VideoLayoutMeasure()
    private var frameWidth = 0
    private var frameHeight = 0
    private var initialized = false

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun init(sharedContext: EglBase.Context, events: RendererCommon.RendererEvents) {
        ThreadUtils.checkIsOnMainThread()
        check(!initialized)
        renderer.init(sharedContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = events.onFirstFrameRendered()
            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                events.onFrameResolutionChanged(width, height, rotation)
                post {
                    if (initialized) {
                        frameWidth = if (rotation % 180 == 0) width else height
                        frameHeight = if (rotation % 180 == 0) height else width
                        requestLayout()
                    }
                }
            }
        }, EglBase.CONFIG_PLAIN, GlRectDrawer())
        initialized = true
        if (isAvailable) surfaceTexture?.let { renderer.createEglSurface(it) }
    }

    fun setMirror(mirror: Boolean) = renderer.setMirror(mirror)

    fun setScalingType(scaling: RendererCommon.ScalingType) {
        layoutMeasure.setScalingType(scaling)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = layoutMeasure.measure(widthMeasureSpec, heightMeasureSpec, frameWidth, frameHeight)
        setMeasuredDimension(size.x, size.y)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (right > left && bottom > top) {
            renderer.setLayoutAspectRatio((right - left).toFloat() / (bottom - top))
        }
    }

    override fun onFrame(frame: VideoFrame) = renderer.onFrame(frame)

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (initialized) renderer.createEglSurface(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        if (initialized) {
            val released = CountDownLatch(1)
            renderer.releaseEglSurface { released.countDown() }
            ThreadUtils.awaitUninterruptibly(released)
        }
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    fun release() {
        if (!initialized) return
        initialized = false
        renderer.release()
    }
}
