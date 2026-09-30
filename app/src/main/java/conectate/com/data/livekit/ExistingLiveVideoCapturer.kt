package conectate.com.data.livekit

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Feeds the collaboration encoder from the broadcast's selected capture.
 * The two SDKs have different native namespaces, so their tracks cannot be
 * shared directly. Retained I420 planes cross the boundary without owning or
 * opening the original camera. Rotation, filters and screen frames are retained.
 */
internal class ExistingLiveVideoCapturer(
    private val source: StateFlow<org.webrtc.VideoTrack?>
) : VideoCapturer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindingLock = Any()
    private val frameLock = Any()
    private var collector: Job? = null
    private var boundTrack: org.webrtc.VideoTrack? = null
    private var observer: CapturerObserver? = null
    @Volatile private var capturing = false
    @Volatile private var disposed = false
    private val reportedFailure = AtomicBoolean(false)
    private val sink = org.webrtc.VideoSink { frame ->
        synchronized(frameLock) {
            if (!capturing || disposed) return@VideoSink
            val receiver = observer ?: return@VideoSink
            val planes = try {
                frame.buffer.toI420() ?: return@VideoSink
            } catch (error: Exception) {
                report(error)
                return@VideoSink
            }
            val converted = try {
                livekit.org.webrtc.JavaI420Buffer.wrap(
                    planes.width, planes.height,
                    planes.dataY, planes.strideY, planes.dataU, planes.strideU,
                    planes.dataV, planes.strideV, Runnable { planes.release() }
                )
            } catch (error: Exception) {
                planes.release()
                report(error)
                return@VideoSink
            }
            val shared = livekit.org.webrtc.VideoFrame(converted, frame.rotation, frame.timestampNs)
            try {
                receiver.onFrameCaptured(shared)
            } catch (error: Exception) {
                report(error)
            } finally {
                shared.release()
            }
        }
    }

    override fun initialize(helper: SurfaceTextureHelper?, context: Context?, observer: CapturerObserver?) {
        synchronized(frameLock) { this.observer = observer }
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        synchronized(bindingLock) {
            check(!disposed) { "The shared capture is already closed" }
            if (capturing) return
            synchronized(frameLock) {
                capturing = true
                observer?.onCapturerStarted(true)
            }
            bindLocked(source.value)
            collector = scope.launch {
                source.collect { track ->
                    synchronized(bindingLock) { if (capturing && !disposed) bindLocked(track) }
                }
            }
        }
    }

    override fun stopCapture() {
        // Never hold frameLock while removing a native sink: removal can wait
        // for an in-flight callback. This ordering also blocks late frames.
        synchronized(frameLock) {
            if (!capturing) return
            capturing = false
            observer?.onCapturerStopped()
        }
        synchronized(bindingLock) {
            collector?.cancel()
            collector = null
            bindLocked(null)
        }
    }

    private fun bindLocked(track: org.webrtc.VideoTrack?) {
        if (boundTrack === track) return
        boundTrack?.let { previous -> runCatching { previous.removeSink(sink) } }
        boundTrack = null
        if (track != null) {
            runCatching { track.addSink(sink) }.onSuccess { boundTrack = track }.onFailure { report(it) }
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) = Unit
    override fun isScreencast(): Boolean = false
    override fun dispose() {
        stopCapture()
        synchronized(bindingLock) { disposed = true }
        synchronized(frameLock) { observer = null }
        scope.cancel()
    }

    private fun report(error: Throwable) {
        if (reportedFailure.compareAndSet(false, true)) {
            android.util.Log.e("ExistingLiveVideo", "Could not forward the existing live frame", error)
        }
    }
}
