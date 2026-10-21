package conectate.com.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class LocalAudioDurationTest {
    @Test fun webmWithoutContainerDurationUsesItsAudioPacketTimestamps() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File.createTempFile("webm-duration-", ".webm", context.cacheDir)
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                .open("audio/no-duration.webm").use { input -> file.outputStream().use { input.copyTo(it) } }
            val duration=localAudioDurationMs(context,file.absolutePath)
            assertNotNull(duration);assertTrue("duration=$duration",duration!! in 3_100..3_250)
            assertEquals("0:03",audioTimeLabel(duration))
        } finally { file.delete() }
    }
    @Test fun localWaveFileReportsItsDurationWithoutPlaying() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val file=File.createTempFile("duration-", ".wav", context.cacheDir)
        try {
            val samples=ByteArray(8_000 * 2 * 3) // Three seconds of mono, 16-bit PCM.
            val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(samples.size+36).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size).array()
            file.outputStream().use { it.write(header);it.write(samples) }
            val duration=localAudioDurationMs(context,file.toURI().toString())
            assertNotNull(duration);assertTrue(duration!! in 2_950..3_050)
            assertEquals("0:03",audioTimeLabel(duration))
        } finally { file.delete() }
    }
    @Test fun remoteAndInvalidSourcesDoNotStartADurationDownload() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        assertNull(localAudioDurationMs(context,"https://invalid.example/no-network-request.webm"))
        assertNull(localAudioDurationMs(context,"file:///does-not-exist.webm"))
        assertNull(localAudioDurationMs(context,null))
    }
}
