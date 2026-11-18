package conectate.com.presentation.wallet

import android.app.Activity
import android.content.Context
import android.util.Log
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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

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
        .digest(accountId.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private val detailsById = ConcurrentHashMap<String, ProductDetails>()
    private val closed = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private val generation = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var deadline: Job? = null
    private val billingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (closed.get() || !starting.compareAndSet(false, true)) return
        val request = generation.incrementAndGet()
        armDeadline(request)
        if (billingClient.isReady) {
            queryProducts(request)
            queryOwnedPurchases(request)
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (!isLive(request)) return
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProducts(request)
                    queryOwnedPurchases(request)
                } else {
                    deadline?.cancel()
                    starting.set(false)
                    reportFailure(result, "connection", "No se pudo conectar con Google Play. Inténtalo nuevamente.")
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun launch(activity: Activity, productId: String) {
        if (closed.get()) return
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
            reportFailure(result, "launch", "No se pudo abrir la compra en Google Play. Inténtalo nuevamente.")
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (closed.get()) return
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases.orEmpty().forEach(::processPurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> onCanceled()
            else -> reportFailure(result, "purchase", "No se pudo completar la compra. Revisa su estado en Google Play antes de reintentar.")
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        deadline?.cancel()
        scope.cancel()
        detailsById.clear()
        billingClient.endConnection()
    }

    private fun isLive(request: Int): Boolean = !closed.get() && generation.get() == request

    private fun armDeadline(request: Int) {
        deadline?.cancel()
        deadline = scope.launch {
            delay(15_000)
            if (!isLive(request)) return@launch
            generation.incrementAndGet()
            starting.set(false)
            detailsById.clear()
            onProducts(emptyList())
            if (!closed.get()) onError("Google Play tardó demasiado en responder. Inténtalo nuevamente.")
        }
    }

    private fun reportFailure(result: BillingResult, operation: String, message: String) {
        if (closed.get()) return
        // Codes and operation identify failures without retaining tokens or SDK payloads.
        Log.w("GooglePlayBilling", "$operation failed (code=${result.responseCode})")
        onError(message)
    }

    private fun queryProducts(request: Int) {
        if (!isLive(request)) return
        armDeadline(request)
        val products = productIds.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        if (products.isEmpty()) {
            deadline?.cancel()
            starting.set(false)
            onProducts(emptyList())
            return
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
            if (!isLive(request)) return@queryProductDetailsAsync
            deadline?.cancel()
            starting.set(false)
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                detailsById.clear()
                onProducts(emptyList())
                reportFailure(result, "catalog", "No se pudieron consultar los precios de Google Play. Inténtalo nuevamente.")
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
            if (!isLive(request)) { detailsById.clear(); return@queryProductDetailsAsync }
            onProducts(displayProducts)
            if (isLive(request)) onCatalogIssues(resolution.issues)
        }
    }

    private fun queryOwnedPurchases(request: Int) {
        if (!isLive(request)) return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (!isLive(request)) return@queryPurchasesAsync
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach(::processPurchase)
            } else {
                reportFailure(result, "owned", "No se pudieron comprobar tus compras. Inténtalo nuevamente.")
            }
        }
    }

    private fun processPurchase(purchase: Purchase) {
        if (closed.get()) return
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
