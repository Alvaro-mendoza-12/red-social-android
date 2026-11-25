package conectate.com.data.service

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IncomingCallAvatarTest {
    @Test fun aSmallPayloadWithLargeDimensionsIsDecodedAtNotificationScale() {
        val context = RuntimeEnvironment.getApplication()
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.BLUE)
            val bytes = checkNotNull(source.encodeIncomingCallAvatar())
            assertTrue(bytes.size in 1..INCOMING_CALL_AVATAR_MAX_BYTES)
            val decoded = checkNotNull(decodeIncomingCallAvatar(context, bytes))
            try {
                assertEquals(512, decoded.width)
                assertEquals(256, decoded.height)
            } finally {
                decoded.recycle()
            }
        } finally {
            source.recycle()
        }
    }

    @Test fun encodingDoesNotReturnTheLastAttemptWhenItExceedsTheLimit() {
        val random = Random(42)
        val pixels = IntArray(1024 * 1024) { random.nextInt() or 0xff000000.toInt() }
        val source = Bitmap.createBitmap(pixels, 1024, 1024, Bitmap.Config.ARGB_8888)
        try {
            assertNull(source.encodeIncomingCallAvatar())
        } finally {
            source.recycle()
        }
    }

    @Test fun invalidOrOversizedExtrasAreIgnored() {
        val context = RuntimeEnvironment.getApplication()
        assertNull(decodeIncomingCallAvatar(context, byteArrayOf()))
        assertNull(decodeIncomingCallAvatar(context, "not a photo".encodeToByteArray()))
        val oversized = ByteArray(INCOMING_CALL_AVATAR_MAX_BYTES + 1)
        oversized[0] = 0xff.toByte()
        oversized[1] = 0xd8.toByte()
        oversized[2] = 0xff.toByte()
        assertNull(decodeIncomingCallAvatar(context, oversized))
    }
}
