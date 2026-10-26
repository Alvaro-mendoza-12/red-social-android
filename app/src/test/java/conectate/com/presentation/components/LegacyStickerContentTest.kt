package conectate.com.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyStickerContentTest {
    @Test fun historicalCatalogLabelsRenderAsTheirOriginalEmoji() {
        mapOf("Feliz" to "😀", "Amor" to "❤️", "Fuego" to "🔥", "Fiesta" to "🥳",
            "Robot" to "🤖", "Estrella" to "⭐", " emoji_star " to "⭐").forEach { (label, emoji) ->
            assertEquals(emoji, legacyStickerDisplayContent(label))
        }
    }

    @Test fun arbitraryUserContentAndEmojiSequencesArePreserved() {
        listOf("Mi sticker favorito", "👨‍👩‍👧‍👦", "👍🏽", "estrella fugaz").forEach {
            assertEquals(it, legacyStickerDisplayContent(it))
        }
        assertEquals("🧩", legacyStickerDisplayContent(" "))
    }
}
