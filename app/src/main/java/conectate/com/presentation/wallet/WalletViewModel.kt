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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
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
    private val mutableBillingLoadError = MutableStateFlow<String?>(null)
    val billingLoadError = mutableBillingLoadError.asStateFlow()

    private val _billingMessage = MutableStateFlow<String?>(null)
    val billingMessage: StateFlow<String?> = _billingMessage.asStateFlow()

    private val _billingVerifying = MutableStateFlow(false)
    val billingVerifying: StateFlow<Boolean> = _billingVerifying.asStateFlow()
    private val purchaseQueue = PlayPurchaseQueue(viewModelScope) { busy -> _billingVerifying.value = busy }

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
    internal val historyStatus get() = walletRepository.historyStatus
    private var historyLoad: Any? = null

    private var payoutOwner = creatorPayoutRepository.captureSession()
    private var refreshOwner = payoutOwner
    private var refreshGeneration = 0L
    private var refreshJob: Job? = null
    private val mutableRefreshState = MutableStateFlow(WalletRefreshState(owner = payoutOwner))
    internal val refreshState = mutableRefreshState.asStateFlow()
    private val mutableGiftSession = MutableStateFlow(payoutOwner)
    internal val giftSession = mutableGiftSession.asStateFlow()
    internal val accountSession get() = giftSession
    private var earningsOwner = payoutOwner
    private var earningsJob: Job? = null
    private var earningsGeneration = 0L
    private val mutableEarningsLoaded = MutableStateFlow(false)
    val earningsLoaded = mutableEarningsLoaded.asStateFlow()
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
            combine(coinBalance, coinDebt) { _, _ -> Unit }.collect {
                val owner = creatorPayoutRepository.captureSession()
                if (refreshOwner.sameVisit(owner)) creatorPayoutRepository.publish(owner) {
                    mutableRefreshState.value = mutableRefreshState.value.copy(balance = coinBalance.value, debt = coinDebt.value)
                }
            }
        }
        viewModelScope.launch {
            creatorPayoutRepository.observeSessionVisits().collect {
                val current = creatorPayoutRepository.captureSession()
                if (!payoutOwner.sameVisit(current)) {
                    resetRefreshSession(current)
                    resetGiftSession(current)
                    resetEarningsSession(current)
                    payoutOperations.onSessionChanged(current)
                    payoutOwner = current
                    _payoutSummary.value = null
                    _payoutMessage.value = null
                    if (current.isCurrent()) {
                        refresh()
                        refreshPayouts()
                        refreshEarnings()
                    }
                }
            }
        }
    }

    fun refresh() {
        val owner = creatorPayoutRepository.captureSession()
        resetRefreshSession(owner)
        if (!owner.isCurrent()) return
        if (!_loading.compareAndSet(false, true)) return
        val request = ++refreshGeneration
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                supervisorScope {
                    launch {
                        val result = refreshSection(owner) { walletRepository.fetchBalance() }
                        publishRefresh(owner, request) {
                            result.onSuccess {
                                _walletLastUpdatedAt.value = Instant.now().toString()
                                mutableRefreshState.value = mutableRefreshState.value.copy(
                                    confirmedAt = _walletLastUpdatedAt.value, balance = coinBalance.value, debt = coinDebt.value,
                                )
                                _error.compareAndSet(WALLET_REFRESH_ERROR, null)
                            }.onFailure { _error.value = WALLET_REFRESH_ERROR }
                        }
                    }
                    launch { refreshSection(owner) { walletRepository.fetchAvailableGifts() } }
                    // The repository has its own 15s deadline and publishes history failures.
                    launch { refreshSection(owner, timeoutMs = 20_000) { walletRepository.fetchTransactionHistory() } }
                    launch {
                        val result = refreshSection(owner) { walletRepository.fetchBillingCapabilities() }
                        publishRefresh(owner, request) {
                            result.onSuccess { _billingCapabilities.value = it; mutableBillingLoadError.value = null }
                                .onFailure {
                                    _billingCapabilities.value = BillingCapabilities()
                                    mutableBillingLoadError.value = "No se pudo consultar la disponibilidad de compras. Comprueba la conexión y reintenta."
                                }
                            mutableRefreshState.value = mutableRefreshState.value.copy(
                                capabilities = _billingCapabilities.value, billingError = mutableBillingLoadError.value,
                            )
                        }
                    }
                }
            } finally { finishRefresh(request) }
        }
        refreshJob = job
        job.invokeOnCompletion { finishRefresh(request) }
        if (refreshGeneration == request) job.start() else job.cancel()
    }

    private suspend fun <T> refreshSection(owner: AuthSessionLease, timeoutMs: Long = 15_000, read: suspend () -> Result<T>): Result<T> {
        return try {
            withTimeout(timeoutMs) {
                owner.requireCurrent()
                val result = read()
                currentCoroutineContext().ensureActive()
                owner.requireCurrent()
                (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                result
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            owner.requireCurrent()
            // A section deadline or a superseded history page does not cancel its siblings.
            Result.failure(cancelled)
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            owner.requireCurrent()
            Result.failure(failure)
        }
    }

    private fun publishRefresh(owner: AuthSessionLease, request: Long, mutation: () -> Unit) {
        if (refreshGeneration == request) creatorPayoutRepository.publish(owner, mutation)
    }

    private fun finishRefresh(request: Long) {
        if (refreshGeneration == request) { refreshJob = null; _loading.value = false }
    }

    private fun resetRefreshSession(owner: AuthSessionLease) {
        if (refreshOwner.sameVisit(owner)) return
        refreshOwner = owner
        refreshGeneration++
        val previous = refreshJob
        refreshJob = null
        previous?.cancel()
        purchaseQueue.cancelPending()
        _loading.value = false
        _walletLastUpdatedAt.value = null
        _billingCapabilities.value = BillingCapabilities()
        mutableBillingLoadError.value = null
        _billingMessage.value = null
        _error.value = null
        mutableRefreshState.value = WalletRefreshState(owner = owner)
    }

    fun sendGift(receiverId: String, liveStreamId: String, gift: VirtualGift, isPublic: Boolean) =
        sendGift(walletRepository.purchaseSession(), receiverId, liveStreamId, gift, isPublic)

    internal fun sendGift(session: AuthSessionLease, receiverId: String, liveStreamId: String, gift: VirtualGift, isPublic: Boolean) {
        if (!session.isCurrent() || activeGift?.owner?.isCurrent() == true) return
        resetRefreshSession(session)
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
                    val result = walletRepository.sendGift(session, receiverId, gift.id, liveStreamId, isPublic, idempotencyKey, gift.priceDiamonds)
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
        if (failure.message.orEmpty().contains("GIFT_PRICE_CHANGED", ignoreCase = true)) {
            return "El precio cambió. Actualiza el catálogo y vuelve a confirmar el importe antes de enviar. Conservamos la clave para evitar un cargo duplicado."
        }
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

    fun verifyGooglePlayPurchase(accountId: String, productId: String, purchaseToken: String) {
        val session = walletRepository.purchaseSession()
        if (!accountId.equals(session.accountId, ignoreCase = true) || !session.isCurrent()) return
        resetRefreshSession(session)
        purchaseQueue.submit(accountId, purchaseToken) {
            if (!session.isCurrent()) return@submit
            val result = refreshSection(session) { walletRepository.verifyGooglePlayPurchase(productId, purchaseToken, session) }
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
                    onFailure = { billingFailureMessage(it) }
                )
                mutableRefreshState.value = mutableRefreshState.value.copy(billingMessage = _billingMessage.value)
            }
            if (result.isSuccess && session.isCurrent()) refreshSection(session) { walletRepository.fetchBalance() }
        }
    }

    fun showBillingMessage(message: String) {
        showBillingMessage(creatorPayoutRepository.captureSession(), message)
    }

    internal fun showBillingMessage(owner: AuthSessionLease, message: String) {
        if (!owner.isCurrent()) return
        resetRefreshSession(owner)
        creatorPayoutRepository.publish(owner) {
            _billingMessage.value = message
            mutableRefreshState.value = mutableRefreshState.value.copy(billingMessage = message)
        }
    }

    private fun billingFailureMessage(error: Throwable): String {
        val code = error.message.orEmpty()
        return when {
            code.contains("GOOGLE_PLAY_ACCOUNT_MISMATCH") || code.contains("GOOGLE_PLAY_PURCHASE_ALREADY_CLAIMED") ->
                "Esta compra corresponde a otra cuenta. Abre la cuenta con la que la hiciste para comprobarla."
            code.contains("GOOGLE_PLAY_PURCHASE_REFUNDED") -> "La compra fue reembolsada y ya no permite acreditar monedas."
            code.contains("GOOGLE_PLAY_BILLING_DISABLED") -> "Las compras no están disponibles en este momento. Inténtalo más tarde."
            else -> "No se pudo verificar la compra. Comprueba la conexión y reintenta; se revisará sin duplicar las monedas."
        }
    }

    fun clearBillingMessage() {
        clearBillingMessage(creatorPayoutRepository.captureSession())
    }

    internal fun clearBillingMessage(owner: AuthSessionLease) {
        creatorPayoutRepository.publish(owner) {
            _billingMessage.value = null
            mutableRefreshState.value = mutableRefreshState.value.copy(billingMessage = null)
        }
    }

    fun consumeReceipt() {
        _lastReceipt.value = null
    }

    fun refreshEarnings() {
        val session = creatorPayoutRepository.captureSession()
        resetEarningsSession(session)
        if (!session.isCurrent()) {
            _earningsError.value = "Inicia sesión para consultar tus ganancias."
            return
        }
        if (_earningsLoading.value) return
        val request = ++earningsGeneration
        _earningsLoading.value = true
        _earningsError.value = null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(15_000) {
                    session.requireCurrent()
                    val result = creatorProgramRepository.loadDashboard()
                    currentCoroutineContext().ensureActive()
                    session.requireCurrent()
                    (result.exceptionOrNull() as? CancellationException)?.let { throw it }
                    publishEarnings(session, request) {
                        result.onSuccess { _creatorDashboard.value = it; mutableEarningsLoaded.value = true }
                            .onFailure { _earningsError.value = "No se pudieron actualizar tus ganancias. Comprueba la conexión y reintenta." }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                publishEarnings(session, request) { _earningsError.value = "La consulta tardó demasiado. Comprueba la conexión y reintenta." }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishEarnings(session, request) { _earningsError.value = "No se pudieron actualizar tus ganancias. Reintenta." }
            } finally {
                finishEarnings(request)
            }
        }
        earningsJob = job
        job.invokeOnCompletion { finishEarnings(request) }
        if (earningsGeneration == request) job.start() else job.cancel()
    }

    private fun publishEarnings(session: AuthSessionLease, request: Long, mutation: () -> Unit) {
        if (request == earningsGeneration) creatorPayoutRepository.publish(session, mutation)
    }

    private fun finishEarnings(request: Long) {
        if (request == earningsGeneration) {
            earningsJob = null
            _earningsLoading.value = false
        }
    }

    private fun resetEarningsSession(session: AuthSessionLease) {
        if (earningsOwner.sameVisit(session)) return
        earningsOwner = session
        earningsGeneration++
        val previous = earningsJob
        earningsJob = null
        previous?.cancel()
        _earningsLoading.value = false
        _earningsError.value = null
        _creatorDashboard.value = CreatorProgramDashboard()
        mutableEarningsLoaded.value = false
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

    fun configurePayoutAccount(email: String, currency: String) =
        configurePayoutAccount(creatorPayoutRepository.captureSession(), email, currency)

    internal fun configurePayoutAccount(owner: AuthSessionLease, email: String, currency: String) = payoutOperations.run(owner) { session ->
        creatorPayoutRepository.updateAccount(session, email, currency)
            .onSuccess {
                publishPayout(session) { _payoutMessage.value = "Cuenta PayPal registrada. Un administrador debe verificarla antes del primer retiro." }
                loadPayoutSummary(session, confirmed = true)
            }
            .onFailure { error -> publishPayout(session) { _payoutMessage.value = payoutErrorMessage(error) } }
    }

    fun requestCreatorPayout(amountMinor: Long) = requestCreatorPayout(creatorPayoutRepository.captureSession(), amountMinor)

    internal fun requestCreatorPayout(owner: AuthSessionLease, amountMinor: Long) = payoutOperations.run(owner) { session ->
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

    fun cancelCreatorPayout(requestId: String) = cancelCreatorPayout(creatorPayoutRepository.captureSession(), requestId)

    internal fun cancelCreatorPayout(owner: AuthSessionLease, requestId: String) = payoutOperations.run(owner) { session ->
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
        if (!transactionsHasMore.value || !_historyLoadingMore.compareAndSet(false, true)) return
        val owner = creatorPayoutRepository.captureSession()
        val operation = Any()
        historyLoad = operation
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                owner.requireCurrent()
                walletRepository.fetchTransactionHistory(reset = false)
            } finally { finishHistoryLoad(operation) }
        }
        job.invokeOnCompletion { finishHistoryLoad(operation) }
        job.start()
    }

    private fun finishHistoryLoad(operation: Any) {
        if (historyLoad === operation) {
            historyLoad = null
            _historyLoadingMore.value = false
        }
    }

    fun retryTransactionHistory() {
        val owner = creatorPayoutRepository.captureSession()
        val status = historyStatus.value
        val append = status.owner?.sameVisit(owner) == true && status.retryAppend
        viewModelScope.launch {
            owner.requireCurrent()
            walletRepository.fetchTransactionHistory(reset = !append)
        }
    }

    fun currentAccountId(): String = walletRepository.currentAccountId().orEmpty()
}

internal data class WalletRefreshState(
    val owner: AuthSessionLease?,
    val balance: Int = 0,
    val debt: Int = 0,
    val confirmedAt: String? = null,
    val capabilities: BillingCapabilities = BillingCapabilities(),
    val billingError: String? = null,
    val billingMessage: String? = null,
)
