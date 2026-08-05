package conectate.com.presentation.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.BillingCapabilities
import conectate.com.data.model.CreatorProgramDashboard
import conectate.com.data.repository.CreatorProgramRepository
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
    private val creatorProgramRepository: CreatorProgramRepository
) : ViewModel() {
    val coinBalance = walletRepository.coinBalance
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

    private val _historyLoadingMore = MutableStateFlow(false)
    val historyLoadingMore: StateFlow<Boolean> = _historyLoadingMore.asStateFlow()

    init {
        refresh()
        refreshEarnings()
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
        if (_sendingGiftId.value != null) return
        val limits = _billingCapabilities.value.giftLimits
        if (gift.priceDiamonds > limits.maxCoinsPerGift) {
            _error.value = "Este regalo supera el límite permitido por el servidor."
            return
        }
        if (coinBalance.value < gift.priceDiamonds) {
            _error.value = "Saldo insuficiente. Recarga monedas y vuelve a confirmar el regalo."
            return
        }
        val idempotencyKey = UUID.randomUUID().toString()
        viewModelScope.launch(Dispatchers.IO) {
            _sendingGiftId.value = gift.id
            _error.value = null
            walletRepository.sendGift(
                receiverId = receiverId,
                giftId = gift.id,
                liveStreamId = liveStreamId,
                isPublic = isPublic,
                idempotencyKey = idempotencyKey
            ).onSuccess { receipt ->
                _lastReceipt.value = receipt
            }.onFailure { failure ->
                val rawMessage = failure.message.orEmpty()
                _error.value = when {
                    rawMessage.contains("INSUFFICIENT_BALANCE", ignoreCase = true) ->
                        "Saldo insuficiente. Recarga monedas y vuelve a confirmar el regalo."
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
                        "No se pudo confirmar el regalo. No se descontaron monedas; vuelve a intentarlo."
                    else -> failure.message ?: "No se pudo enviar el regalo. No se descontaron monedas."
                }
            }
            _sendingGiftId.value = null
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun verifyGooglePlayPurchase(productId: String, purchaseToken: String) {
        if (_billingVerifying.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _billingVerifying.value = true
            walletRepository.verifyGooglePlayPurchase(productId, purchaseToken)
                .onSuccess { result ->
                    _billingMessage.value = when (result.status) {
                        "pending" -> "Pago pendiente. Las monedas se acreditarán cuando Google Play confirme el cobro."
                        "credited" -> if (result.credited) {
                            "Compra verificada: se acreditaron ${result.coins} monedas."
                        } else {
                            "Esta compra ya había sido acreditada."
                        }
                        else -> "Google Play devolvió un estado no reconocido."
                    }
                }
                .onFailure { failure ->
                    _billingMessage.value = failure.message ?: "No se pudo verificar la compra con Google Play."
                }
            _billingVerifying.value = false
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
