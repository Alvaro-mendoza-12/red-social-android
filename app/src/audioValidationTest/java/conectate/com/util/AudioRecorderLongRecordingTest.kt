package conectate.com.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AudioRecorderLongRecordingTest {

    @Test(timeout = 120_000L)
    fun sixtyFiveSecondRecording_encodesWithoutBlockingOrCrashing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(
            "RECORD_AUDIO debe estar concedido en el dispositivo de prueba",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )

        val recorder = AudioRecorder(context)
        var encodedFile: java.io.File? = null
        try {
            assertNotNull("No se pudo iniciar AudioRecord", recorder.start())
            delay(65_000L)

            val startedAt = System.currentTimeMillis()
            encodedFile = withContext(Dispatchers.IO) { recorder.stop() }
            val finalizeMs = System.currentTimeMillis() - startedAt

            assertNotNull("La codificación AAC no produjo archivo", encodedFile)
            assertTrue("El audio AAC quedó vacío", encodedFile!!.length() > 1_024L)
            assertTrue(
                "Finalizar el audio tardó demasiado: ${finalizeMs}ms",
                finalizeMs < 15_000L
            )
        } finally {
            recorder.cancel()
            encodedFile?.delete()
        }
    }
}
