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
import conectate.com.data.supabase.AuthSessionLease
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@HiltViewModel
class WalletViewModel @Inject constructor(
    private val walletRepository: WalletRepository,
    private val creatorProgramRepository: CreatorProgramRepository,
    private val creatorPayoutRepository: CreatorPayoutRepository,
) : ViewModel() {
    private companion object {
        const val WALLET_REFRESH_ERROR = "No se pudo actualizar el saldo. Comprueba la conexión y vuelve a intentarlo."
    }

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

    private var payoutOwner = creatorPayoutRepository.captureSession()
    private val mutableGiftSession = MutableStateFlow(payoutOwner)
    internal val giftSession = mutableGiftSession.asStateFlow()
    private class GiftSend(val owner: AuthSessionLease, val checkingPrevious: Boolean) { var job: Job? = null }
    private var activeGift: GiftSend? = null
    private val payoutOperations = CreatorPayoutOperations(viewModelScope,
        creatorPayoutRepository::captureSession,
        { _payoutLoading.value = it },
        { _payoutMessage.value = payoutErrorMessage(it) })

    init {
        refresh()
        refreshEarnings()
        refreshPayouts()
        viewModelScope.launch {
            creatorPayoutRepository.observeSessionVisits().collect {
                val current = creatorPayoutRepository.captureSession()
                if (!payoutOwner.sameVisit(current)) {
                    resetGiftSession(current)
                    payoutOperations.cancel()
                    payoutOwner = current
                    _payoutSummary.value = null
                    _payoutMessage.value = null
                    if (current.isCurrent()) refreshPayouts()
                }
            }
        }
    }

    fun refresh() {
        if (!_loading.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val balance = walletRepository.fetchBalance()
                (balance.exceptionOrNull() as? CancellationException)?.let { throw it }
                balance.onSuccess {
                    _walletLastUpdatedAt.value = Instant.now().toString()
                    _error.compareAndSet(WALLET_REFRESH_ERROR, null)
                }.onFailure { _error.value = WALLET_REFRESH_ERROR }
                walletRepository.fetchAvailableGifts()
                walletRepository.fetchTransactionHistory()
                walletRepository.fetchBillingCapabilities()
                    .onSuccess { _billingCapabilities.value = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = WALLET_REFRESH_ERROR
            } finally {
                _loading.value = false
            }
        }
    }

    fun sendGift(receiverId: String, liveStreamId: String, gift: VirtualGift, isPublic: Boolean) =
        sendGift(walletRepository.purchaseSession(), receiverId, liveStreamId, gift, isPublic)

    internal fun sendGift(session: AuthSessionLease, receiverId: String, liveStreamId: String, gift: VirtualGift, isPublic: Boolean) {
        if (!session.isCurrent() || activeGift?.owner?.isCurrent() == true) return
        resetGiftSession(session)
        val limits = _billingCapabilities.value.giftLimits
        val pending = hasPendingGift(receiverId, liveStreamId, gift.id, isPublic)
        if (gift.priceDiamonds > limits.maxCoinsPerGift && !pending) {
            walletRepository.publishPurchaseResult(session) { _error.value = "Este regalo supera el límite permitido por el servidor." }
            return
        }
        if (coinBalance.value < gift.priceDiamonds && !pending) {
            walletRepository.publishPurchaseResult(session) { _error.value = "Saldo insuficiente. Recarga monedas y vuelve a confirmar el regalo." }
            return
        }
        cancelGift()
        val operation = GiftSend(session, pending)
        activeGift = operation
        _sendingGiftId.value = gift.id
        walletRepository.publishPurchaseResult(session) { _error.value = null; _lastReceipt.value = null }
        val idempotencyKey = UUID.randomUUID().toString()
        operation.job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(45_000) {
                    session.requireCurrent()
                    val result = walletRepository.sendGift(session, receiverId, gift.id, liveStreamId, isPublic, idempotencyKey)
                    currentCoroutineContext().ensureActive()
                    session.requireCurrent()
                    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                    publishGift(operation) {
                        result.onSuccess { _lastReceipt.value = it }
                            .onFailure { _error.value = giftErrorMessage(it, operation.checkingPrevious) }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                publishGift(operation) { _error.value = giftErrorMessage(timeout, operation.checkingPrevious) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publishGift(operation) { _error.value = giftErrorMessage(failure, operation.checkingPrevious) }
            } finally {
                finishGift(operation)
            }
        }
        operation.job!!.invokeOnCompletion { finishGift(operation) }
        if (activeGift === operation) operation.job!!.start() else operation.job!!.cancel()
    }

    private fun publishGift(operation: GiftSend, mutation: () -> Unit) {
        if (activeGift === operation) walletRepository.publishPurchaseResult(operation.owner, mutation)
    }

    private fun finishGift(operation: GiftSend) {
        if (activeGift === operation) {
            activeGift = null
            _sendingGiftId.value = null
        }
    }

    private fun cancelGift() {
        val previous = activeGift
        activeGift = null
        previous?.job?.cancel()
        _sendingGiftId.value = null
    }

    private fun resetGiftSession(session: AuthSessionLease) {
        if (mutableGiftSession.value.sameVisit(session)) return
        cancelGift()
        mutableGiftSession.value = session
        _lastReceipt.value = null
        _error.value = null
    }

    private fun giftErrorMessage(failure: Throwable, checkingPrevious: Boolean): String {
        if (checkingPrevious) return "El envío anterior sigue sin confirmar. Conservamos su clave: revisa el historial o vuelve a comprobarlo sin duplicar el cargo."
        val rawMessage = failure.message.orEmpty()
        return when {
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

    private fun publishPayout(session: AuthSessionLease, mutation: () -> Unit) {
        creatorPayoutRepository.publish(session, mutation)
    }

    private suspend fun loadPayoutSummary(session: AuthSessionLease, confirmed: Boolean = false) {
        creatorPayoutRepository.summary(session)
            .onSuccess { summary -> publishPayout(session) { _payoutSummary.value = summary } }
            .onFailure { error -> publishPayout(session) {
                _payoutMessage.value = if (confirmed) "El servidor confirmó la operación, pero no pudimos actualizar el resumen. Actualiza para comprobarlo."
                    else payoutErrorMessage(error)
            } }
    }

    fun refreshPayouts() = payoutOperations.run { session -> loadPayoutSummary(session) }

    fun configurePayoutAccount(email: String, currency: String) = payoutOperations.run { session ->
        creatorPayoutRepository.updateAccount(session, email, currency)
            .onSuccess {
                publishPayout(session) { _payoutMessage.value = "Cuenta PayPal registrada. Un administrador debe verificarla antes del primer retiro." }
                loadPayoutSummary(session, confirmed = true)
            }
            .onFailure { error -> publishPayout(session) { _payoutMessage.value = payoutErrorMessage(error) } }
    }

    fun requestCreatorPayout(amountMinor: Long) = payoutOperations.run { session ->
        creatorPayoutRepository.request(session, amountMinor)
            .onSuccess { response ->
                publishPayout(session) {
                    _payoutMessage.value = if (response.localCleanupPending) "Retiro confirmado. Quedó pendiente la limpieza local; conservamos la clave para evitar duplicarlo."
                        else if (response.duplicate) "La solicitud ya existía y no se duplicó."
                        else "Solicitud registrada. Queda pendiente de aprobación manual."
                }
                loadPayoutSummary(session, confirmed = true)
            }
            .onFailure { error -> publishPayout(session) { _payoutMessage.value = payoutErrorMessage(error) } }
    }

    fun cancelCreatorPayout(requestId: String) = payoutOperations.run { session ->
        creatorPayoutRepository.cancel(session, requestId)
            .onSuccess {
                publishPayout(session) { _payoutMessage.value = "Solicitud cancelada; el saldo reservado volvió a estar disponible." }
                loadPayoutSummary(session, confirmed = true)
            }
            .onFailure { error -> publishPayout(session) { _payoutMessage.value = payoutErrorMessage(error) } }
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
            "PAYOUT_PREVIOUS_ATTEMPT_UNCONFIRMED" -> "Hay un retiro sin confirmar. Reintenta el mismo monto para comprobarlo antes de solicitar otro."
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
