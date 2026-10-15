package conectate.com.core.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class NotificationAvatarDecoderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun photoAvatarIsDecodedAndBounded() {
        val original = Bitmap.createBitmap(1536,768,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().use { output -> original.compress(Bitmap.CompressFormat.PNG,100,output);output.toByteArray() }
        original.recycle()
        val bitmap = NotificationAvatarDecoder.decode(context,bytes)
        assertNotNull(bitmap)
        try { assertTrue(maxOf(bitmap!!.width,bitmap.height) <= 512);assertTrue(Color.blue(bitmap.getPixel(0,0)) > 200) }
        finally { bitmap?.recycle() }
    }

    @Test fun twoSecondVideoAvatarHasVisiblePosterAndNoRetainedVideoFile() {
        val directory = File(context.noBackupFilesDir,"notification-avatar-decoding")
        val before = directory.list()?.toSet().orEmpty()
        val bytes = instrumentation.context.assets.open("notification-avatar-two-seconds.mp4").use { it.readBytes() }
        val bitmap = NotificationAvatarDecoder.decode(context,bytes)
        assertNotNull("A video avatar must yield a real decoded frame",bitmap)
        try { assertTrue(maxOf(bitmap!!.width,bitmap.height) <= 512);assertTrue(Color.blue(bitmap.getPixel(bitmap.width/2,bitmap.height/2)) > 180) }
        finally { bitmap?.recycle() }
        assertEquals(before,directory.list()?.toSet().orEmpty())
    }

    @Test fun longVideoAndInvalidMediaAreRejectedWithoutRetainedFiles() {
        val directory = File(context.noBackupFilesDir,"notification-avatar-decoding")
        val before = directory.list()?.toSet().orEmpty()
        assertNull(NotificationAvatarDecoder.decode(context,byteArrayOf(1,2,3,4)))
        val long = instrumentation.context.assets.open("notification-avatar-four-seconds.mp4").use { it.readBytes() }
        assertNull(NotificationAvatarDecoder.decode(context,long))
        assertEquals(before,directory.list()?.toSet().orEmpty())
    }
}
