package conectate.com.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GiftCatalogTest {
    @Test
    fun legacyNamesResolveToDistinctOriginalArtwork() {
        assertEquals("pulse", gift("a", "Corazón", null).visualKey())
        assertEquals("aurora", gift("b", "Rosa", null).visualKey())
        assertEquals("prism", gift("c", "Trofeo", null).visualKey())
        assertEquals("fireworks", gift("d", "Fuegos Artificiales", null).visualKey())
        assertEquals("nova", gift("e", "Superestrella", null).visualKey())
    }

    @Test
    fun assetKeyWinsOverBackendUuid() {
        val value = gift(
            id = "7a100016-0000-4000-8000-000000000016",
            name = "Nombre remoto",
            imageUrl = "conectate://gift-assets/phoenix"
        )
        assertEquals("phoenix", value.visualKey())
    }

    @Test
    fun duplicatedLegacyRowsCollapseToOneVisualFamily() {
        val catalog = listOf(
            gift("legacy-1", "Corazon", null, 10),
            gift("legacy-2", "Corazón", null, 10),
            gift("builtin-heart", "Pulso Novvin", "conectate://gift-assets/pulse", 5)
        ).withoutVisualDuplicates()

        assertEquals(1, catalog.size)
        assertEquals("builtin-heart", catalog.single().id)
        assertTrue(NOVVIN_GIFT_VISUAL_KEYS.size >= 16)
    }

    private fun gift(
        id: String,
        name: String,
        imageUrl: String?,
        price: Int = 10
    ) = VirtualGift(
        id = id,
        name = name,
        imageUrl = imageUrl,
        priceDiamonds = price,
        sortOrder = 1
    )
}
