package conectate.com.data.repository

import android.util.Log
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.WalletBalance
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.BillingCapabilities
import conectate.com.data.model.CoinProduct
import conectate.com.data.model.CoinPurchaseVerification
import conectate.com.data.model.GiftSafetyLimits
import conectate.com.data.model.NOVVIN_CANONICAL_GIFT_CATALOG
import conectate.com.data.model.WalletTransaction
import conectate.com.data.model.mergeGiftCatalog
import conectate.com.data.model.withoutVisualDuplicates
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionLease
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val authService: AuthService,
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
) {
    private companion object {
        private const val TAG = "WalletRepository"
        private val LOCAL_GIFT_CATALOG = NOVVIN_CANONICAL_GIFT_CATALOG
    }

    private val _coinBalance = MutableStateFlow(0)
    private val giftAttempts = context.getSharedPreferences("pending_gift_attempts", android.content.Context.MODE_PRIVATE)
    private val giftSendCoordinator = GiftSendCoordinator(
        read = { giftAttempts.getString(it, null) },
        write = { operation, key ->
            val previous = giftAttempts.getString(operation, null)
            val editor = giftAttempts.edit()
            if (key == null) editor.remove(operation) else editor.putString(operation, key)
            // API24/25 may skip an identical commit even after a failed disk write.
            // Change metadata in the same transaction while retaining the original UUID.
            advanceGiftWrite(editor)
            if (!editor.commit()) {
                if (key == null && previous != null) {
                    // A failed removal already changed memory. Restore the retry identity
                    // before reporting failure, even if disk remains temporarily unavailable.
                    runCatching {
                        val restore = giftAttempts.edit().putString(operation, previous)
                        advanceGiftWrite(restore)
                        check(restore.commit()) { "No se pudo restaurar la clave pendiente del regalo." }
                    }
                }
                error("No se pudo guardar la confirmación del regalo. Reintenta.")
            }
        },
    )
    private fun advanceGiftWrite(editor: android.content.SharedPreferences.Editor) {
        val marker = "__gift_attempt_write"
        val previous = giftAttempts.getString(marker, null)?.toLongOrNull() ?: 0L
        editor.putString(marker, (previous + 1L).toString())
    }

    val coinBalance: StateFlow<Int> = _coinBalance.asStateFlow()

    private val _coinDebt = MutableStateFlow(0)
    val coinDebt: StateFlow<Int> = _coinDebt.asStateFlow()

    private val _availableGifts = MutableStateFlow<List<VirtualGift>>(emptyList())
    val availableGifts: StateFlow<List<VirtualGift>> = _availableGifts.asStateFlow()

    private val _transactions = MutableStateFlow<List<WalletTransaction>>(emptyList())
    val transactions: StateFlow<List<WalletTransaction>> = _transactions.asStateFlow()

    private val _transactionsHasMore = MutableStateFlow(false)
    val transactionsHasMore: StateFlow<Boolean> = _transactionsHasMore.asStateFlow()

    private val historyLock = Any()
    private class HistoryRead(val reset: Boolean, val offset: Int)
    private var historyRead: HistoryRead? = null
    private var historyOffset = 0
    private val mutableHistoryStatus = MutableStateFlow(WalletHistoryStatus())
    internal val historyStatus = mutableHistoryStatus.asStateFlow()

    private var walletOwner: AuthSessionLease? = null
    private val balanceRead = java.util.concurrent.atomic.AtomicReference<Any?>(null)

    @Synchronized
    private fun walletSession(): AuthSessionLease {
        val session = authService.captureAccountLease()
        if (walletOwner?.sameVisit(session) != true) {
            walletOwner = session
            balanceRead.set(null)
            _coinBalance.value = 0
            _coinDebt.value = 0
            synchronized(historyLock) {
                historyRead = null
                historyOffset = 0
                _transactions.value = emptyList()
                _transactionsHasMore.value = false
                mutableHistoryStatus.value = WalletHistoryStatus(owner = session)
            }
        }
        return session
    }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            authService.currentSession.collect { walletSession() }
        }
    }

    suspend fun fetchBalance(): Result<Unit> {
        val session = walletSession()
        val read = Any()
        authService.commitForAccountLease(session) { balanceRead.set(read) }
        return try {
            accountScopedCall(session, { userId ->
                check(supabaseConfig.isConfigured) { "El monedero no está configurado para este entorno." }
                authService.ensureValidAccessToken().getOrThrow()
                session.requireCurrent()
                authService.getAccessTokenForAccountAwait(userId)
            }) { userId, token ->
                withContext(Dispatchers.IO) {
                    session.requireCurrent()
                    val balance = fetchWalletViaRpc(token) ?: run {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        session.requireCurrent()
                        fetchWalletByRest(token, userId) ?: WalletBalance(userId = userId)
                    }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    session.requireCurrent()
                    var confirmed = false
                    val current = authService.commitForAccountLease(session) {
                        if (balanceRead.compareAndSet(read, null)) {
                            _coinBalance.value = balance.balanceDiamonds
                            _coinDebt.value = balance.balanceDebtDiamonds
                            confirmed = true
                        }
                    }
                    if (!current) session.requireCurrent()
                    check(confirmed) { "Otra operación reemplazó esta consulta de saldo. Vuelve a actualizar el monedero." }
                    Result.success(Unit)
                }
            }.onFailure { error ->
                Log.e(TAG, "Unable to fetch wallet balance", error)
                // Keep the last confirmed snapshot when this request cannot confirm a new one.
            }
        } finally {
            // A cancelled/late reader must not release a newer reader's reservation.
            balanceRead.compareAndSet(read, null)
        }
    }

    private fun invalidateBalanceRead(session: AuthSessionLease) {
        val current = authService.commitForAccountLease(session) { balanceRead.set(null) }
        if (!current) session.requireCurrent()
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

            val remoteCatalog = decodeObjects(response.bodyAsText())
                .map { it.toVirtualGift() }
                .filter { it.id.isNotBlank() && it.priceDiamonds > 0 }
                .withoutVisualDuplicates()
            _availableGifts.value = mergeGiftCatalog(remoteCatalog, LOCAL_GIFT_CATALOG)
        }.onFailure { error ->
            Log.e(TAG, "Unable to fetch virtual gifts", error)
            _availableGifts.value = LOCAL_GIFT_CATALOG
        }
    }

    suspend fun fetchTransactionHistory(reset: Boolean = true): Result<Unit> {
        // Capture ownership and reserve the page before dispatching credentials or HTTP.
        val session = walletSession()
        if (session.accountId == null) return Result.failure(IllegalStateException("Inicia sesión para consultar el historial."))
        var request: HistoryRead? = null
        val reserved = authService.commitForAccountLease(session) {
            synchronized(historyLock) {
                if (reset || (historyRead == null && mutableHistoryStatus.value.loaded && _transactionsHasMore.value)) {
                    request = HistoryRead(reset, if (reset) 0 else historyOffset)
                    historyRead = request
                    mutableHistoryStatus.value = mutableHistoryStatus.value.copy(loading = true, error = null)
                }
            }
        }
        if (!reserved) session.requireCurrent()
        val read = request ?: return Result.success(Unit)
        try {
            val result = try {
                kotlinx.coroutines.withTimeout(15_000) {
                    accountScopedCall(session, { userId ->
                        check(supabaseConfig.isConfigured) { "El monedero no está configurado para este entorno." }
                        authService.ensureValidAccessToken().getOrThrow()
                        session.requireCurrent()
                        requireHistoryRead(read)
                        authService.getAccessTokenForAccountAwait(userId)
                    }) { userId, token ->
                        withContext(Dispatchers.IO) {
                            requireHistoryRead(read)
                            session.requireCurrent()
                            val response = supabaseConfig.httpClient.get("${supabaseConfig.supabaseUrl}/rest/v1/wallet_transactions") {
                                restHeaders(token)
                                parameter("user_id", "eq.$userId")
                                parameter("select", "*")
                                parameter("order", "created_at.desc")
                                parameter("limit", "51")
                                parameter("offset", read.offset.toString())
                            }
                            check(response.status.isSuccess()) { "No se pudo cargar el historial (${response.status.value})." }
                            val parsed = supabaseConfig.json.parseToJsonElement(response.bodyAsText())
                            val objects = when (parsed) {
                                is JsonArray -> parsed.map { it as? JsonObject ?: error("El historial contiene movimientos inválidos.") }
                                is JsonObject -> listOf(parsed) // Imported REST servers may return a single row.
                                else -> error("El servidor no confirmó el historial.")
                            }
                            val decoded = objects.map { value -> value.toWalletTransaction(userId) }
                            val page = decoded.take(50)
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            session.requireCurrent()
                            checkHistoryPublication(session, read) {
                                _transactions.value = (if (read.reset) page else _transactions.value + page).distinctBy { it.id }
                                _transactionsHasMore.value = decoded.size > 50
                                historyOffset = read.offset + page.size
                                mutableHistoryStatus.value = WalletHistoryStatus(
                                    loaded = true, loading = true, owner = session,
                                    transactions = _transactions.value, hasMore = _transactionsHasMore.value,
                                )
                            }
                            Result.success(Unit)
                        }
                    }
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                session.requireCurrent()
                Result.failure(IllegalStateException("La consulta del historial tardó demasiado. Reintenta.", timeout))
            }
            result.onFailure { failure ->
                checkHistoryPublication(session, read) {
                    mutableHistoryStatus.value = mutableHistoryStatus.value.copy(
                        error = "No se pudieron actualizar los movimientos. Comprueba la conexión y reintenta.",
                        retryAppend = !read.reset,
                    )
                }
                Log.w(TAG, "Unable to fetch wallet transaction history", failure)
            }
            return result
        } finally {
            authService.commitForAccountLease(session) {
                synchronized(historyLock) {
                    if (historyRead === read) {
                        historyRead = null
                        mutableHistoryStatus.value = mutableHistoryStatus.value.copy(loading = false)
                    }
                }
            }
        }
    }

    private fun requireHistoryRead(read: HistoryRead) = synchronized(historyLock) {
        if (historyRead !== read) throw kotlinx.coroutines.CancellationException("Otra actualización reemplazó esta consulta del historial.")
    }

    private fun checkHistoryPublication(session: AuthSessionLease, read: HistoryRead, mutation: () -> Unit) {
        val published = authService.commitForAccountLease(session) {
            synchronized(historyLock) { requireHistoryRead(read); mutation() }
        }
        if (!published) session.requireCurrent()
    }

    private fun JsonObject.toWalletTransaction(owner: String): WalletTransaction {
        val id = string("id") ?: error("El movimiento no tiene referencia.")
        check(!containsKey("user_id") || string("user_id").equals(owner, ignoreCase = true)) { "El movimiento pertenece a otra cuenta." }
        val amount = this["amount_diamonds"]?.jsonPrimitive?.intOrNull
        val balance = (this["balance_after"] ?: this["balance_after_diamonds"])?.jsonPrimitive?.intOrNull
        check(amount != null && amount != Int.MIN_VALUE && balance != null && balance >= 0) { "El movimiento no contiene importes válidos." }
        return WalletTransaction(
            id = id,
            type = string("transaction_type") ?: string("type") ?: "movimiento",
            amountDiamonds = if (string("direction") == "debit" && amount > 0) -amount else amount,
            balanceAfterDiamonds = balance,
            description = string("description"), createdAt = string("created_at"),
            status = string("status") ?: "confirmed",
            providerReference = string("provider_reference") ?: string("provider_order_id"),
        )
    }

    fun currentAccountId(): String? = authService.getCurrentUserId()?.trim()?.takeIf { it.isNotBlank() }

    fun hasPendingGift(receiverId: String, liveStreamId: String, giftId: String, isPublic: Boolean): Boolean {
        val accountId = authService.captureAccountLease().accountId ?: return false
        return giftSendCoordinator.hasPending(giftOperationKey(accountId, receiverId, liveStreamId, giftId, isPublic))
    }

    suspend fun sendGift(
        receiverId: String,
        giftId: String,
        liveStreamId: String? = null,
        isPublic: Boolean = true,
        idempotencyKey: String
    ): Result<GiftReceipt> = sendGift(walletSession(), receiverId, giftId, liveStreamId, isPublic, idempotencyKey)

    internal suspend fun sendGift(
        session: AuthSessionLease,
        receiverId: String,
        giftId: String,
        liveStreamId: String?,
        isPublic: Boolean,
        idempotencyKey: String,
    ): Result<GiftReceipt> = sendGift(session, receiverId, giftId, liveStreamId, isPublic, idempotencyKey, null)

    internal suspend fun sendGift(
        session: AuthSessionLease,
        receiverId: String,
        giftId: String,
        liveStreamId: String?,
        isPublic: Boolean,
        idempotencyKey: String,
        expectedPriceDiamonds: Int?,
    ): Result<GiftReceipt> = accountScopedCall(session, { senderId ->
        check(supabaseConfig.isConfigured) { "La tienda de regalos no está configurada para este entorno" }
        authService.ensureValidAccessToken().getOrThrow()
        session.requireCurrent()
        authService.getAccessTokenForAccountAwait(senderId)
    }) { senderId, token ->
        withContext(Dispatchers.IO) {
            session.requireCurrent()
            val cleanReceiverId = receiverId.trim()
            val cleanGiftId = giftId.trim()
            val cleanLiveStreamId = liveStreamId?.trim().orEmpty()
            require(cleanReceiverId.isNotBlank()) { "No se encontró el receptor del regalo" }
            require(cleanGiftId.isNotBlank()) { "Selecciona un regalo válido" }

            val gift = _availableGifts.value.firstOrNull { it.id == cleanGiftId }
                ?: fetchGiftById(token, cleanGiftId)
                ?: throw IllegalArgumentException("GIFT_NOT_FOUND")
            val expectedPrice = expectedPriceDiamonds ?: gift.priceDiamonds
            require(expectedPrice > 0) { "El regalo seleccionado no tiene precio válido" }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            session.requireCurrent()
            val result = giftSendCoordinator.execute(
                operation = giftOperationKey(senderId, cleanReceiverId, cleanLiveStreamId, cleanGiftId, isPublic),
                proposedKey = idempotencyKey,
            ) { durableKey ->
                // The journal can block or fail. Recheck after its durable write, before any charge request.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                session.requireCurrent()
                // An attempted mutation makes earlier reads unsuitable for fresh confirmation,
                // even if its charge commits but the response is lost or malformed.
                invalidateBalanceRead(session)
                try {
                    val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/send_virtual_gift_v2") {
                        restHeaders(token)
                        contentType(ContentType.Application.Json)
                        header(HttpHeaders.Prefer, "return=representation")
                        setBody(buildJsonObject {
                            put("p_sender_id", senderId)
                            put("p_receiver_id", cleanReceiverId)
                            put("p_gift_id", cleanGiftId)
                            put("p_expected_price_diamonds", expectedPrice)
                            put("p_idempotency_key", durableKey)
                            put("p_is_public", isPublic)
                            put("p_message", JsonNull)
                            put("p_live_stream_id", cleanLiveStreamId.takeIf { it.isNotBlank() })
                        })
                    }
                    val body = response.bodyAsText()
                    if (!response.status.isSuccess()) {
                        throw IllegalStateException(parseSupabaseError(body) ?: "No se pudo enviar el regalo (${response.status.value})")
                    }
                    val receipt = decodeFirstObject(body)
                        ?: throw IllegalStateException("El servidor no devolvió el comprobante del regalo")
                    val transactionId = receipt.string("transaction_id")?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("El comprobante del regalo no es válido")
                    val newBalance = receipt["sender_balance_diamonds"]?.jsonPrimitive?.intOrNull
                    check(newBalance != null && newBalance >= 0) { "El comprobante no contiene un saldo confirmado válido" }
                    // This versioned endpoint must confirm the actual charged amount.
                    // A replay reconciles the original gift, even if its catalog price changed.
                    val chargedAmount = receipt["amount_diamonds"]?.jsonPrimitive?.intOrNull
                    val replay = receipt["idempotent_replay"]?.jsonPrimitive?.booleanOrNull
                    check(chargedAmount != null && chargedAmount > 0 && replay != null) { "El comprobante no contiene el importe confirmado." }
                    check(replay || chargedAmount == expectedPrice) { "El comprobante no coincide con el precio confirmado." }
                    // Imported receipt metadata remains optional; supplied fields must match.
                    check(!receipt.containsKey("ok") || receipt["ok"]?.jsonPrimitive?.booleanOrNull == true) { "El servidor no confirmó el regalo" }
                    check(!receipt.containsKey("receiver_id") || receipt.string("receiver_id").equals(cleanReceiverId, ignoreCase = true)) { "El comprobante corresponde a otro destinatario" }
                    check(!receipt.containsKey("gift_id") || receipt.string("gift_id") == cleanGiftId) { "El comprobante corresponde a otro regalo" }
                    check(!receipt.containsKey("live_stream_id") || receipt.string("live_stream_id").orEmpty().equals(cleanLiveStreamId, ignoreCase = true)) { "El comprobante corresponde a otro directo" }
                    check(!receipt.containsKey("is_public") || receipt["is_public"]?.jsonPrimitive?.booleanOrNull == isPublic) { "El comprobante tiene otra visibilidad" }
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    session.requireCurrent()
                    GiftReceipt(
                        transactionId = transactionId,
                        liveStreamId = cleanLiveStreamId.takeIf { it.isNotBlank() },
                        senderId = senderId,
                        senderName = receipt.string("sender_name").orEmpty().ifBlank { "Usuario" },
                        receiverId = cleanReceiverId,
                        gift = gift.copy(priceDiamonds = chargedAmount),
                        senderBalanceDiamonds = newBalance,
                        isPublic = isPublic,
                        idempotentReplay = replay
                    )
                } finally {
                    invalidateBalanceRead(session)
                }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            session.requireCurrent()
            result.onSuccess { receipt ->
                authService.commitForAccountLease(session) { balanceRead.set(null); _coinBalance.value = receipt.senderBalanceDiamonds }
            }
        }
    }

    suspend fun fetchBillingCapabilities(): Result<BillingCapabilities> {
        val session = walletSession()
        return accountScopedCall(session, { userId ->
            check(supabaseConfig.isConfigured) { "Las compras no están configuradas para este entorno." }
            authService.ensureValidAccessToken().getOrThrow()
            session.requireCurrent()
            authService.getAccessTokenForAccountAwait(userId)
        }) { _, token -> withContext(Dispatchers.IO) {
            session.requireCurrent()
            val response = supabaseConfig.httpClient.post("${supabaseConfig.supabaseUrl}/rest/v1/rpc/billing_capabilities") {
                restHeaders(token)
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { })
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw IllegalStateException(parseSupabaseError(body) ?: "No se pudo consultar Google Play")
            }
            val parsed = supabaseConfig.json.parseToJsonElement(body)
            val objectValue = when (parsed) {
                is JsonObject -> parsed
                is JsonArray -> parsed.singleOrNull() as? JsonObject
                else -> null
            } ?: error("El servidor no confirmó la disponibilidad de compras.")
            val enabled = objectValue["enabled"]?.jsonPrimitive?.booleanOrNull
                ?: error("El servidor no confirmó la disponibilidad de compras.")
            val provider = objectValue.string("provider") ?: "google_play"
            check(!enabled || provider == "google_play") { "El proveedor de compras no es compatible." }
            val productsValue = objectValue["products"]
            val products = if (productsValue == null && !enabled) emptyList() else {
                check(productsValue is JsonArray) { "El catálogo de compras no es válido." }
                productsValue.map { element ->
                    val product = element as? JsonObject ?: error("El catálogo contiene un producto inválido.")
                    val id = product.string("product_id")?.trim() ?: error("El producto no tiene referencia.")
                    val coins = product["coins"]?.jsonPrimitive?.intOrNull
                    check(coins != null && coins > 0) { "El producto no contiene una cantidad válida de monedas." }
                    CoinProduct(id, coins)
                }.also { check(it.map(CoinProduct::productId).distinct().size == it.size) { "El catálogo contiene productos duplicados." } }
            }
            val capabilities = BillingCapabilities(
                enabled = enabled,
                provider = provider,
                products = products,
                giftLimits = (objectValue["gift_limits"] as? JsonObject)?.let { limits ->
                    GiftSafetyLimits(
                        maxCoinsPerGift = limits.int("max_coins_per_gift").takeIf { it > 0 } ?: 5_000,
                        maxCoinsPerDay = limits.int("max_coins_per_day").takeIf { it > 0 } ?: 25_000,
                        maxCoinsPerWeek = limits.int("max_coins_per_week").coerceAtLeast(0),
                        maxGiftsPerMinute = limits.int("max_gifts_per_minute").takeIf { it > 0 } ?: 20,
                        highValueConfirmationCoins = limits.int("high_value_confirmation_coins")
                            .takeIf { it > 0 } ?: 900
                    )
                } ?: GiftSafetyLimits()
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            session.requireCurrent()
            Result.success(capabilities)
        } }
    }

    internal fun purchaseSession() = walletSession()

    internal fun publishPurchaseResult(session: AuthSessionLease, publish: () -> Unit) =
        authService.commitForAccountLease(session, publish)

    internal suspend fun verifyGooglePlayPurchase(
        productId: String,
        purchaseToken: String,
        session: AuthSessionLease
    ): Result<CoinPurchaseVerification> = accountScopedCall(session, { account ->
        check(supabaseConfig.isConfigured) { "Las compras no están configuradas para este entorno." }
        require(productId.isNotBlank() && purchaseToken.isNotBlank() && purchaseToken.length <= 4096) { "La referencia de compra no es válida." }
        authService.ensureValidAccessToken().getOrThrow()
        session.requireCurrent()
        authService.getAccessTokenForAccountAwait(account)
    }) { _, token ->
        withContext(Dispatchers.IO) {
            session.requireCurrent()
            invalidateBalanceRead(session)
            try {
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
                val status = value.string("status")
                val credited = value["credited"]?.jsonPrimitive?.booleanOrNull
                val coins = value["coins"]?.jsonPrimitive?.intOrNull
                check(status in setOf("pending", "credited") && credited != null && coins != null && coins >= 0) {
                    "El servidor no confirmó el estado de la compra."
                }
                val balance = if (status == "credited") value["balance_diamonds"]?.jsonPrimitive?.intOrNull else 0
                check(balance != null && balance >= 0) { "La confirmación no contiene un saldo válido." }
                check(if (status == "pending") !credited && coins == 0 else if (credited) coins > 0 else coins == 0) {
                    "La confirmación de monedas no es válida."
                }
                val verified = CoinPurchaseVerification(status!!, credited, coins, balance)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                session.requireCurrent()
                if (verified.status == "credited") publishPurchaseResult(session) { _coinBalance.value = balance }
                Result.success(verified)
            } finally {
                invalidateBalanceRead(session)
            }
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
            parameter("select", "user_id,balance_diamonds,balance_debt_diamonds,balance_credits,total_spent")
            parameter("limit", "1")
        }
        check(response.status.isSuccess()) { "No se pudo confirmar el saldo (${response.status.value})." }
        val parsed = supabaseConfig.json.parseToJsonElement(response.bodyAsText())
        val row = when (parsed) {
            is JsonArray -> {
                if (parsed.isEmpty()) return null
                parsed.first() as? JsonObject ?: error("El servidor devolvió un saldo inválido.")
            }
            is JsonObject -> parsed
            else -> error("El servidor no confirmó el saldo.")
        }
        return row.toWalletBalance()
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
        val balance = this["balance_diamonds"]?.jsonPrimitive?.intOrNull
        val debt = if (containsKey("balance_debt_diamonds")) this["balance_debt_diamonds"]?.jsonPrimitive?.intOrNull else 0
        check(balance != null && balance >= 0 && debt != null && debt >= 0) {
            "El servidor no devolvió un saldo y una deuda válidos."
        }
        return WalletBalance(
            userId = string("user_id").orEmpty(),
            balanceDiamonds = balance,
            balanceDebtDiamonds = debt,
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
            obj?.string("message")
                ?: obj?.string("error")
                ?: obj?.string("code")
                ?: obj?.string("details")
                ?: obj?.string("hint")
        }.getOrNull()
    }
}

internal data class WalletHistoryStatus(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val retryAppend: Boolean = false,
    val owner: AuthSessionLease? = null,
    val transactions: List<WalletTransaction> = emptyList(),
    val hasMore: Boolean = false,
)
