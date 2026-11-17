package conectate.com.presentation.wallet

import androidx.lifecycle.ViewModelStore
import conectate.com.data.model.*
import conectate.com.data.repository.CreatorPayoutRepository
import conectate.com.data.repository.CreatorProgramRepository
import conectate.com.data.repository.WalletRepository
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The real WalletViewModel consumes controlled payout replies; no purchase or payout is sent. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletPayoutStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val guard = AuthSessionMutationGuard()
    private var account = "a"
    private val visits = MutableSharedFlow<AuthSessionLease>(extraBufferCapacity = 8)
    private val payouts = mockk<CreatorPayoutRepository>()
    private lateinit var model: WalletViewModel

    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        val wallet = mockk<WalletRepository>()
        every { wallet.coinBalance } returns MutableStateFlow(0)
        every { wallet.coinDebt } returns MutableStateFlow(0)
        every { wallet.availableGifts } returns MutableStateFlow(emptyList<VirtualGift>())
        every { wallet.transactions } returns MutableStateFlow(emptyList<WalletTransaction>())
        every { wallet.transactionsHasMore } returns MutableStateFlow(false)
        coEvery { wallet.fetchBalance() } coAnswers { awaitCancellation() }
        val program = mockk<CreatorProgramRepository>()
        coEvery { program.loadDashboard() } coAnswers { awaitCancellation() }
        every { payouts.captureSession() } answers { guard.captureAccountLease { account } }
        every { payouts.observeSessionVisits() } returns visits
        every { payouts.publish(any(), any()) } answers {
            guard.commitForLease(firstArg(), secondArg())
        }
        coEvery { payouts.summary(any()) } coAnswers {
            Result.success(CreatorPayoutSummary(currency = if (firstArg<AuthSessionLease>().accountId == "a") "USD" else "EUR"))
        }
        model = WalletViewModel(wallet, program, payouts)
        store.put("wallet", model)
    }

    @After fun stop() { store.clear(); Dispatchers.resetMain() }

    @Test fun confirmedAccountUpdateIsStillIdentifiedWhenItsSummaryReloadFails() = runTest(dispatcher) {
        runCurrent()
        coEvery { payouts.updateAccount(any(), "qa@example.invalid", "USD") } returns
            Result.success(CreatorPayoutAccountResponse(CreatorPayoutAccountSummary()))
        coEvery { payouts.summary(any()) } returns Result.failure(Exception("reload offline"))
        model.configurePayoutAccount("qa@example.invalid", "USD"); runCurrent()
        assertTrue(model.payoutMessage.value.orEmpty().contains("confirm", ignoreCase = true))
        assertFalse(model.payoutLoading.value)
    }

    @Test fun repeatedPayoutClicksBeforeDispatchReachTheRepositoryOnlyOnce() = runTest(dispatcher) {
        runCurrent()
        val reply = CompletableDeferred<Result<CreatorPayoutRequestResponse>>()
        coEvery { payouts.request(any(), 100) } coAnswers { reply.await() }
        model.requestCreatorPayout(100); model.requestCreatorPayout(100)
        assertTrue(model.payoutLoading.value)
        runCurrent()
        coVerify(exactly = 1) { payouts.request(any(), 100) }
        reply.complete(Result.failure(Exception("offline"))); runCurrent()
        assertFalse(model.payoutLoading.value)
    }

    @Test fun changingAccountBeforeDispatchPreventsTheQueuedPayout() = runTest(dispatcher) {
        runCurrent()
        model.requestCreatorPayout(100)
        guard.invalidate { account = "b" }
        visits.emit(payouts.captureSession()); runCurrent()
        coVerify(exactly = 0) { payouts.request(any(), any()) }
        assertFalse(model.payoutLoading.value)
        assertEquals("EUR", model.payoutSummary.value?.currency)
    }

    @Test fun aThrownFailureReleasesTheLoadingControl() = runTest(dispatcher) {
        runCurrent()
        coEvery { payouts.cancel(any(), "request") } throws java.io.IOException("offline")
        model.cancelCreatorPayout("request"); runCurrent()
        assertFalse(model.payoutLoading.value)
        assertNotNull(model.payoutMessage.value)
    }

    @Test fun anOldNonCancellableResponseCannotRestoreItsSummaryOrMessage() = runTest(dispatcher) {
        runCurrent()
        val reply = CompletableDeferred<Result<CreatorPayoutAccountResponse>>()
        coEvery { payouts.updateAccount(any(), any(), any()) } coAnswers {
            withContext(NonCancellable) { reply.await() }
        }
        model.configurePayoutAccount("qa@example.invalid", "USD"); runCurrent()
        guard.invalidate { account = "b" }; visits.emit(payouts.captureSession()); runCurrent()
        reply.complete(Result.success(CreatorPayoutAccountResponse(CreatorPayoutAccountSummary()))); runCurrent()
        assertEquals("EUR", model.payoutSummary.value?.currency)
        assertNull(model.payoutMessage.value)
        assertFalse(model.payoutLoading.value)
    }
}
