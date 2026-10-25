package conectate.com.data.repository

import conectate.com.data.model.StickerItem
import org.junit.Assert.*
import org.junit.Test

class StickerCatalogPolicyTest {
    @Test fun legacyEmojiLabelsDoNotReturnAsStickerTiles() {
        listOf("emoji_happy", "emoji_love", "emoji_fire", "emoji_party", "emoji_robot", "emoji_star").forEach { id ->
            assertTrue(StickerCatalogPolicy.isLegacyEmojiPlaceholder(StickerItem(id = id, title = "Etiqueta", source = "local")))
        }
    }

    @Test fun actualArtworkAndUserStickersSurviveTheLegacyCleanup() {
        val legacyIdWithArtwork = StickerItem(id = "emoji_happy", title = "Feliz", source = "local", media_url = "https://example.invalid/sticker.webp")
        assertFalse(StickerCatalogPolicy.isLegacyEmojiPlaceholder(legacyIdWithArtwork))
        assertFalse(StickerCatalogPolicy.isLegacyEmojiPlaceholder(legacyIdWithArtwork.copy(media_url = null, source = "message")))
        assertFalse(StickerCatalogPolicy.isLegacyEmojiPlaceholder(StickerItem(id = "custom", title = "Mi sticker", source = "local")))
    }
}
