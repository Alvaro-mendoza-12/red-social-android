package conectate.com.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerFormatSupportTest {
    @Test
    fun `detects gif from bytes even when resolver mime is generic`() {
        val bytes = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(16)

        assertEquals("image/gif", StickerFormatSupport.detectMimeType(bytes, "application/octet-stream"))
        assertTrue(StickerFormatSupport.isAnimated(bytes, "image/gif"))
    }

    @Test
    fun `static webp is not marked animated`() {
        val bytes = webpPayload("VP8 ")

        assertEquals("image/webp", StickerFormatSupport.detectMimeType(bytes, "image/*"))
        assertFalse(StickerFormatSupport.isAnimated(bytes, "image/webp"))
    }

    @Test
    fun `animated webp is marked animated`() {
        val bytes = webpPayload("ANIM") + "ANMF".toByteArray(Charsets.US_ASCII)

        assertEquals("image/webp", StickerFormatSupport.detectMimeType(bytes, "image/png"))
        assertTrue(StickerFormatSupport.isAnimated(bytes, "image/webp"))
    }

    private fun webpPayload(chunk: String): ByteArray {
        return "RIFF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(16, 0, 0, 0) +
            "WEBP".toByteArray(Charsets.US_ASCII) +
            chunk.toByteArray(Charsets.US_ASCII) +
            ByteArray(8)
    }
}
