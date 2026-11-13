package conectate.com.presentation.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.BillingCapabilities
import conectate.com.data.model.CreatorProgramDashboard
import conectate.com.data.model.CreatorPayoutSummary
import conectate.com.data.payout.CreatorPayoutApiException
import conectate.com.data.repository.CreatorProgramRepository
import conectate.com.data.repository.CreatorPayoutRepository
import conectate.com.data.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val creatorProgramRepository: CreatorProgramRepository,
    private val creatorPayoutRepository: CreatorPayoutRepository,
) : ViewModel() {
    val coinBalance = walletRepository.coinBalance
    val coinDebt = walletRepository.coinDebt
    val availableGifts = walletRepository.availableGifts
    val transactions = walletRepository.transactions
    val transactionsHasMore = walletRepository.transactionsHasMore

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _walletLastUpdatedAt = MutableStateFlow<String?>(null)
    val walletLastUpdatedAt: StateFlow<String?> = _walletLastUpdatedAt.asStateFlow()

    private val _sendingGiftId = MutableStateFlow<String?>(null)
    val sendingGiftId: StateFlow<String?> = _sendingGiftId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastReceipt = MutableStateFlow<GiftReceipt?>(null)
    val lastReceipt: StateFlow<GiftReceipt?> = _lastReceipt.asStateFlow()

    private val _billingCapabilities = MutableStateFlow(BillingCapabilities())
    val billingCapabilities: StateFlow<BillingCapabilities> = _billingCapabilities.asStateFlow()

    private val _billingMessage = MutableStateFlow<String?>(null)
    val billingMessage: StateFlow<String?> = _billingMessage.asStateFlow()

    private val _billingVerifying = MutableStateFlow(false)
    val billingVerifying: StateFlow<Boolean> = _billingVerifying.asStateFlow()

    private val _creatorDashboard = MutableStateFlow(CreatorProgramDashboard())
    val creatorDashboard: StateFlow<CreatorProgramDashboard> = _creatorDashboard.asStateFlow()

    private val _earningsLoading = MutableStateFlow(false)
    val earningsLoading: StateFlow<Boolean> = _earningsLoading.asStateFlow()

    private val _earningsError = MutableStateFlow<String?>(null)
    val earningsError: StateFlow<String?> = _earningsError.asStateFlow()

    private val _payoutSummary = MutableStateFlow<CreatorPayoutSummary?>(null)
    val payoutSummary: StateFlow<CreatorPayoutSummary?> = _payoutSummary.asStateFlow()

    private val _payoutLoading = MutableStateFlow(false)
    val payoutLoading: StateFlow<Boolean> = _payoutLoading.asStateFlow()

    private val _payoutMessage = MutableStateFlow<String?>(null)
    val payoutMessage: StateFlow<String?> = _payoutMessage.asStateFlow()

    private val _historyLoadingMore = MutableStateFlow(false)
    val historyLoadingMore: StateFlow<Boolean> = _historyLoadingMore.asStateFlow()

    init {
        refresh()
        refreshEarnings()
        refreshPayouts()
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            try {
                walletRepository.fetchBalance()
                _walletLastUpdatedAt.value = Instant.now().toString()
                walletRepository.fetchAvailableGifts()
                walletRepository.fetchTransactionHistory()
                walletRepository.fetchBillingCapabilities()
                    .onSuccess { _billingCapabilities.value = it }
            } finally {
                _loading.value = false
            }
        }
    }

    fun sendGift(
        receiverId: String,
        liveStreamId: String,
        gift: VirtualGift,
        isPublic: Boolean
    ) {
        val limits = _billingCapabilities.value.giftLimits
        val pending = hasPendingGift(receiverId, liveStreamId, gift.id, isPublic)
        if (gift.priceDiamonds > limits.maxCoinsPerGift && !pending) {
            _error.value = "Este regalo supera el límite permitido por el servidor."
            return
        }
        if (coinBalance.value < gift.priceDiamonds && !pending) {
            _error.value = "Saldo insuficiente. Recarga monedas y vuelve a confirmar el regalo."
            return
        }
        val idempotencyKey = UUID.randomUUID().toString()
        if (!_sendingGiftId.compareAndSet(null, gift.id)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
            _error.value = null
            walletRepository.sendGift(
                receiverId = receiverId,
                giftId = gift.id,
                liveStreamId = liveStreamId,
                isPublic = isPublic,
                idempotencyKey = idempotencyKey
            ).onSuccess { receipt ->
                if (walletRepository.currentAccountId() == receipt.senderId) _lastReceipt.value = receipt
            }.onFailure { failure ->
                val rawMessage = failure.message.orEmpty()
                _error.value = when {
                    rawMessage.contains("WALLET_DEBT_OUTSTANDING", ignoreCase = true) ->
                        "Tu cuenta tiene monedas pendientes por un reembolso. La próxima compra verificable pagará primero esa deuda."
                    rawMessage.contains("INSUFFICIENT_BALANCE", ignoreCase = true) ->
                        "Saldo insuficiente. Recarga monedas y vuelve a confirmar el regalo."
                    rawMessage.contains("LIVE_GIFTS_DISABLED", ignoreCase = true) ->
                        "El creador desactivó los regalos para este directo. No se descontaron monedas."
                    rawMessage.contains("GIFTS_DISABLED", ignoreCase = true) ->
                        "Los regalos están temporalmente desactivados. No se descontaron monedas."
                    rawMessage.contains("LIVE_NOT_ACTIVE", ignoreCase = true) ->
                        "Este directo ya terminó o no está activo. No se descontaron monedas."
                    rawMessage.contains("LIVE_RECEIVER_MISMATCH", ignoreCase = true) ->
                        "El regalo no corresponde al creador de este directo."
                    rawMessage.contains("SELF_GIFT_NOT_ALLOWED", ignoreCase = true) ->
                        "No puedes enviarte regalos a ti mismo."
                    rawMessage.contains("GIFT_VALUE_LIMIT", ignoreCase = true) ->
                        "Este regalo supera el límite configurado para la cuenta."
                    rawMessage.contains("GIFT_DAILY_LIMIT", ignoreCase = true) ->
                        "Alcanzaste el límite diario de regalos. No se descontaron monedas."
                    rawMessage.contains("RATE_LIMITED", ignoreCase = true) ->
                        "Estás enviando regalos demasiado rápido. Espera un momento y vuelve a intentarlo."
                    rawMessage.contains("GIFT_NOT_FOUND", ignoreCase = true) ->
                        "El catálogo cambió. Actualízalo y vuelve a seleccionar el regalo."
                    rawMessage.contains("DATABASE_ERROR", ignoreCase = true) ->
                        "No se pudo confirmar el regalo. Reintenta el mismo regalo para comprobar el envío sin duplicarlo."
                    else -> "No se pudo confirmar la respuesta. Reintenta el mismo regalo; se comprobará el envío anterior sin duplicar el cargo."
                }
            }
            } finally {
                _sendingGiftId.value = null
            }
        }
    }

    fun hasPendingGift(receiverId: String, liveStreamId: String, giftId: String, isPublic: Boolean): Boolean =
        walletRepository.hasPendingGift(receiverId, liveStreamId, giftId, isPublic)

    fun clearError() {
        _error.value = null
    }

    private val purchaseQueue = PlayPurchaseQueue(viewModelScope) { busy -> _billingVerifying.value = busy }

    fun verifyGooglePlayPurchase(accountId: String, productId: String, purchaseToken: String) {
        val session = walletRepository.purchaseSession()
        if (!accountId.equals(session.accountId, ignoreCase = true) || !session.isCurrent()) return
        purchaseQueue.submit(accountId, purchaseToken) {
            if (!session.isCurrent()) return@submit
            val result = walletRepository.verifyGooglePlayPurchase(productId, purchaseToken, session)
            walletRepository.publishPurchaseResult(session) {
                _billingMessage.value = result.fold(
                    onSuccess = { verified ->
                        when (verified.status) {
                            "pending" -> "Pago pendiente. Las monedas se acreditarán cuando Google Play confirme el cobro."
                            "credited" -> if (verified.credited) "Compra verificada: se acreditaron ${verified.coins} monedas."
                                else "Esta compra ya había sido acreditada."
                            else -> "Google Play devolvió un estado no reconocido."
                        }
                    },
                    onFailure = { it.message ?: "No se pudo verificar la compra con Google Play." }
                )
            }
            if (result.isSuccess && session.isCurrent()) walletRepository.fetchBalance()
        }
    }

    fun showBillingMessage(message: String) {
        _billingMessage.value = message
    }

    fun clearBillingMessage() {
        _billingMessage.value = null
    }

    fun consumeReceipt() {
        _lastReceipt.value = null
    }

    fun refreshEarnings() {
        if (_earningsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _earningsLoading.value = true
            _earningsError.value = null
            creatorProgramRepository.loadDashboard()
                .onSuccess { _creatorDashboard.value = it }
                .onFailure { failure ->
                    _earningsError.value = failure.message
                        ?: "No se pudo actualizar el estado de ganancias."
                }
            _earningsLoading.value = false
        }
    }

    fun refreshPayouts() {
        if (_payoutLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _payoutLoading.value = true
            creatorPayoutRepository.summary()
                .onSuccess { _payoutSummary.value = it }
                .onFailure { _payoutMessage.value = payoutErrorMessage(it) }
            _payoutLoading.value = false
        }
    }

    fun configurePayoutAccount(email: String, currency: String) {
        if (_payoutLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _payoutLoading.value = true
            creatorPayoutRepository.updateAccount(email, currency)
                .onSuccess {
                    _payoutMessage.value = "Cuenta PayPal registrada. Un administrador debe verificarla antes del primer retiro."
                    creatorPayoutRepository.summary().onSuccess { summary -> _payoutSummary.value = summary }
                }
                .onFailure { _payoutMessage.value = payoutErrorMessage(it) }
            _payoutLoading.value = false
        }
    }

    fun requestCreatorPayout(amountMinor: Long) {
        if (_payoutLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _payoutLoading.value = true
            creatorPayoutRepository.request(amountMinor)
                .onSuccess { response ->
                    _payoutMessage.value = if (response.duplicate) {
                        "La solicitud ya existía y no se duplicó."
                    } else {
                        "Solicitud registrada. Queda pendiente de aprobación manual."
                    }
                    creatorPayoutRepository.summary().onSuccess { summary -> _payoutSummary.value = summary }
                }
                .onFailure { _payoutMessage.value = payoutErrorMessage(it) }
            _payoutLoading.value = false
        }
    }

    fun cancelCreatorPayout(requestId: String) {
        if (_payoutLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _payoutLoading.value = true
            creatorPayoutRepository.cancel(requestId)
                .onSuccess {
                    _payoutMessage.value = "Solicitud cancelada; el saldo reservado volvió a estar disponible."
                    creatorPayoutRepository.summary().onSuccess { summary -> _payoutSummary.value = summary }
                }
                .onFailure { _payoutMessage.value = payoutErrorMessage(it) }
            _payoutLoading.value = false
        }
    }

    fun clearPayoutMessage() {
        _payoutMessage.value = null
    }

    private fun payoutErrorMessage(error: Throwable): String {
        val code = (error as? CreatorPayoutApiException)?.code ?: error.message.orEmpty()
        return when (code) {
            "CREATOR_PAYOUTS_DISABLED" -> "Los retiros todavía están desactivados durante las pruebas de seguridad."
            "PAYOUT_PROVIDER_NOT_CONFIGURED" -> "PayPal Sandbox aún no está configurado en el servidor."
            "CREATOR_NOT_VERIFIED" -> "Solo los creadores verificados pueden solicitar retiros."
            "PAYOUT_ACCOUNT_NOT_VERIFIED" -> "La cuenta PayPal todavía no fue verificada por un administrador."
            "PAYOUT_MINIMUM_NOT_REACHED" -> "El monto no alcanza el mínimo de retiro configurado."
            "PAYOUT_INSUFFICIENT_BALANCE" -> "No hay suficiente ganancia disponible para ese retiro."
            "PAYOUT_EMAIL_INVALID" -> "Ingresa un correo PayPal válido."
            "PAYOUT_ACCOUNT_ALREADY_IN_USE" -> "Esta cuenta PayPal ya está asociada a otro creador."
            "PAYOUT_IDEMPOTENCY_CONFLICT" -> "La solicitud no pudo repetirse porque sus datos cambiaron."
            else -> "No se pudo completar la operación de retiro. Inténtalo nuevamente."
        }
    }

    fun loadMoreTransactions() {
        if (_historyLoadingMore.value || !transactionsHasMore.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _historyLoadingMore.value = true
            walletRepository.fetchTransactionHistory(reset = false)
            _historyLoadingMore.value = false
        }
    }

    fun currentAccountId(): String = walletRepository.currentAccountId().orEmpty()
}
