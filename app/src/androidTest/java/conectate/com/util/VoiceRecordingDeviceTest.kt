package conectate.com.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.core.observability.AppMetrics
import conectate.com.core.observability.MetricAlert
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires RECORD_AUDIO on the isolated test installation; never sends audio to a server. */
@RunWith(AndroidJUnit4::class)
class VoiceRecordingDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun recorder(): AudioRecorder {
        assertEquals(PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        return AudioRecorder(context, Metrics)
    }

    private fun assertAudioContainer(file: File?) {
        assertNotNull("AAC encoding returned no file", file)
        assertTrue("AAC output is empty", file!!.length() > 0L)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(44_100, format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            assertTrue("Recorded duration is unexpectedly short", format.getLong(MediaFormat.KEY_DURATION) >= 400_000L)
            extractor.selectTrack(0)
            assertTrue("M4A contains no audio sample", extractor.sampleTime >= 0L)
        } finally { extractor.release() }
    }

    @Test(timeout = 40_000L) fun pauseResumeAndAppendProduceCompleteAacContainers() = runBlocking {
        val recorder = recorder()
        var first: File? = null
        var appended: File? = null
        try {
            assertNotNull(recorder.start())
            delay(800L)
            repeat(4) { assertTrue(recorder.pause()); assertTrue(recorder.resume()) }
            delay(800L)
            first = requireNotNull(recorder.detachFinalization()).finish()
            assertAudioContainer(first)
            assertNotNull(recorder.start(append = true))
            delay(600L)
            appended = requireNotNull(recorder.detachFinalization()).finish()
            assertAudioContainer(appended)
        } finally { recorder.cancel(); first?.delete(); appended?.delete() }
    }

    @Test(timeout = 40_000L) fun discardedOldFinalizationCannotCloseANewRecording() = runBlocking {
        val recorder = recorder()
        var oldFile: File? = null
        var newFile: File? = null
        try {
            assertNotNull(recorder.start())
            delay(600L)
            val old = requireNotNull(recorder.detachFinalization())
            val encoding = async(start = CoroutineStart.UNDISPATCHED) { old.finish().also { oldFile = it } }
            old.discard()
            recorder.cancel()
            assertNotNull(recorder.start())
            delay(800L)
            assertTrue("Old cleanup stopped the new microphone", recorder.isRecording())
            newFile = requireNotNull(recorder.detachFinalization()).finish()
            assertAudioContainer(newFile)
            try { encoding.await() } catch (_: CancellationException) { /* Old work was explicitly discarded. */ }
            assertFalse("Discarded output survived", oldFile?.exists() == true)
        } finally { recorder.cancel(); oldFile?.delete(); newFile?.delete() }
    }

    private object Metrics : AppMetrics {
        override val alerts = emptyFlow<MetricAlert>()
        override fun incrementCounter(metric: String, delta: Long, attributes: Map<String, String>) = Unit
        override fun recordLatency(metric: String, durationMs: Long, attributes: Map<String, String>) = Unit
        override fun recordFailure(metric: String, throwable: Throwable?, attributes: Map<String, String>) = Unit
    }
}
