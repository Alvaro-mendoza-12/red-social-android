package conectate.com.data.repository

import conectate.com.data.model.StickerItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickerIdentityTest {
    @Test
    fun `recents contain only favorites ordered by actual usage`() {
        assertEquals(listOf("frequent", "saved"), StickerIdentity.frequentFavoriteIds(
            listOf("unknown", "saved", "frequent", "saved"), setOf("saved", "frequent"),
            mapOf("unknown" to 100, "frequent" to 9, "saved" to 2), 48))
    }

    @Test
    fun `removing favorite also removes it from recents`() {
        assertEquals(listOf("saved"), StickerIdentity.frequentFavoriteIds(
            listOf("removed", "saved"), setOf("saved"), mapOf("removed" to 9), 48))
    }

    private fun duplicate(id: String) = StickerItem(id = id, title = "Sticker", media_url = "https://media.example/$id.webp", content_sha256 = "same")

    @Test
    fun `duplicates in favorites and recents have one canonical entry`() {
        val first = duplicate("first")
        val second = duplicate("second")
        val state = StickerIdentity.canonicalState(listOf(first, second), setOf(first.id, second.id),
            listOf(second.id, first.id), emptyMap())
        assertEquals(setOf(first.id), state.favorites)
        assertEquals(listOf(first.id), state.recents)
        assertEquals(first.id, state.mediaAliases[StickerIdentity.mediaKey(second.media_url)])
    }

    @Test
    fun `removal wins when duplicate identity arrives after the tap`() {
        val first = duplicate("first")
        val second = duplicate("second")
        val state = StickerIdentity.canonicalState(listOf(first, second), setOf(second.id),
            listOf(first.id, second.id), mapOf(first.id to -3L, second.id to 2L))
        assertTrue(state.favorites.isEmpty())
        assertEquals(1, state.recents.size)
    }

    @Test
    fun `adding again after a removal keeps one favorite`() {
        val first = duplicate("first")
        val second = duplicate("second")
        val state = StickerIdentity.canonicalState(listOf(first, second), setOf(second.id),
            listOf(first.id, second.id), mapOf(first.id to -3L, second.id to 4L))
        assertEquals(setOf(second.id), state.favorites)
    }

    @Test
    fun `animations with different bytes are not merged by a static preview`() {
        val first = StickerItem(id = "first", title = "Sticker", is_animated = true,
            content_sha256 = "animation-a", visual_fingerprint = "a5:102:90:80:1000")
        val second = first.copy(id = "second", content_sha256 = "animation-b")
        assertFalse(StickerIdentity.matches(first, second))
    }

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

    @Test
    fun `removing displayed duplicate also removes saved favorite id`() {
        val displayed = StickerItem(id = "recent-copy", title = "Sticker", content_sha256 = "same")
        val saved = StickerItem(id = "saved-original", title = "Sticker", content_sha256 = "same")
        val unrelated = StickerItem(id = "other", title = "Sticker", content_sha256 = "other")

        assertEquals(
            listOf("saved-original"),
            StickerIdentity.favoriteIdsToRemove(
                stickerId = displayed.id,
                catalog = listOf(displayed, saved, unrelated),
                favorites = setOf(saved.id, unrelated.id)
            )
        )
    }
}
