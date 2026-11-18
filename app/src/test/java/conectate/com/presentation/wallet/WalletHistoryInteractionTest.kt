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

@OptIn(ExperimentalCoroutinesApi::class)
class WalletHistoryInteractionTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val guard = AuthSessionMutationGuard()
    private val wallet = mockk<WalletRepository>()
    private val payouts = mockk<CreatorPayoutRepository>()
    private lateinit var model: WalletViewModel
    private var calls = 0
    private var initialReset = true
    private val resets = mutableListOf<Boolean>()
    private val history = MutableStateFlow(WalletHistoryStatus())
    private var response: suspend () -> Result<Unit> = { Result.success(Unit) }
    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        every { wallet.coinBalance } returns MutableStateFlow(0)
        every { wallet.coinDebt } returns MutableStateFlow(0)
        every { wallet.availableGifts } returns MutableStateFlow(emptyList<VirtualGift>())
        every { wallet.transactions } returns MutableStateFlow(emptyList<WalletTransaction>())
        every { wallet.transactionsHasMore } returns MutableStateFlow(true)
        every { wallet.historyStatus } returns history
        coEvery { wallet.fetchBalance() } coAnswers { awaitCancellation() }
        coEvery { wallet.fetchTransactionHistory(any()) } coAnswers {
            if (firstArg<Boolean>() && initialReset) { initialReset = false; awaitCancellation() }
            calls++; resets += firstArg<Boolean>(); response()
        }
        val program = mockk<CreatorProgramRepository>()
        coEvery { program.loadDashboard() } returns Result.success(CreatorProgramDashboard())
        every { payouts.captureSession() } answers { guard.captureAccountLease { "a" } }
        every { payouts.observeSessionVisits() } returns emptyFlow()
        every { payouts.publish(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
        coEvery { payouts.summary(any()) } returns Result.success(CreatorPayoutSummary())
        model = WalletViewModel(wallet, program, payouts); store.put("wallet", model)
        dispatcher.scheduler.runCurrent()
    }
    @After fun stop() = runBlocking {
        val job = model.viewModelScope.coroutineContext[Job]!!
        try {
            store.clear()
            withTimeout(5000) { while (!job.isCompleted) { dispatcher.scheduler.runCurrent(); delay(5) } }
        } finally { Dispatchers.resetMain() }
    }
    @Test fun aSecondTapIsBlockedBeforeEitherCoroutineRuns() = runTest(dispatcher) {
        response = { awaitCancellation() }
        model.loadMoreTransactions(); model.loadMoreTransactions(); runCurrent()
        assertEquals(1, calls); assertTrue(model.historyLoadingMore.value)
    }
    @Test fun cancellationReleasesTheLoadMoreControl() = runTest(dispatcher) {
        response = { throw CancellationException("cancelled") }
        model.loadMoreTransactions(); runCurrent()
        assertFalse(model.historyLoadingMore.value)
    }
    @Test fun aChangedVisitCannotRunItsQueuedAppend() = runTest(dispatcher) {
        model.loadMoreTransactions(); guard.invalidate { }; runCurrent()
        assertEquals(0, calls); assertFalse(model.historyLoadingMore.value)
    }
    @Test fun clearingBeforeDispatchReleasesTheReservedControl() = runTest(dispatcher) {
        model.loadMoreTransactions(); store.clear(); runCurrent()
        assertEquals(0, calls); assertFalse(model.historyLoadingMore.value)
    }
    @Test fun aFailedReadCanBeRetriedWithoutAStuckButton() = runTest(dispatcher) {
        response = { Result.failure(java.io.IOException("offline")) }
        model.loadMoreTransactions(); runCurrent(); assertFalse(model.historyLoadingMore.value)
        response = { Result.success(Unit) }
        model.loadMoreTransactions(); runCurrent(); assertEquals(2, calls); assertFalse(model.historyLoadingMore.value)
    }
    @Test fun aCurrentFailedAppendRetriesItsOriginalPage() = runTest(dispatcher) {
        history.value = WalletHistoryStatus(retryAppend = true, owner = payouts.captureSession())
        model.retryTransactionHistory(); runCurrent(); assertEquals(listOf(false), resets)
    }
    @Test fun aFailedResetIsRetriedAsAResetEvenWithOlderRows() = runTest(dispatcher) {
        history.value = WalletHistoryStatus(loaded = true, retryAppend = false, owner = payouts.captureSession())
        model.retryTransactionHistory(); runCurrent(); assertEquals(listOf(true), resets)
    }
    @Test fun anOldVisitsAppendErrorCannotSkipTheNewVisitsFirstPage() = runTest(dispatcher) {
        history.value = WalletHistoryStatus(retryAppend = true, owner = payouts.captureSession())
        guard.invalidate { }
        model.retryTransactionHistory(); runCurrent(); assertEquals(listOf(true), resets)
    }
}
