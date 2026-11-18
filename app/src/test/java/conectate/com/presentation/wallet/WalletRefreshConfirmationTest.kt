package conectate.com.presentation.wallet

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.*
import conectate.com.data.repository.*
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WalletRefreshConfirmationTest {
    private val wallet = mockk<WalletRepository>()
    private val program = mockk<CreatorProgramRepository>()
    private val payouts = mockk<CreatorPayoutRepository>()
    private val guard = AuthSessionMutationGuard()
    private val store = ViewModelStore()
    private lateinit var model: WalletViewModel
    private var reply: Result<Unit> = Result.success(Unit)
    private var entered = CompletableDeferred<Unit>()
    private var release: CompletableDeferred<Unit>? = null
    private var balanceCalls = java.util.concurrent.atomic.AtomicInteger()

    @Before fun start() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        every { wallet.coinBalance } returns MutableStateFlow(75)
        every { wallet.coinDebt } returns MutableStateFlow(9)
        every { wallet.availableGifts } returns MutableStateFlow(emptyList<VirtualGift>())
        every { wallet.transactions } returns MutableStateFlow(emptyList<WalletTransaction>())
        every { wallet.transactionsHasMore } returns MutableStateFlow(false)
        coEvery { wallet.fetchBalance() } coAnswers {
            balanceCalls.incrementAndGet(); entered.complete(Unit); release?.await(); reply
        }
        coEvery { wallet.fetchAvailableGifts() } returns Result.success(Unit)
        coEvery { wallet.fetchTransactionHistory(any()) } returns Result.success(Unit)
        coEvery { wallet.fetchBillingCapabilities() } returns Result.success(BillingCapabilities())
        coEvery { program.loadDashboard() } returns Result.success(CreatorProgramDashboard())
        every { payouts.captureSession() } answers { guard.captureAccountLease { "a" } }
        every { payouts.observeSessionVisits() } returns emptyFlow()
        every { payouts.publish(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
        coEvery { payouts.summary(any()) } returns Result.success(CreatorPayoutSummary())
    }
    private fun create() { model = WalletViewModel(wallet, program, payouts); store.put("wallet", model) }
    private suspend fun finished() {
        withTimeout(5000) { entered.await(); model.loading.first { !it } }
    }
    @After fun close(): Unit = runBlocking {
        val job = if (::model.isInitialized) model.viewModelScope.coroutineContext[Job] else null
        try {
            store.clear()
            withTimeout(5000) { job?.join() }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun aFailedFirstLoadCannotDisplayASuccessfulRefreshTime() = runBlocking {
        reply = Result.failure(java.io.IOException("offline")); create(); finished()
        assertNull(model.walletLastUpdatedAt.value)
        assertNotNull("The screen must identify the failed update", model.error.value)
    }
    @Test fun aFailedRefreshKeepsThePreviousConfirmationTime() = runBlocking {
        create(); finished(); val confirmed = model.walletLastUpdatedAt.value
        assertNotNull(confirmed)
        reply = Result.failure(java.io.IOException("offline")); entered = CompletableDeferred()
        model.refresh(); finished()
        assertEquals(confirmed, model.walletLastUpdatedAt.value)
        assertNotNull(model.error.value)
    }
    @Test fun aSuccessfulRetryPublishesAConfirmationAndClearsTheLoadError() = runBlocking {
        reply = Result.failure(java.io.IOException("offline")); create(); finished()
        reply = Result.success(Unit); entered = CompletableDeferred()
        model.refresh(); finished()
        assertNotNull(model.walletLastUpdatedAt.value); assertNull(model.error.value)
    }
    @Test fun repeatedRefreshClicksDoNotStartConcurrentBalanceLoads() = runBlocking {
        release = CompletableDeferred(); create()
        withTimeout(5000) { entered.await() }
        repeat(10) { model.refresh() }
        release!!.complete(Unit); finished()
        assertEquals(1, balanceCalls.get())
    }
}
