package conectate.com

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No frames leave the phone. Separates sensor cadence from filters, codecs and transport. */
@RunWith(AndroidJUnit4::class)
class CameraSensorCadenceAuditTest {
    @Test(timeout = 180_000) fun measureRawCaptureAndCapabilities() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditCameraSensor") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val report = JSONObject().put("cameras", JSONArray(cameraManager.cameraIdList.map { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            JSONObject().put("id", id)
                .put("facing", characteristics.get(CameraCharacteristics.LENS_FACING))
                .put("hardwareLevel", characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                .put("aeFpsRanges", JSONArray(characteristics.get(
                    CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
                ).orEmpty().map { JSONArray(listOf(it.lower, it.upper)) }))
                .put("formats", JSONArray(Camera2Enumerator(context).getSupportedFormats(id).orEmpty().map {
                    JSONObject().put("width", it.width).put("height", it.height)
                        .put("minFps", it.framerate.min).put("maxFps", it.framerate.max)
                }))
        })).put("encoders", JSONArray(MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.startsWith("video/") } }
            .map { codec -> JSONObject().put("name", codec.name).put("hardware", codec.isHardwareAccelerated)
                .put("types", JSONArray(codec.supportedTypes.toList())) }))
        val samples = JSONArray()
        val egl = EglBase.create()
        try {
            val enumerator: CameraEnumerator = if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context)
                else Camera1Enumerator(true)
            for (steady in listOf(false, true)) for (front in listOf(false, true)) {
                val camera = enumerator.deviceNames.firstOrNull {
                    if (front) enumerator.isFrontFacing(it) else enumerator.isBackFacing(it)
                } ?: continue
                val capturer = if (steady && enumerator is Camera2Enumerator)
                    org.webrtc.NovvinCamera2Capturer(context, camera, null)
                    else checkNotNull(enumerator.createCapturer(camera, null))
                val helper = SurfaceTextureHelper.create("SensorCadenceAudit", egl.eglBaseContext)
                val ready = CountDownLatch(1)
                val frames = AtomicInteger()
                var width = 0
                var height = 0
                capturer.initialize(helper, context, object : CapturerObserver {
                    override fun onCapturerStarted(success: Boolean) { if (success) ready.countDown() }
                    override fun onCapturerStopped() = Unit
                    override fun onFrameCaptured(frame: VideoFrame) {
                        width = frame.buffer.width; height = frame.buffer.height
                        frames.incrementAndGet()
                    }
                })
                try {
                    for (requested in listOf(30, 60)) {
                        if (requested == 30) capturer.startCapture(640, 480, requested)
                        else capturer.changeCaptureFormat(640, 480, requested)
                        assertTrue("Raw camera did not start", ready.await(15, TimeUnit.SECONDS))
                        Thread.sleep(2_000)
                        val before = frames.get()
                        val started = System.nanoTime()
                        Thread.sleep(5_000)
                        val fps = (frames.get() - before) * 1_000_000_000.0 / (System.nanoTime() - started)
                        samples.put(JSONObject().put("api", enumerator.javaClass.simpleName).put("steady", steady)
                            .put("front", front).put("requestedFps", requested).put("rawFps", fps)
                            .put("width", width).put("height", height))
                        assertTrue("Raw camera produced almost no frames: $fps", fps >= 5)
                        val fixed = cameraManager.getCameraCharacteristics(camera)
                            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
                            .filter { it.lower == it.upper && it.upper <= requested }
                            .maxOfOrNull { it.upper }
                        if (steady && fixed != null) assertTrue(
                            "Steady camera is below advertised cadence: $fps / $fixed", fps >= fixed * 0.9)
                    }
                } finally {
                    capturer.stopCapture(); capturer.dispose(); helper.dispose()
                    Thread.sleep(500)
                }
            }
        } finally {
            egl.release()
            report.put("samples", samples)
            File(context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() },
                "camera-sensor-cadence.json").writeText(report.toString(2))
        }
    }
}
