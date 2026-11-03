package conectate.com.presentation.voicerooms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomChatPhotoTest {
    @Test fun preparingAPhotoBoundsDimensionsAndUsesJpeg() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("owned-room-photo", ".png", context.cacheDir)
        val original = Bitmap.createBitmap(1080, 810, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG,100,it) }
            val encoded = RoomChatPhoto.prepare(context, Uri.fromFile(file))
            assertTrue(encoded.length <= 132000)
            val bytes = RoomChatPhoto.bytes(encoded)!!
            val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,dimensions)
            assertEquals(720,dimensions.outWidth); assertEquals(540,dimensions.outHeight)
            assertEquals("image/jpeg",dimensions.outMimeType)
        } finally { original.recycle(); file.delete() }
    }
    @Test fun remoteUrlsAndUnsupportedPayloadsNeverBecomeRoomImages() {
        for (data in listOf("https://example.org/photo.jpg","data:image/svg+xml,<svg/>",RoomChatPhoto.PREFIX+"a".repeat(132001))) assertNull(RoomChatPhoto.bytes(data))
    }
    @Test fun duplicateEchoAndPhotoRetentionStayBounded() {
        val rows = (0..109).map { ChatMessage(it.toString(),"member","photo",it.toLong(),imageData="owned fixture") }
        val bounded = boundedRoomChat(rows + rows.last(),now=0)
        assertEquals(100,bounded.size); assertEquals(10,bounded.count { it.imageData != null })
    }
    @Test fun expiredContentDisappearsAndAnAcknowledgementReplacesItsPendingCopy() {
        val pending=ChatMessage("id","member","text",1000,expiresAt=2000,delivery=RoomChatDelivery.SENDING)
        val accepted=pending.copy(delivery=RoomChatDelivery.SENT)
        assertEquals(listOf(accepted),boundedRoomChat(listOf(pending,accepted),now=1500))
        assertTrue(boundedRoomChat(listOf(accepted),now=2000).isEmpty())
        assertEquals(60L*60*1000,ROOM_CHAT_TTL_MS)
        val legacy = pending.copy(expiresAt = pending.timestamp + 90L * 60 * 1000, imageData = "old photo")
        assertTrue(boundedRoomChat(listOf(legacy), now = legacy.timestamp + ROOM_CHAT_TTL_MS).isEmpty())
    }
}
