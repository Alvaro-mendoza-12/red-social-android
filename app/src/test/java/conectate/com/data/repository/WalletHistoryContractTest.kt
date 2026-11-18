package conectate.com.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import conectate.com.data.supabase.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class WalletHistoryContractTest {
    @Volatile private var account: String? = "a"
    private val guard = AuthSessionMutationGuard()
    private val auth = mockk<AuthService>()
    private val config = mockk<SupabaseConfig>()
    private var configured = true
    private var body = "[]"
    private var status = HttpStatusCode.OK
    private var tokenAction: suspend () -> Unit = {}
    private var reply: suspend (HttpRequestData) -> String = { body }
    private val requests = CopyOnWriteArrayList<HttpRequestData>()
    private val client = HttpClient(MockEngine { request ->
        requests += request
        respond(reply(request), status, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private lateinit var repository: WalletRepository
    @Before fun start() {
        mockkStatic(Log::class)
        every { Log.w(any(), any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { config.httpClient } returns client
        every { config.supabaseUrl } returns "https://fixture.invalid"
        every { config.supabaseAnonKey } returns "qa-anon"
        every { config.isConfigured } answers { configured }
        every { config.json } returns Json
        every { auth.currentSession } returns MutableStateFlow<AuthResponse?>(null)
        every { auth.accountVisitChanges } returns guard.accountVisitChanges
        every { auth.getCurrentUserId() } answers { account }
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        every { auth.commitForAccountLease(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
        coEvery { auth.ensureValidAccessToken() } returns Result.success("renewed")
        coEvery { auth.getAccessTokenForAccountAwait(any()) } coAnswers { tokenAction(); "token-${firstArg<String>()}" }
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        repository = WalletRepository(config, auth, context)
    }
    @After fun stop() { client.close(); unmockkStatic(Log::class) }
    private fun field(name: String): Any? {
        val getter = repository.javaClass.methods.firstOrNull { it.name.startsWith("getHistoryStatus") } ?: return null
        val value = (getter.invoke(repository) as StateFlow<*>).value ?: return null
        return value.javaClass.methods.single { it.name == "get$name" }.invoke(value)
    }
    private fun row(id: String, extra: String = "") = """{"id":"$id","amount_diamonds":10,"balance_after":90$extra}"""
    private fun page(start: Int, size: Int = 51) = (start until start + size).joinToString(",", "[", "]") { row("tx-$it") }
    private suspend fun prime() { body = page(0); assertTrue(repository.fetchTransactionHistory().isSuccess) }
    private fun switch() { guard.invalidate { account = "b" }; guard.invalidate { account = "a" } }
    private suspend fun expires(action: suspend () -> Unit) {
        try { action(); fail("An expired read cannot report success or dispatch HTTP") } catch (_: CancellationException) { }
    }
    private suspend fun invalid(value: String) {
        prime(); body = value
        assertTrue("Malformed history must fail: $value", repository.fetchTransactionHistory().isFailure)
        assertEquals(50, repository.transactions.value.size)
        assertNotNull("The failure must be visible", field("Error"))
    }
    @Test fun aConfirmedEmptyListIsExplicitlyLoaded() = runBlocking {
        assertTrue(repository.fetchTransactionHistory().isSuccess); assertEquals(true, field("Loaded"))
        assertEquals(false, field("Loading")); assertNull(field("Error"))
    }
    @Test fun aFailedFirstLoadIsNotAConfirmedEmptyHistory() = runBlocking {
        status = HttpStatusCode.ServiceUnavailable
        assertTrue(repository.fetchTransactionHistory().isFailure)
        assertEquals(false, field("Loaded")); assertNotNull(field("Error"))
    }
    @Test fun aFailedRefreshKeepsTheLastPageAndItsContinuation() = runBlocking {
        prime(); status = HttpStatusCode.Forbidden
        assertTrue(repository.fetchTransactionHistory().isFailure)
        assertEquals(50, repository.transactions.value.size); assertTrue(repository.transactionsHasMore.value)
        assertNotNull(field("Error")); assertEquals(true, field("Loaded"))
    }
    @Test fun blankSuccessIsNotAnEmptyLedger() = runBlocking { invalid("") }
    @Test fun nullSuccessIsNotAnEmptyLedger() = runBlocking { invalid("null") }
    @Test fun anErrorObjectIsNotALedgerRow() = runBlocking { invalid("{}") }
    @Test fun primitiveArrayEntriesCannotBeSilentlyDropped() = runBlocking { invalid("[17]") }
    @Test fun aMissingAmountCannotBecomeZero() = runBlocking { invalid("""[{"id":"bad","balance_after":0}]""") }
    @Test fun aFractionalAmountCannotBeTruncated() = runBlocking { invalid("""[{"id":"bad","amount_diamonds":1.9,"balance_after":0}]""") }
    @Test fun aMissingBalanceCannotBecomeZero() = runBlocking { invalid("""[{"id":"bad","amount_diamonds":10}]""") }
    @Test fun anotherAccountsRowCannotBeDisplayed() = runBlocking { invalid("[${row("bad", ",\"user_id\":\"b\"")}]") }
    @Test fun legacyObjectAndSignedAmountsStillWork() = runBlocking {
        body = """{"id":"legacy","amount_diamonds":-10,"balance_after_diamonds":80}"""
        assertTrue(repository.fetchTransactionHistory().isSuccess); assertEquals(-10, repository.transactions.value.single().amountDiamonds)
    }
    @Test fun debitDirectionIsReflectedInTheDisplayedAmount() = runBlocking {
        body = "[${row("debit", ",\"direction\":\"debit\"")} ]"
        assertTrue(repository.fetchTransactionHistory().isSuccess); assertEquals(-10, repository.transactions.value.single().amountDiamonds)
    }
    @Test fun aSignedOutReadIsNotSuccessful() = runBlocking {
        guard.invalidate { account = null }
        assertTrue(repository.fetchTransactionHistory().isFailure); assertTrue(requests.isEmpty())
    }
    @Test fun missingConfigurationCannotConfirmAnEmptyHistory() = runBlocking {
        configured = false
        assertTrue(repository.fetchTransactionHistory().isFailure); assertTrue(requests.isEmpty())
    }
    @Test fun credentialRenewalIsPartOfTheRead() = runBlocking {
        assertTrue(repository.fetchTransactionHistory().isSuccess)
        coVerify(exactly = 1) { auth.ensureValidAccessToken() }
    }
    @Test fun changingVisitDuringTokenLookupCannotSendTheRead() = runBlocking {
        tokenAction = { switch() }; expires { repository.fetchTransactionHistory() }; assertTrue(requests.isEmpty())
    }
    @Test fun anOldReplyCannotClaimToBeAConfirmedRead() = runBlocking {
        reply = { switch(); "[]" }; expires { repository.fetchTransactionHistory() }
    }
    @Test fun theNewestRefreshWinsEvenWhenTheOlderReplyArrivesLast() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        reply = { if (requests.size == 1) { entered.complete(Unit); release.await(); "[${row("old")}]" } else "[${row("new")}]" }
        val first = async { runCatching { repository.fetchTransactionHistory() } }
        try {
            withTimeout(5000) { entered.await() }
            assertTrue(repository.fetchTransactionHistory().isSuccess)
        } finally { release.complete(Unit); first.await() }
        assertEquals(listOf("new"), repository.transactions.value.map { it.id })
    }
    @Test fun concurrentAppendClicksDoNotFetchTheSamePageTwice() = runBlocking {
        prime(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        reply = { entered.complete(Unit); release.await(); page(50, 1) }
        val first = async { repository.fetchTransactionHistory(false) }
        var second: Deferred<Result<Unit>>? = null
        try {
            withTimeout(5000) { entered.await() }
            second = async { repository.fetchTransactionHistory(false) }
            withTimeout(5000) { while (!second.isCompleted && requests.size < 3) delay(5) }
            assertEquals("Only one page request may be in flight", 2, requests.size)
        } finally { release.complete(Unit); first.await(); second?.await() }
    }
    @Test fun duplicateIdsDoNotProduceDuplicateLazyListKeys() = runBlocking {
        body = "[${row("same")},${row("same")}]"
        assertTrue(repository.fetchTransactionHistory().isSuccess)
        assertEquals(listOf("same"), repository.transactions.value.map { it.id })
    }
    @Test fun offsetsCountConsumedRowsRatherThanDeduplicatedDisplayRows() = runBlocking {
        prime(); body = (50..100).joinToString(",", "[", "]") { row(if (it == 50) "tx-0" else "tx-$it") }
        assertTrue(repository.fetchTransactionHistory(false).isSuccess)
        body = "[]"; assertTrue(repository.fetchTransactionHistory(false).isSuccess)
        assertEquals(listOf("0", "50", "100"), requests.map { it.url.parameters["offset"] })
    }
    @Test fun aFailedAppendDoesNotAdvanceTheOffset() = runBlocking {
        prime(); status = HttpStatusCode.ServiceUnavailable
        assertTrue(repository.fetchTransactionHistory(false).isFailure)
        assertEquals(true, field("RetryAppend"))
        status = HttpStatusCode.OK; body = "[]"; assertTrue(repository.fetchTransactionHistory(false).isSuccess)
        assertEquals(listOf("0", "50", "50"), requests.map { it.url.parameters["offset"] })
    }
    @Test fun aBlockedCredentialLookupHasADeadlineAndReleasesLoading() = runTest {
        tokenAction = { awaitCancellation() }
        val operation = async { repository.fetchTransactionHistory() }
        try {
            runCurrent(); advanceTimeBy(15001); runCurrent()
            assertTrue("The deadline includes credentials", operation.isCompleted)
            assertTrue(operation.await().isFailure); assertEquals(false, field("Loading")); assertNotNull(field("Error"))
        } finally { operation.cancelAndJoin() }
    }
    @Test fun aNegativeBalanceDoesNotBecomeAReceipt() = runBlocking {
        invalid("""[{"id":"bad","amount_diamonds":10,"balance_after":-1}]""")
    }
    @Test fun retryingAfterAFailureClearsTheErrorOnlyWithAConfirmedRead() = runBlocking {
        status = HttpStatusCode.ServiceUnavailable
        assertTrue(repository.fetchTransactionHistory().isFailure)
        assertNotNull(field("Error"))
        status = HttpStatusCode.OK
        assertTrue(repository.fetchTransactionHistory().isSuccess)
        assertNull(field("Error")); assertEquals(true, field("Loaded"))
    }
    @Test fun anOldAppendCannotContaminateANewerRefresh() = runBlocking {
        prime(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        reply = { request ->
            if (request.url.parameters["offset"] == "50") { entered.complete(Unit); release.await(); "[${row("old-append")}]" }
            else "[${row("new")}]"
        }
        val first = async { runCatching { repository.fetchTransactionHistory(false) } }
        try { withTimeout(5000) { entered.await() }; assertTrue(repository.fetchTransactionHistory().isSuccess) }
        finally { release.complete(Unit); first.await() }
        assertEquals(listOf("new"), repository.transactions.value.map { it.id })
    }
    @Test fun aSupersededFailureCannotReplaceTheNewSuccess() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        reply = { if (requests.size == 1) { entered.complete(Unit); release.await(); throw java.io.IOException("old") } else "[]" }
        val first = async { runCatching { repository.fetchTransactionHistory() } }
        try { withTimeout(5000) { entered.await() }; assertTrue(repository.fetchTransactionHistory().isSuccess) }
        finally { release.complete(Unit); first.await() }
        assertNull(field("Error")); assertEquals(true, field("Loaded"))
    }
    @Test fun externalCancellationReleasesLoadingWithoutInventingAnError() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        tokenAction = { entered.complete(Unit); awaitCancellation() }
        val first = launch { repository.fetchTransactionHistory() }
        withTimeout(5000) { entered.await() }; first.cancelAndJoin()
        assertEquals(false, field("Loading")); assertNull(field("Error"))
    }
}
