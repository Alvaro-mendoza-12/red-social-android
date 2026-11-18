package conectate.com.presentation.wallet

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.*
import conectate.com.data.repository.*
import conectate.com.data.supabase.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WalletRefreshVisitTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val guard = AuthSessionMutationGuard()
    @Volatile private var account: String? = "a"
    private val wallet = mockk<WalletRepository>()
    private val program = mockk<CreatorProgramRepository>()
    private val payouts = mockk<CreatorPayoutRepository>()
    private lateinit var model: WalletViewModel
    private val balances = AtomicInteger()
    private val histories = AtomicInteger()
    private val capabilities = AtomicInteger()
    private val balanceFlow = MutableStateFlow(75)
    private var verifyResponse: suspend () -> Result<CoinPurchaseVerification> = { Result.failure(java.io.IOException("private SDK stack path")) }
    private var balanceResponse: suspend () -> Result<Unit> = { Result.success(Unit) }
    private var giftResponse: suspend () -> Result<Unit> = { Result.success(Unit) }
    private var capsResponse: suspend () -> Result<BillingCapabilities> = { Result.success(caps(account ?: "guest")) }
    private fun lease() = guard.captureAccountLease { account }
    private fun caps(owner: String) = BillingCapabilities(enabled = true, products = listOf(CoinProduct("coins-$owner", 10)))
    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        every { wallet.coinBalance } returns balanceFlow
        every { wallet.coinDebt } returns MutableStateFlow(0)
        every { wallet.availableGifts } returns MutableStateFlow(emptyList<VirtualGift>())
        every { wallet.transactions } returns MutableStateFlow(emptyList<WalletTransaction>())
        every { wallet.transactionsHasMore } returns MutableStateFlow(false)
        coEvery { wallet.fetchBalance() } coAnswers { balances.incrementAndGet(); balanceResponse() }
        coEvery { wallet.fetchAvailableGifts() } coAnswers { giftResponse() }
        coEvery { wallet.fetchTransactionHistory(any()) } coAnswers { histories.incrementAndGet(); Result.success(Unit) }
        coEvery { wallet.fetchBillingCapabilities() } coAnswers { capabilities.incrementAndGet(); capsResponse() }
        coEvery { program.loadDashboard() } returns Result.success(CreatorProgramDashboard())
        every { payouts.captureSession() } answers { lease() }
        every { payouts.observeSessionVisits() } returns guard.accountVisitChanges.map { lease() }
        every { payouts.publish(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
        every { wallet.purchaseSession() } answers { lease() }
        every { wallet.publishPurchaseResult(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
        coEvery { wallet.verifyGooglePlayPurchase(any(), any(), any()) } coAnswers { verifyResponse() }
        coEvery { payouts.summary(any()) } returns Result.success(CreatorPayoutSummary())
    }
    private fun create() { model = WalletViewModel(wallet, program, payouts); store.put("wallet", model) }
    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5000) { do { dispatcher.scheduler.runCurrent(); if (condition()) break; delay(5) } while (true) }
    }
    private suspend fun ready() = until { !model.loading.value }
    private fun switch(value: String?) { guard.invalidate { account = value }; dispatcher.scheduler.runCurrent() }
    private fun loadError(): Any? = model.javaClass.methods.firstOrNull { it.name.startsWith("getBillingLoadError") }
        ?.invoke(model)?.let { (it as StateFlow<*>).value }
    @After fun stop() = runBlocking {
        if (::model.isInitialized) {
            val job = model.viewModelScope.coroutineContext[Job]!!
            try { store.clear(); withTimeout(5000) { while (!job.isCompleted) { dispatcher.scheduler.runCurrent(); delay(5) } } }
            finally { Dispatchers.resetMain() }
        } else Dispatchers.resetMain()
    }
    @Test fun aConfirmedCurrentRefreshStillLoadsBalanceHistoryAndProducts() = runBlocking {
        create(); ready(); assertNotNull(model.walletLastUpdatedAt.value)
        assertEquals(1, histories.get()); assertEquals("coins-a", model.billingCapabilities.value.products.single().productId)
    }
    @Test fun changingVisitRemovesThePreviousConfirmationWhileTheNewBalanceIsPending() = runBlocking {
        create(); ready(); assertNotNull(model.walletLastUpdatedAt.value)
        balanceResponse = { awaitCancellation() }; switch("b")
        assertNull("The previous visit cannot confirm this balance", model.walletLastUpdatedAt.value)
    }
    @Test fun changingVisitStartsANewRefresh() = runBlocking {
        create(); ready(); switch("b"); until { balances.get() >= 2 }
        ready(); assertEquals("coins-b", model.billingCapabilities.value.products.single().productId)
    }
    @Test fun signingOutRemovesTheOldProductsAndConfirmation() = runBlocking {
        create(); ready(); switch(null)
        assertNull(model.walletLastUpdatedAt.value); assertFalse(model.billingCapabilities.value.enabled)
        assertTrue(model.billingCapabilities.value.products.isEmpty())
    }
    @Test fun anOldFailureCannotReplaceTheNewVisitsSuccess() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        balanceResponse = { val owner = account; if (owner == "a") { entered.complete(Unit); withContext(NonCancellable) { release.await() }; Result.failure(java.io.IOException("old")) } else Result.success(Unit) }
        create(); until { entered.isCompleted }
        try { switch("b"); until { balances.get() >= 2 }; ready(); val confirmation = model.walletLastUpdatedAt.value
            assertNotNull(confirmation); release.complete(Unit); delay(30); dispatcher.scheduler.runCurrent()
            assertEquals(confirmation, model.walletLastUpdatedAt.value); assertNull(model.error.value)
        } finally { release.complete(Unit) }
    }
    @Test fun anOldProductReplyCannotRestoreThePreviousVisitsCatalog() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        capsResponse = { val owner = account ?: "guest"; if (owner == "a") { entered.complete(Unit); withContext(NonCancellable) { release.await() } }; Result.success(caps(owner)) }
        create(); until { entered.isCompleted }
        try { switch("b"); until { capabilities.get() >= 2 }; ready(); release.complete(Unit); delay(30); dispatcher.scheduler.runCurrent()
            assertEquals("coins-b", model.billingCapabilities.value.products.single().productId)
        } finally { release.complete(Unit) }
    }
    @Test fun returningToTheSameAccountIsStillANewVisit() = runBlocking {
        create(); ready(); balanceResponse = { awaitCancellation() }
        guard.invalidate { account = "b" }; guard.invalidate { account = "a" }; dispatcher.scheduler.runCurrent()
        assertNull(model.walletLastUpdatedAt.value)
    }
    @Test fun aProductLoadFailureIsVisibleWithoutErasingTheConfirmedBalance() = runBlocking {
        capsResponse = { Result.failure(java.io.IOException("private server details")) }
        create(); ready(); assertNotNull(model.walletLastUpdatedAt.value)
        assertNotNull("A failed request is not confirmed unavailability", loadError())
        assertFalse(loadError().toString().contains("private server details"))
    }
    @Test fun aBlockedBalanceDoesNotBlockHistoryOrProductsAndHasADeadline() = runTest(dispatcher) {
        balanceResponse = { awaitCancellation() }; create(); runCurrent()
        advanceTimeBy(15001); runCurrent()
        assertFalse(model.loading.value); assertTrue(histories.get() > 0); assertTrue(capabilities.get() > 0)
        assertNull(model.walletLastUpdatedAt.value); assertNotNull(model.error.value)
    }
    @Test fun aBlockedGiftCatalogDoesNotBlockHistoryOrProductsAndHasADeadline() = runTest(dispatcher) {
        giftResponse = { awaitCancellation() }; create(); runCurrent()
        advanceTimeBy(15001); runCurrent()
        assertFalse(model.loading.value); assertTrue(histories.get() > 0); assertTrue(capabilities.get() > 0)
    }
    @Test fun lifecycleCancellationAlsoReleasesLoading() = runBlocking {
        balanceResponse = { awaitCancellation() }; create(); until { balances.get() > 0 }
        store.clear(); until { !model.loading.value }; assertFalse(model.loading.value)
    }
    @Test fun failedVerificationDoesNotExposeRawServerDetails() = runBlocking {
        create(); ready(); model.verifyGooglePlayPurchase("a","coins","fictional-token")
        until { !model.billingVerifying.value }; assertNotNull(model.billingMessage.value)
        assertFalse(model.billingMessage.value.orEmpty().contains("private SDK stack path"))
    }
    @Test fun aBlockedVerificationHasADeadlineAndReleasesBusy() = runTest(dispatcher) {
        verifyResponse = { awaitCancellation() }; create(); runCurrent()
        model.verifyGooglePlayPurchase("a","coins","fictional-token"); runCurrent(); advanceTimeBy(15001); runCurrent()
        assertFalse(model.billingVerifying.value); assertNotNull(model.billingMessage.value)
    }
    @Test fun theDisplaySnapshotTracksConfirmedBalanceChanges() = runBlocking {
        create(); ready(); balanceFlow.value=42; dispatcher.scheduler.runCurrent()
        val value = (model.javaClass.methods.firstOrNull { it.name.startsWith("getRefreshState") }?.invoke(model) as? StateFlow<*>)?.value
        val balance = value?.javaClass?.methods?.firstOrNull { it.name=="getBalance" }?.invoke(value)
        assertEquals(42,balance)
    }
}
