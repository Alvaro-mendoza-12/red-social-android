package conectate.com.data.repository

import conectate.com.data.model.StickerItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerIdentityTest {
    @Test
    fun `identical bytes under different URLs are one sticker`() {
        val first = StickerItem(id = "first", title = "Sticker", content_sha256 = "abc")
        val second = StickerItem(id = "second", title = "Sticker", content_sha256 = "abc")
        assertTrue(StickerIdentity.matches(first, second))
    }

    @Test
    fun `same visual image after recompression is one sticker`() {
        val first = StickerItem(id = "first", title = "Sticker", visual_fingerprint = "a5:102:90:80:1000")
        val second = StickerItem(id = "second", title = "Sticker", visual_fingerprint = "a5:104:92:81:1005")
        assertTrue(StickerIdentity.matches(first, second))
    }

    @Test
    fun `different images remain separate`() {
        val first = StickerItem(id = "first", title = "Sticker", visual_fingerprint = "a5:102:90:80:1000")
        val second = StickerItem(id = "second", title = "Sticker", visual_fingerprint = "5a:104:92:81:1005")
        assertFalse(StickerIdentity.matches(first, second))
    }
}
