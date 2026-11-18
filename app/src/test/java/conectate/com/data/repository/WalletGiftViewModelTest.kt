package conectate.com.data.repository

import androidx.lifecycle.ViewModelStore
import conectate.com.data.model.CreatorPayoutSummary
import conectate.com.data.model.CreatorProgramDashboard
import conectate.com.presentation.wallet.WalletViewModel
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real ViewModel -> real repository -> Ktor HTTP, with fictional accounts and charges. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalletGiftViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private lateinit var f: GiftHttpFixture
    private lateinit var model: WalletViewModel
    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        f = GiftHttpFixture()
        val program = mockk<CreatorProgramRepository>()
        coEvery { program.loadDashboard() } returns Result.success(CreatorProgramDashboard())
        val payouts = mockk<CreatorPayoutRepository>()
        every { payouts.captureSession() } answers { f.auth.captureAccountLease() }
        every { payouts.observeSessionVisits() } returns f.guard.accountVisitChanges.map { f.auth.captureAccountLease() }
        every { payouts.publish(any(), any()) } answers { f.guard.commitForLease(firstArg(), secondArg()) }
        coEvery { payouts.summary(any()) } returns Result.success(CreatorPayoutSummary())
        model = WalletViewModel(f.repository, program, payouts)
        store.put("wallet", model)
    }
    @After fun stop() { store.clear(); dispatcher.scheduler.runCurrent(); f.close(); Dispatchers.resetMain() }
    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5000) {
            do { dispatcher.scheduler.runCurrent(); if (condition()) break; delay(5) } while (true)
        }
    }
    private suspend fun ready() = until { !model.loading.value }
    private fun send() = model.sendGift("receiver", "live", f.gift, true)
    private suspend fun finished() = until { model.sendingGiftId.value == null }

    @Test fun aGiftQueuedByOneAccountCannotRunAsTheNextAccount() = runBlocking {
        ready(); send(); f.switchTo("other"); dispatcher.scheduler.runCurrent(); finished()
        assertTrue("The old confirmation cannot send under either visit", f.requests.isEmpty())
    }
    @Test fun aRetainedConfirmationCannotChargeTheNextAccount() = runBlocking {
        ready(); val previous = f.repository.purchaseSession()
        f.switchTo("other"); dispatcher.scheduler.runCurrent()
        assertTrue(f.repository.fetchBalance().isSuccess)
        // Exercise the old dialog call or the new call carrying its original visit.
        val scoped = model.javaClass.methods.firstOrNull { it.name.startsWith("sendGift") && it.parameterCount == 5 }
        if (scoped == null) send() else scoped.invoke(model, previous, "receiver", "live", f.gift, true)
        dispatcher.scheduler.runCurrent(); finished()
        assertTrue("An old confirmation is not consent from the next account", f.requests.isEmpty())
    }
    @Test fun changingAccountRemovesAnAlreadyRenderedReceipt() = runBlocking {
        ready(); send(); finished(); assertNotNull(model.lastReceipt.value)
        f.switchTo("other"); dispatcher.scheduler.runCurrent()
        assertNull(model.lastReceipt.value)
    }
    @Test fun signingOutRemovesTheOutgoingAccountsReceipt() = runBlocking {
        ready(); send(); finished(); assertNotNull(model.lastReceipt.value)
        f.switchTo(null); dispatcher.scheduler.runCurrent()
        assertNull(model.lastReceipt.value)
    }
    @Test fun anOldReplyCannotRestoreTheReceiptAfterReturningToTheAccount() = runBlocking {
        ready(); f.holdReply = CompletableDeferred(); send()
        until { f.entered.isCompleted }
        f.roundTrip(); dispatcher.scheduler.runCurrent(); f.holdReply!!.complete(Unit); finished()
        assertNull(model.lastReceipt.value)
        assertTrue("The unobserved confirmation retains its original retry identity", f.disk.isNotEmpty())
    }
    @Test fun anOldAccountsFailureDoesNotRemainOnTheNewAccount() = runBlocking {
        ready(); f.status = HttpStatusCode.ServiceUnavailable; send(); finished()
        assertNotNull(model.error.value)
        f.switchTo("other"); dispatcher.scheduler.runCurrent()
        assertNull(model.error.value)
    }
    @Test fun aRejectedRetryDoesNotClaimThatTheEarlierChargeNeverHappened() = runBlocking {
        ready(); f.status = HttpStatusCode.ServiceUnavailable; send(); finished()
        assertEquals(1, f.charges.get()); assertTrue(f.disk.isNotEmpty())
        f.overrideReceipt = """{"error":"GIFTS_DISABLED"}"""
        send(); finished()
        assertEquals(1, f.charges.get())
        assertFalse("A rejected retry cannot deny the earlier ambiguous charge", model.error.value.orEmpty().contains("No se descontaron"))
    }
    @Test fun repeatedTapsStillSendOnlyOneGift() = runBlocking {
        ready(); f.holdReply = CompletableDeferred(); send(); send()
        until { f.entered.isCompleted }; assertEquals(1, f.requests.size)
        f.holdReply!!.complete(Unit); finished(); assertEquals(1, f.charges.get())
    }
    @Test fun confirmedGiftsCanStillBeConsumedAndFollowedByANewIntent() = runBlocking {
        ready(); send(); finished(); val first = model.lastReceipt.value
        assertNotNull(first); model.consumeReceipt(); assertNull(model.lastReceipt.value)
        send(); finished()
        assertNotEquals(first!!.transactionId, model.lastReceipt.value!!.transactionId)
        assertEquals(2, f.charges.get())
    }
    @Test fun credentialRenewalCannotKeepTheGiftButtonBusyIndefinitely() = runBlocking {
        ready()
        val tokenEntered = CompletableDeferred<Unit>()
        val blocked: suspend () -> Unit = { tokenEntered.complete(Unit); awaitCancellation() }
        f.onToken = blocked; f.onRenew = blocked
        send(); until { tokenEntered.isCompleted }
        dispatcher.scheduler.advanceTimeBy(45001); dispatcher.scheduler.runCurrent()
        assertNull("The operation needs a deadline including credential renewal", model.sendingGiftId.value)
        assertNotNull(model.error.value); assertTrue(f.requests.isEmpty())
    }
}
