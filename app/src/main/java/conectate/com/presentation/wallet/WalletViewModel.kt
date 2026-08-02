package conectate.com.presentation.wallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.GiftReceipt
import conectate.com.data.model.VirtualGift
import conectate.com.data.model.BillingCapabilities
import conectate.com.data.repository.WalletRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletRepository: WalletRepository
) : ViewModel() {
    val coinBalance = walletRepository.coinBalance
    val availableGifts = walletRepository.availableGifts

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

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

    init {
        refresh()
    }

    fun refresh() {
        if (_loading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            try {
                walletRepository.fetchBalance()
                walletRepository.fetchAvailableGifts()
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
                _error.value = when {
                    failure.message.orEmpty().contains("INSUFFICIENT", ignoreCase = true) ->
                        "Saldo insuficiente. Recarga monedas y vuelve a confirmar el regalo."
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
}
