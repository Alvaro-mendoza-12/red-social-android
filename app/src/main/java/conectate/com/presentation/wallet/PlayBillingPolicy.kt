package conectate.com.presentation.wallet

enum class PlayCatalogIssueReason {
    MISSING_FROM_RESPONSE,
    INVALID_PRODUCT_ID,
    PRODUCT_INACTIVE_OR_NOT_FOUND,
    NO_ELIGIBLE_OFFER,
    PRICE_UNAVAILABLE,
    UNKNOWN
}

internal data class PlayCatalogProductSnapshot(
    val productId: String,
    val formattedPrice: String?
)

internal data class PlayUnfetchedProductSnapshot(
    val productId: String,
    val statusCode: Int
)

data class PlayCatalogIssue(
    val productId: String,
    val reason: PlayCatalogIssueReason
)

internal data class PlayCatalogResolution(
    val availableProductIds: List<String>,
    val issues: List<PlayCatalogIssue>
)

/**
 * Reconciles the authoritative list received from the backend with Google Play's response.
 * Status values are the public UnfetchedProduct status codes from Billing Library 8+.
 */
internal fun resolvePlayCatalog(
    expectedProductIds: Collection<String>,
    fetchedProducts: Collection<PlayCatalogProductSnapshot>,
    unfetchedProducts: Collection<PlayUnfetchedProductSnapshot>
): PlayCatalogResolution {
    val expected = expectedProductIds.map(String::trim).filter(String::isNotBlank).distinct()
    val fetched = fetchedProducts.associateBy { it.productId }
    val unfetched = unfetchedProducts.associateBy { it.productId }
    val available = mutableListOf<String>()
    val issues = mutableListOf<PlayCatalogIssue>()

    expected.forEach { productId ->
        val product = fetched[productId]
        if (product != null && !product.formattedPrice.isNullOrBlank()) {
            available += productId
            return@forEach
        }
        if (product != null) {
            issues += PlayCatalogIssue(productId, PlayCatalogIssueReason.PRICE_UNAVAILABLE)
            return@forEach
        }
        val reason = when (unfetched[productId]?.statusCode) {
            2 -> PlayCatalogIssueReason.INVALID_PRODUCT_ID
            3 -> PlayCatalogIssueReason.PRODUCT_INACTIVE_OR_NOT_FOUND
            4 -> PlayCatalogIssueReason.NO_ELIGIBLE_OFFER
            0 -> PlayCatalogIssueReason.UNKNOWN
            null -> PlayCatalogIssueReason.MISSING_FROM_RESPONSE
            else -> PlayCatalogIssueReason.UNKNOWN
        }
        issues += PlayCatalogIssue(productId, reason)
    }
    return PlayCatalogResolution(available, issues)
}

internal fun PlayCatalogIssue.userFacingDescription(): String = when (reason) {
    PlayCatalogIssueReason.INVALID_PRODUCT_ID -> "ID inválido en Google Play"
    PlayCatalogIssueReason.PRODUCT_INACTIVE_OR_NOT_FOUND -> "Producto no publicado o inactivo"
    PlayCatalogIssueReason.NO_ELIGIBLE_OFFER -> "Sin oferta disponible para esta cuenta o región"
    PlayCatalogIssueReason.PRICE_UNAVAILABLE -> "Precio localizado no disponible"
    PlayCatalogIssueReason.MISSING_FROM_RESPONSE -> "Google Play no devolvió el producto"
    PlayCatalogIssueReason.UNKNOWN -> "No disponible temporalmente"
}
