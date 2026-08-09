package conectate.com.presentation.wallet

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.security.MessageDigest

data class PlayCoinProduct(
    val productId: String,
    val formattedPrice: String,
    val details: ProductDetails
)

class GooglePlayBillingController(
    context: Context,
    accountId: String,
    private val productIds: Set<String>,
    private val onProducts: (List<PlayCoinProduct>) -> Unit,
    private val onCatalogIssues: (List<PlayCatalogIssue>) -> Unit,
    private val onPurchased: (productId: String, purchaseToken: String) -> Unit,
    private val onPending: () -> Unit,
    private val onCanceled: () -> Unit,
    private val onError: (String) -> Unit
) : PurchasesUpdatedListener {
    private val obfuscatedAccountId = MessageDigest.getInstance("SHA-256")
        .digest(accountId.trim().lowercase().toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val detailsById = mutableMapOf<String, ProductDetails>()
    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (billingClient.isReady) {
            queryProducts()
            queryOwnedPurchases()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts()
                    queryOwnedPurchases()
                } else {
                    onError(result.debugMessage.ifBlank { "Google Play no está disponible." })
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun launch(activity: Activity, productId: String) {
        val details = detailsById[productId] ?: run {
            onError("El producto todavía no está disponible en Google Play.")
            return
        }
        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
            ?: details.oneTimePurchaseOfferDetails
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply {
                val token = offer?.offerToken.orEmpty()
                if (token.isNotBlank()) setOfferToken(token)
            }
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(obfuscatedAccountId)
            .build()
        val result = billingClient.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            onError(result.debugMessage.ifBlank { "No se pudo abrir Google Play." })
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::processPurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> onCanceled()
            else -> onError(result.debugMessage.ifBlank { "Google Play no completó la compra." })
        }
    }

    fun close() {
        billingClient.endConnection()
    }

    private fun queryProducts() {
        val products = productIds.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        if (products.isEmpty()) {
            onProducts(emptyList())
            return
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                detailsById.clear()
                onProducts(emptyList())
                onError(result.debugMessage.ifBlank { "No se pudieron consultar los productos." })
                return@queryProductDetailsAsync
            }
            detailsById.clear()
            val displayProducts = queryResult.productDetailsList.mapNotNull { details ->
                if (details.productId !in productIds) return@mapNotNull null
                val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
                    ?: details.oneTimePurchaseOfferDetails
                    ?: return@mapNotNull null
                if (offer.formattedPrice.isBlank()) return@mapNotNull null
                detailsById[details.productId] = details
                PlayCoinProduct(details.productId, offer.formattedPrice, details)
            }
            val resolution = resolvePlayCatalog(
                expectedProductIds = productIds,
                fetchedProducts = queryResult.productDetailsList.map { details ->
                    val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
                        ?: details.oneTimePurchaseOfferDetails
                    PlayCatalogProductSnapshot(details.productId, offer?.formattedPrice)
                },
                unfetchedProducts = queryResult.unfetchedProductList.map { product ->
                    PlayUnfetchedProductSnapshot(product.productId, product.statusCode)
                }
            )
            onProducts(displayProducts)
            onCatalogIssues(resolution.issues)
        }
    }

    private fun queryOwnedPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach(::processPurchase)
            }
        }
    }

    private fun processPurchase(purchase: Purchase) {
        val productId = purchase.products.firstOrNull { it in productIds }
        if (productId == null) {
            onError("Google Play devolvió un producto que no pertenece al catálogo disponible.")
            return
        }
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PENDING -> onPending()
            Purchase.PurchaseState.PURCHASED -> onPurchased(productId, purchase.purchaseToken)
            else -> onError("La compra no fue completada por Google Play.")
        }
    }
}
