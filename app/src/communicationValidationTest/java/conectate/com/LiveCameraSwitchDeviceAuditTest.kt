package conectate.com

import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.webrtc.SignalingEvents
import conectate.com.data.webrtc.WebRTCManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.Camera2Enumerator
import org.webrtc.VideoSink
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class LiveCameraSwitchDeviceAuditTest {
    @Test(timeout = 120_000) fun frontBackFrontKeepsNativeFramesAndStopRemovesTrack() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cameras = Camera2Enumerator(context)
        assertTrue("Debe existir cámara frontal", cameras.deviceNames.any(cameras::isFrontFacing))
        assertTrue("Debe existir cámara trasera", cameras.deviceNames.any(cameras::isBackFacing))
        val manager = WebRTCManager(context, object : SignalingEvents {
            override fun onSendOffer(targetSocketId: String, offer: JSONObject) = Unit
            override fun onSendAnswer(targetSocketId: String, answer: JSONObject) = Unit
            override fun onSendIceCandidate(targetSocketId: String, candidate: JSONObject) = Unit
        })
        var attached: org.webrtc.VideoTrack? = null
        val frames = AtomicInteger(0)
        val sink = VideoSink { frames.incrementAndGet() }
        fun cameraName(): String {
            val field = manager.javaClass.getDeclaredField("videoCapturer").apply { isAccessible = true }
            val capturer = field.get(manager) ?: error("Sin capturador")
            var type: Class<*>? = capturer.javaClass
            while (type != null) {
                val found = type.declaredFields.firstOrNull { it.name == "cameraName" }
                if (found != null) { found.isAccessible = true; return found.get(capturer) as String }
                type = type.superclass
            }
            error("No se pudo observar la cámara nativa seleccionada")
        }
        fun awaitFrames(after: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (frames.get() < after + 3 && System.nanoTime() < deadline) Thread.sleep(30)
            assertTrue("No llegan imágenes tras el cambio de cámara", frames.get() >= after + 3)
        }
        try {
            assertTrue(runBlocking { withTimeout(15_000) {
                manager.startVideoAwait(isScreen = false, isFront = true, targetWidth = 320, targetHeight = 240, targetFps = 15)
            } })
            attached = manager.localVideoTrack.value
            assertNotNull(attached); attached!!.addSink(sink); awaitFrames(0)
            assertTrue(cameras.isFrontFacing(cameraName()))
            for (front in listOf(false, true)) {
                val before = frames.get(); val switched = CountDownLatch(1); val success = AtomicBoolean(false)
                assertTrue(manager.switchCamera { result -> success.set(result); switched.countDown() })
                assertTrue("El cambio de cámara no terminó", switched.await(10, TimeUnit.SECONDS))
                assertTrue("El capturador rechazó el cambio", success.get())
                assertEquals(front, cameras.isFrontFacing(cameraName()))
                awaitFrames(before)
            }
            attached!!.removeSink(sink); attached = null
            runBlocking { withTimeout(10_000) { manager.stopVideoAwait() } }
            assertNull("Apagar cámara debe retirar la pista", manager.localVideoTrack.value)
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }, "native-camera-switch.json")
                .writeText(JSONObject().put("frontBackFront",true).put("framesObserved",frames.get()).put("trackRemovedAfterStop",true).put("emulatedCameras",true).toString(2))
        } finally {
            attached?.removeSink(sink)
            val closed = CountDownLatch(1); manager.destroy { closed.countDown() }
            assertTrue("El capturador no cerró", closed.await(15, TimeUnit.SECONDS))
        }
    }
}
