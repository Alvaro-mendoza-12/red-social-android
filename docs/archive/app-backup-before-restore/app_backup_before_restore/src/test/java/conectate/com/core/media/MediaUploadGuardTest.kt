package conectate.com.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUploadGuardTest {

    private val guard = MediaUploadGuard()

    @Test
    fun `accepts valid jpeg payload`() {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val result = guard.validate(
            bucketName = "avatars",
            fileName = "user/photo.jpg",
            bytes = bytes,
            contentType = "image/jpeg"
        )

        assertTrue(result.allowed)
    }

    @Test
    fun `rejects executable signatures`() {
        val bytes = byteArrayOf(0x4D, 0x5A, 0x11, 0x22, 0x33, 0x44)

        val result = guard.validate(
            bucketName = "chat-media",
            fileName = "chat/file.mp3",
            bytes = bytes,
            contentType = "audio/mpeg"
        )

        assertFalse(result.allowed)
    }

    @Test
    fun `rejects extension not allowed by bucket policy`() {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        val result = guard.validate(
            bucketName = "avatars",
            fileName = "user/photo.exe",
            bytes = bytes,
            contentType = "image/jpeg"
        )

        assertFalse(result.allowed)
    }
}
