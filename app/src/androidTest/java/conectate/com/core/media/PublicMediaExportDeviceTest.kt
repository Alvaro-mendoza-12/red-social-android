package conectate.com.core.media

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicMediaExportDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun savesAnActualPhotoWithoutADestinationPicker() = runBlocking<Unit> {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        assertEquals("conectate.com.qa.cierre11", context.packageName)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.CYAN) }
        val filename = "Novvin_QA_${UUID.randomUUID()}.png"
        val uri = try {
            savePublicMedia(context, filename, "image/png", {}) { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { bitmap.recycle() }
        try {
            assertEquals("content", uri.scheme)
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(filename, it.getString(0)); assertEquals(0, it.getInt(1))
            }
            context.contentResolver.openInputStream(uri).use {
                val decoded = checkNotNull(BitmapFactory.decodeStream(it))
                assertEquals(8, decoded.width); assertEquals(8, decoded.height); decoded.recycle()
            }
        } finally { context.contentResolver.delete(uri, null, null) }
    }

    @Test fun interruptedExportDoesNotPublishAPartialPhoto() = runBlocking<Unit> {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        assertEquals("conectate.com.qa.cierre11", context.packageName)
        val filename = "Novvin_QA_${UUID.randomUUID()}.png"
        val failure = runCatching {
            savePublicMedia(context, filename, "image/png", {}) { output ->
                output.write(byteArrayOf(1, 2)); error("Disposable fixture interrupted")
            }
        }
        assertTrue(failure.isFailure)
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(filename), null)!!.use {
            val remaining = it.count
            // Remove only this unique fixture if the assertion exposes a failure.
            while (it.moveToNext()) context.contentResolver.delete(ContentUris.withAppendedId(collection, it.getLong(0)), null, null)
            assertEquals(0, remaining)
        }
    }
}
