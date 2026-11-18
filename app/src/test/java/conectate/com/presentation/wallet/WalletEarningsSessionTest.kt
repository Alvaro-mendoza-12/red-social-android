package conectate.com.presentation.wallet

import androidx.lifecycle.ViewModelStore
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
class WalletEarningsSessionTest {
    private val dispatcher=StandardTestDispatcher()
    private val store=ViewModelStore()
    private val guard=AuthSessionMutationGuard()
    private var account: String?="a"
    private val program=mockk<CreatorProgramRepository>()
    private val payouts=mockk<CreatorPayoutRepository>()
    private lateinit var model: WalletViewModel
    private var calls=java.util.concurrent.atomic.AtomicInteger()
    private val started=CompletableDeferred<Unit>()
    private var response: suspend (Int)->Result<CreatorProgramDashboard> = { Result.success(dashboard(account?:"anonymous")) }
    private fun lease()=guard.captureAccountLease { account }
    private fun dashboard(owner:String)=CreatorProgramDashboard(payoutAccount=CreatorPayoutAccount(owner,"paypal","$owner@example.invalid"))
    @Before fun start() {
        Dispatchers.setMain(dispatcher)
        coEvery { program.loadDashboard() } coAnswers { val n=calls.incrementAndGet(); started.complete(Unit); response(n) }
        every { payouts.captureSession() } answers { lease() }
        every { payouts.observeSessionVisits() } returns guard.accountVisitChanges.map { lease() }
        every { payouts.publish(any(),any()) } answers { guard.commitForLease(firstArg(),secondArg()) }
        coEvery { payouts.summary(any()) } returns Result.success(CreatorPayoutSummary())
        coEvery { payouts.updateAccount(any(),any(),any()) } returns Result.success(CreatorPayoutAccountResponse(CreatorPayoutAccountSummary()))
        coEvery { payouts.request(any(),any()) } returns Result.success(CreatorPayoutRequestResponse(CreatorPayoutRequest("request",100,"USD","PENDING")))
        coEvery { payouts.cancel(any(),any()) } returns Result.success(CreatorPayoutRequestResponse(CreatorPayoutRequest("request",100,"USD","CANCELLED")))
    }
    private fun create() {
        val wallet=mockk<WalletRepository>()
        every { wallet.coinBalance } returns MutableStateFlow(0)
        every { wallet.coinDebt } returns MutableStateFlow(0)
        every { wallet.availableGifts } returns MutableStateFlow(emptyList<VirtualGift>())
        every { wallet.transactions } returns MutableStateFlow(emptyList<WalletTransaction>())
        every { wallet.transactionsHasMore } returns MutableStateFlow(false)
        coEvery { wallet.fetchBalance() } coAnswers { awaitCancellation() }
        model=WalletViewModel(wallet,program,payouts);store.put("wallet",model)
    }
    @After fun stop() { store.clear();dispatcher.scheduler.runCurrent();Dispatchers.resetMain() }
    private suspend fun until(condition:()->Boolean) {
        withTimeout(5000) { do { dispatcher.scheduler.runCurrent();if(condition())break;delay(5) } while(true) }
    }
    private suspend fun ready()=until { started.isCompleted&&!model.earningsLoading.value }
    private fun switch(value:String?) { guard.invalidate { account=value };dispatcher.scheduler.runCurrent() }

    @Test fun aConfirmedDashboardIsDisplayed()=runBlocking {
        create();ready();assertEquals("a@example.invalid",model.creatorDashboard.value.payoutAccount?.externalAccountId)
    }
    @Test fun changingAccountClearsAndReloadsThePrivateDashboard()=runBlocking {
        create();ready();switch("b")
        until { !model.earningsLoading.value }
        assertEquals("b@example.invalid",model.creatorDashboard.value.payoutAccount?.externalAccountId)
    }
    @Test fun signingOutRemovesThePrivateAccountReference()=runBlocking {
        create();ready();switch(null);assertNull(model.creatorDashboard.value.payoutAccount)
    }
    @Test fun anOldNonCancellableReplyCannotRestoreThePreviousVisit()=runBlocking {
        val first=CompletableDeferred<Result<CreatorProgramDashboard>>()
        response={ n-> if(n==1)withContext(NonCancellable){first.await()} else Result.success(dashboard(account?:"anonymous")) }
        create();until { started.isCompleted }
        try {
            switch("b");switch("a");model.refreshEarnings();dispatcher.scheduler.runCurrent()
            first.complete(Result.success(dashboard("old-a")));ready()
            assertEquals("a@example.invalid",model.creatorDashboard.value.payoutAccount?.externalAccountId)
        } finally { first.complete(Result.success(dashboard("released"))) }
    }
    @Test fun anOldFailureCannotReplaceTheNewAccountsDashboard()=runBlocking {
        val first=CompletableDeferred<Result<CreatorProgramDashboard>>()
        response={ n-> if(n==1)withContext(NonCancellable){first.await()} else Result.success(dashboard(account?:"anonymous")) }
        create();until { started.isCompleted }
        try {
            switch("b");model.refreshEarnings();dispatcher.scheduler.runCurrent()
            first.complete(Result.failure(java.io.IOException("old-a-offline")));ready()
            assertEquals("b@example.invalid",model.creatorDashboard.value.payoutAccount?.externalAccountId)
            assertNull(model.earningsError.value)
        } finally { first.complete(Result.success(dashboard("released"))) }
    }
    @Test fun cancellationAlsoReleasesTheProgressControl()=runBlocking {
        response={awaitCancellation()};create();until {started.isCompleted}
        store.clear();dispatcher.scheduler.runCurrent();assertFalse(model.earningsLoading.value)
    }
    @Test fun anUnresponsiveDashboardHasALoadingDeadline()=runBlocking {
        response={awaitCancellation()};create();until { started.isCompleted }
        dispatcher.scheduler.advanceTimeBy(15001);dispatcher.scheduler.runCurrent()
        assertFalse(model.earningsLoading.value);assertNotNull(model.earningsError.value)
    }
    @Test fun retryingAfterAFailureCanDisplayAConfirmedDashboard()=runBlocking {
        response={Result.failure(java.io.IOException("offline"))};create();ready();assertNotNull(model.earningsError.value)
        response={Result.success(dashboard("recovered"))};model.refreshEarnings();ready()
        assertEquals("recovered@example.invalid",model.creatorDashboard.value.payoutAccount?.externalAccountId)
        assertNull(model.earningsError.value)
    }
    private suspend fun oldConfirmation(name:String,args:Array<Any>) {
        create();ready();val previous=lease();switch("b")
        val scoped=model.javaClass.methods.firstOrNull { it.name.startsWith(name)&&it.parameterCount==args.size+1 }
        if(scoped==null) model.javaClass.methods.single { it.name==name&&it.parameterCount==args.size }.invoke(model,*args)
        else scoped.invoke(model,previous,*args)
        dispatcher.scheduler.runCurrent()
    }
    @Test fun anOldAccountFormCannotConfigureTheNextAccountsPayout()=runBlocking {
        oldConfirmation("configurePayoutAccount",arrayOf("old-a@example.invalid","USD"))
        coVerify(exactly=0){payouts.updateAccount(any(),any(),any())}
    }
    @Test fun anOldWithdrawalFormCannotRequestTheNextAccountsMoney()=runBlocking {
        oldConfirmation("requestCreatorPayout",arrayOf(100L))
        coVerify(exactly=0){payouts.request(any(),any())}
    }
    @Test fun anOldCancelButtonCannotSendUnderTheNextAccount()=runBlocking {
        oldConfirmation("cancelCreatorPayout",arrayOf("old-a-request"))
        coVerify(exactly=0){payouts.cancel(any(),any())}
    }
    @Test fun aValidNewVisitRequestIsNotCancelledByItsDelayedSessionObserver()=runBlocking {
        create();ready()
        guard.invalidate { account="b" }
        model.requestCreatorPayout(100L)
        dispatcher.scheduler.runCurrent()
        coVerify(exactly=1){payouts.request(any(),100L)}
    }

}
