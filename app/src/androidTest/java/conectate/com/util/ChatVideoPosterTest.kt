package conectate.com.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.request.SuccessResult
import coil.Coil
import conectate.com.core.media.MediaImageRequestFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChatVideoPosterTest {
    @Test fun uploadPosterUsesABoundedDecodableNativeFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("upload-poster-", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("video-cache-smoke.mp4").use { input ->
                file.outputStream().use(input::copyTo)
            }
            val bytes = requireNotNull(VideoUtils.generateVideoThumbnail(context, android.net.Uri.fromFile(file)))
            val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertTrue(bitmap.width in 1..720)
                assertTrue(bitmap.height in 1..720)
            } finally { bitmap.recycle() }
        } finally { file.delete() }
    }
    @Test fun downloadedOpaqueVideoProducesARealPosterWithoutAnExtension() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("video-offline-", ".media", context.cacheDir)
        // Use the same singleton loader the app uses, including its registered video decoder.
        val loader = Coil.imageLoader(context)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("video-cache-smoke.mp4").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val request = requireNotNull(MediaImageRequestFactory.videoPoster(context, file.toURI().toString(), null))
            val result = loader.execute(request)
            assertTrue("Cannot decode opaque video: ${(result as? coil.request.ErrorResult)?.throwable}", result is SuccessResult)
            val drawable = (result as SuccessResult).drawable
            assertTrue(drawable.intrinsicWidth > 0)
            assertTrue(drawable.intrinsicHeight > 0)
        } finally { file.delete() }
    }
}
