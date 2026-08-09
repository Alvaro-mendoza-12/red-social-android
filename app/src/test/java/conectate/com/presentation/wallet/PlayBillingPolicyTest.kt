package conectate.com.presentation.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayBillingPolicyTest {
    private val ids = listOf(
        "novvin_coins_70", "novvin_coins_140", "novvin_coins_350", "novvin_coins_700",
        "novvin_coins_1400", "novvin_coins_3500", "novvin_coins_7000", "novvin_coins_14000"
    )

    @Test
    fun `all eight localized products are available`() {
        val result = resolvePlayCatalog(
            ids,
            ids.map { PlayCatalogProductSnapshot(it, "S/ 3.90") },
            emptyList()
        )
        assertEquals(ids, result.availableProductIds)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `missing catalog item is explicit`() {
        val result = resolvePlayCatalog(
            ids,
            ids.dropLast(1).map { PlayCatalogProductSnapshot(it, "S/ 3.90") },
            emptyList()
        )
        assertEquals("novvin_coins_14000", result.issues.single().productId)
        assertEquals(PlayCatalogIssueReason.MISSING_FROM_RESPONSE, result.issues.single().reason)
    }

    @Test
    fun `inactive product returned by Play is explicit`() {
        val result = resolvePlayCatalog(
            ids,
            ids.drop(1).map { PlayCatalogProductSnapshot(it, "S/ 3.90") },
            listOf(PlayUnfetchedProductSnapshot("novvin_coins_70", 3))
        )
        assertEquals(PlayCatalogIssueReason.PRODUCT_INACTIVE_OR_NOT_FOUND, result.issues.single().reason)
    }

    @Test
    fun `missing localized price disables only that package`() {
        val result = resolvePlayCatalog(
            ids,
            ids.map { PlayCatalogProductSnapshot(it, if (it == "novvin_coins_350") null else "US$ 1.99") },
            emptyList()
        )
        assertEquals(PlayCatalogIssueReason.PRICE_UNAVAILABLE, result.issues.single().reason)
        assertEquals(7, result.availableProductIds.size)
    }

    @Test
    fun `no eligible regional offer is explicit`() {
        val result = resolvePlayCatalog(
            ids,
            ids.dropLast(1).map { PlayCatalogProductSnapshot(it, "€1.99") },
            listOf(PlayUnfetchedProductSnapshot("novvin_coins_14000", 4))
        )
        assertEquals(PlayCatalogIssueReason.NO_ELIGIBLE_OFFER, result.issues.single().reason)
    }
}
