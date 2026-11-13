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

    @Test
    fun `uses the animation bit even when the file name and mime are missing`() {
        val header = webpPayload("VP8X").apply { this[16] = 10; this[20] = 2 }
        assertEquals("image/webp", StickerFormatSupport.animatedMime(header.inputStream()))
        header[20] = 0
        assertFalse(StickerFormatSupport.isAnimated(header + "ANIM".toByteArray(), "image/webp"))
    }

    @Test
    fun `recognizes animation control before PNG image data`() {
        val png = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        val control = byteArrayOf(0, 0, 0, 8) + "acTL".toByteArray() + byteArrayOf(0, 0, 0, 2) + ByteArray(8)
        assertEquals("image/png", StickerFormatSupport.animatedMime((png + control).inputStream()))
        val pixels = byteArrayOf(0, 0, 0, 12) + "IDAT".toByteArray()
        assertFalse(StickerFormatSupport.isAnimated(png + pixels + control, "image/png"))
        assertFalse(StickerFormatSupport.isAnimated(png + control.take(8).toByteArray(), "image/png"))
    }
}
