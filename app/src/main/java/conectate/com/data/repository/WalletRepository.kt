package conectate.com.data.repository

import android.util.Log
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.WalletBalance
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.BillingCapabilities
import conectate.com.data.model.CoinProduct
import conectate.com.data.model.CoinPurchaseVerification
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WalletRepository @Inject constructor(
    private val supabaseConfig: SupabaseConfig,
    private val authService: AuthService
) {
    private companion object {
        private const val TAG = "WalletRepository"
        private val LOCAL_GIFT_CATALOG = listOf(
            VirtualGift(
                id = "builtin-heart",
                name = "Pulso Novvin",
                description = "Un pulso luminoso para apoyar al creador",
                imageUrl = "conectate://gift-assets/heart",
                priceDiamonds = 5,
                sortOrder = 1
            ),
            VirtualGift(
                id = "builtin-rose",
                name = "Flor Aurora",
                description = "Pétalos de luz originales de Novvin",
                imageUrl = "conectate://gift-assets/rose",
                priceDiamonds = 25,
                priceCredits = 4.99,
                sortOrder = 2
            ),
            VirtualGift(
                id = "builtin-trophy",
                name = "Copa Prisma",
                description = "Una copa brillante para celebrar logros",
                imageUrl = "conectate://gift-assets/trophy",
                priceDiamonds = 50,
                priceCredits = 9.99,
                sortOrder = 3
            ),
            VirtualGift(
                id = "builtin-fireworks",
                name = "Lluvia de Luz",
                description = "Una celebración que llena la pantalla",
                imageUrl = "conectate://gift-assets/fireworks",
                priceDiamonds = 100,
                priceCredits = 19.99,
                sortOrder = 4
            ),
            VirtualGift(
                id = "builtin-star",
                name = "Nova",
                description = "Una explosión estelar para grandes momentos",
                imageUrl = "conectate://gift-assets/star",
                priceDiamonds = 200,
                priceCredits = 39.99,
                sortOrder = 5
            ),
            VirtualGift(
                id = "builtin-orbit",
                name = "Órbita",
                description = "Un planeta de energía Novvin",
                imageUrl = "conectate://gift-assets/orbit",
                priceDiamonds = 35,
                sortOrder = 6
            ),
            VirtualGift(
                id = "builtin-comet",
                name = "Cometa",
                description = "Una estela veloz para destacar el directo",
                imageUrl = "conectate://gift-assets/comet",
                priceDiamonds = 350,
                sortOrder = 7
            ),
            VirtualGift(
                id = "builtin-crown",
                name = "Corona Cósmica",
                description = "La celebración más destacada del catálogo",
                imageUrl = "conectate://gift-assets/crown",
                priceDiamonds = 500,
                sortOrder = 8
            )
        )
    }

    private val _coinBalance = MutableStateFlow(0)
    val coinBalance: StateFlow<Int> = _coinBalance.asStateFlow()

    private val _availableGifts = MutableStateFlow<List<VirtualGift>>(emptyList())
    val availableGifts: StateFlow<List<VirtualGift>> = _availableGifts.asStateFlow()

    suspend fun fetchBalance() = withContext(Dispatchers.IO) {
        runCatching {
            if (!supabaseConfig.isConfigured) {
                _coinBalance.value = 0
                return@runCatching
            }

            val userId = authService.getCurrentUserId()?.trim().orEmpty()
            if (userId.isBlank()) {
                _coinBalance.value = 0
                return@runCatching
            }

            val token = authService.getAccessToken()
                ?: throw IllegalStateException("No hay token de acceso")
            val balance = fetchWalletViaRpc(token)
                ?: fetchWalletByRest(token, userId)
                ?: WalletBalance(userId = userId)

            _coinBalance.value = balance.balanceDiamonds.coerceAtLeast(0)
        }.onFailure { error ->
            Log.e(TAG, "Unable to fetch wallet balance", error)
            _coinBalance.value = 0
        }
    }

    suspend fun fetchAvailableGifts() = withContext(Dispatchers.IO) {
        runCatching {
            if (!supabaseConfig.isConfigured) {
                _availableGifts.value = LOCAL_GIFT_CATALOG
                return@runCatching
            }

            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/virtual_gifts") {
                restHeaders(authService.getAccessToken())
                parameter("is_active", "eq.true")
                parameter("select", "id,name,description,image_url,price_diamonds,price_credits,animation_url,sort_order")
                parameter("order", "sort_order.asc,name.asc")
            }

            if (!response.status.isSuccess()) {
                throw IllegalStateException("No se pudieron cargar regalos: ${response.status}")
            }

            _availableGifts.value = decodeObjects(response.bodyAsText())
                .map { it.toVirtualGift() }
                .filter { it.id.isNotBlank() && it.priceDiamonds > 0 }
                .sortedWith(compareBy<VirtualGift> { it.sortOrder }.thenBy { it.name })
        }.onFailure { error ->
            Log.e(TAG, "Unable to fetch virtual gifts", error)
            if (!supabaseConfig.isConfigured) {
                _availableGifts.value = LOCAL_GIFT_CATALOG
            } else {
                _availableGifts.value = emptyList()
            }
        }
    }

    suspend fun sendGift(
        receiverId: String,
        giftId: String,
        liveStreamId: String? = null,
        isPublic: Boolean = true,
        idempotencyKey: String
    ): Result<GiftReceipt> = withContext(Dispatchers.IO) {
        runCatching {
            if (!supabaseConfig.isConfigured) {
                throw IllegalStateException("La tienda de regalos no está configurada para este entorno")
            }

            val cleanReceiverId = receiverId.trim()
            val cleanGiftId = giftId.trim()
            val cleanLiveStreamId = liveStreamId?.trim().orEmpty()
            val senderId = authService.getCurrentUserId()?.trim()
                ?: throw IllegalStateException("No hay una sesion activa")
            val token = authService.getAccessToken()
                ?: throw IllegalStateException("No hay token de acceso")

            if (cleanReceiverId.isBlank()) {
                throw IllegalArgumentException("No se encontro el receptor del regalo")
            }
            if (cleanGiftId.isBlank()) {
                throw IllegalArgumentException("Selecciona un regalo valido")
            }

            val gift = _availableGifts.value.firstOrNull { it.id == cleanGiftId }
                ?: fetchGiftById(token, cleanGiftId)
            val canonicalCost = gift?.priceDiamonds ?: 0
            if (canonicalCost <= 0) {
                throw IllegalArgumentException("El regalo seleccionado no tiene precio valido")
            }
            if (_coinBalance.value < canonicalCost) {
                throw IllegalStateException("Saldo insuficiente")
            }

            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/send_virtual_gift") {
                restHeaders(token)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Prefer, "return=representation")
                setBody(buildJsonObject {
                    put("p_sender_id", senderId)
                    put("p_receiver_id", cleanReceiverId)
                    put("p_gift_id", cleanGiftId)
                    put("p_idempotency_key", idempotencyKey)
                    put("p_is_public", isPublic)
                    put("p_message", JsonNull)
                    if (cleanLiveStreamId.isBlank()) {
                        put("p_live_stream_id", JsonNull)
                    } else {
                        put("p_live_stream_id", cleanLiveStreamId)
                    }
                })
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw IllegalStateException(
                    parseSupabaseError(body) ?: "No se pudo enviar el regalo (${response.status.value})"
                )
            }

            val receipt = decodeFirstObject(body)
                ?: throw IllegalStateException("El servidor no devolvió el comprobante del regalo")
            val newBalance = receipt.int("sender_balance_diamonds")
            _coinBalance.value = newBalance.coerceAtLeast(0)
            GiftReceipt(
                transactionId = receipt.string("transaction_id")
                    ?: throw IllegalStateException("El comprobante del regalo no es válido"),
                liveStreamId = receipt.string("live_stream_id") ?: cleanLiveStreamId.takeIf { it.isNotBlank() },
                senderId = senderId,
                senderName = receipt.string("sender_name").orEmpty().ifBlank { "Usuario" },
                receiverId = cleanReceiverId,
                gift = gift ?: VirtualGift(
                    id = cleanGiftId,
                    name = receipt.string("gift_name").orEmpty().ifBlank { "Regalo Novvin" },
                    imageUrl = receipt.string("gift_image_url"),
                    animationUrl = receipt.string("gift_animation_url"),
                    priceDiamonds = canonicalCost
                ),
                senderBalanceDiamonds = newBalance,
                isPublic = receipt.string("is_public")?.toBooleanStrictOrNull() ?: isPublic,
                idempotentReplay = receipt.string("idempotent_replay")?.toBooleanStrictOrNull() ?: false
            )
        }
    }

    suspend fun fetchBillingCapabilities(): Result<BillingCapabilities> = withContext(Dispatchers.IO) {
        runCatching {
            if (!supabaseConfig.isConfigured) return@runCatching BillingCapabilities()
            val token = authService.getAccessToken()
                ?: throw IllegalStateException("No hay token de acceso")
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/billing_capabilities") {
                restHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { })
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw IllegalStateException(parseSupabaseError(body) ?: "No se pudo consultar Google Play")
            }
            val objectValue = decodeFirstObject(body) ?: return@runCatching BillingCapabilities()
            BillingCapabilities(
                enabled = objectValue.boolean("enabled"),
                provider = objectValue.string("provider") ?: "google_play",
                products = (objectValue["products"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val product = element as? JsonObject ?: return@mapNotNull null
                    val id = product.string("product_id").orEmpty()
                    val coins = product.int("coins")
                    if (id.isBlank() || coins <= 0) null else CoinProduct(id, coins)
                }
            )
        }
    }

    suspend fun verifyGooglePlayPurchase(
        productId: String,
        purchaseToken: String
    ): Result<CoinPurchaseVerification> = withContext(Dispatchers.IO) {
        runCatching {
            val token = authService.getAccessToken()
                ?: throw IllegalStateException("No hay token de acceso")
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/verify_google_play_purchase") {
                restHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("p_product_id", productId)
                    put("p_purchase_token", purchaseToken)
                })
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw IllegalStateException(parseSupabaseError(body) ?: "Google Play no pudo verificar la compra")
            }
            val value = decodeFirstObject(body)
                ?: throw IllegalStateException("La verificación no devolvió un resultado")
            val verified = CoinPurchaseVerification(
                status = value.string("status") ?: "unknown",
                credited = value.boolean("credited"),
                coins = value.int("coins"),
                balanceDiamonds = value.int("balance_diamonds")
            )
            if (verified.status == "credited") {
                _coinBalance.value = verified.balanceDiamonds.coerceAtLeast(0)
            }
            verified
        }
    }

    private suspend fun fetchWalletViaRpc(token: String): WalletBalance? {
        val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/wallet_get_or_create") {
            restHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { })
        }
        if (!response.status.isSuccess()) return null
        return decodeFirstObject(response.bodyAsText())?.toWalletBalance()
    }

    private suspend fun fetchWalletByRest(token: String, userId: String): WalletBalance? {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/wallets") {
            restHeaders(token)
            parameter("user_id", "eq.$userId")
            parameter("select", "user_id,balance_diamonds,balance_credits,total_spent")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return null
        return decodeObjects(response.bodyAsText()).firstOrNull()?.toWalletBalance()
    }

    private suspend fun fetchGiftById(token: String, giftId: String): VirtualGift? {
        val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/virtual_gifts") {
            restHeaders(token)
            parameter("id", "eq.$giftId")
            parameter("is_active", "eq.true")
            parameter("select", "id,name,description,image_url,price_diamonds,price_credits,animation_url,sort_order")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return null
        return decodeObjects(response.bodyAsText()).firstOrNull()?.toVirtualGift()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.restHeaders(accessToken: String?) {
        header("apikey", supabaseConfig.supabaseAnonKey)
        header("Authorization", "Bearer ${accessToken ?: supabaseConfig.supabaseAnonKey}")
    }

    private fun decodeObjects(body: String): List<JsonObject> {
        if (body.isBlank()) return emptyList()
        return when (val parsed = supabaseConfig.json.parseToJsonElement(body)) {
            is JsonArray -> parsed.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(parsed)
            else -> emptyList()
        }
    }

    private fun decodeFirstObject(body: String): JsonObject? = decodeObjects(body).firstOrNull()

    private fun JsonObject.toWalletBalance(): WalletBalance {
        return WalletBalance(
            userId = string("user_id").orEmpty(),
            balanceDiamonds = int("balance_diamonds"),
            balanceCredits = double("balance_credits"),
            totalSpent = double("total_spent")
        )
    }

    private fun JsonObject.toVirtualGift(): VirtualGift {
        return VirtualGift(
            id = string("id").orEmpty(),
            name = string("name").orEmpty(),
            description = string("description"),
            imageUrl = string("image_url"),
            priceDiamonds = int("price_diamonds"),
            priceCredits = double("price_credits"),
            animationUrl = string("animation_url"),
            sortOrder = int("sort_order")
        )
    }

    private fun JsonObject.string(key: String): String? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JsonObject.int(key: String): Int {
        return this[key]?.jsonPrimitive?.intOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt()
            ?: 0
    }

    private fun JsonObject.double(key: String): Double {
        return this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
    }

    private fun JsonObject.boolean(key: String): Boolean {
        return this[key]?.jsonPrimitive?.booleanOrNull
            ?: this[key]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
            ?: false
    }

    private fun parseSupabaseError(body: String): String? {
        return runCatching {
            val obj = supabaseConfig.json.parseToJsonElement(body) as? JsonObject
            obj?.string("message") ?: obj?.string("details") ?: obj?.string("hint")
        }.getOrNull()
    }
}
