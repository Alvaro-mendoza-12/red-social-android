package conectate.com

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.webrtc.SignalingEvents
import conectate.com.data.webrtc.WebRTCManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.VideoSink
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Measures the actual camera before network/encoder effects. Not a load test. */
@RunWith(AndroidJUnit4::class)
class CameraCadenceAuditTest {
    @Test(timeout = 180_000) fun measureBothCamerasBeforeTransport() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditCameraCadence") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reports = JSONArray()
        for (front in listOf(false, true)) {
            val manager = WebRTCManager(context, object : SignalingEvents {
                override fun onSendOffer(targetSocketId: String, offer: JSONObject) = Unit
                override fun onSendAnswer(targetSocketId: String, answer: JSONObject) = Unit
                override fun onSendIceCandidate(targetSocketId: String, candidate: JSONObject) = Unit
            })
            val frames = AtomicInteger()
            val sink = VideoSink { frames.incrementAndGet() }
            var track: org.webrtc.VideoTrack? = null
            try {
                for (requested in listOf(30, 60)) {
                    val ready = CountDownLatch(1)
                    var success = false
                    manager.startVideo(false, isFront = front, targetWidth = 640, targetHeight = 480,
                        targetFps = requested, onComplete = { success = it; ready.countDown() })
                    assertTrue("Capture setup timed out", ready.await(15, TimeUnit.SECONDS))
                    assertTrue("Capture setup failed", success)
                    if (track == null) {
                        track = manager.localVideoTrack.value
                        assertNotNull(track)
                        track!!.addSink(sink)
                    }
                    Thread.sleep(2_000)
                    val before = frames.get()
                    val started = System.nanoTime()
                    Thread.sleep(8_000)
                    val fps = (frames.get() - before) * 1_000_000_000.0 / (System.nanoTime() - started)
                    reports.put(JSONObject().put("front", front).put("requestedFps", requested).put("captureFps", fps))
                    assertTrue("Camera produced almost no frames: $fps", fps >= 8)
                }
            } finally {
                track?.let { runCatching { it.removeSink(sink) } }
                val released = CountDownLatch(1)
                manager.destroy { released.countDown() }
                assertTrue("Camera resources did not close", released.await(15, TimeUnit.SECONDS))
                Thread.sleep(500)
            }
        }
        File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "camera-cadence.json")
            .writeText(reports.toString(2))
    }
}
