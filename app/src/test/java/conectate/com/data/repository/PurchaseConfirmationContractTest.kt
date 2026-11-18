package conectate.com.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import conectate.com.data.supabase.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PurchaseConfirmationContractTest {
    @Volatile private var account: String? = "a"
    private val guard = AuthSessionMutationGuard()
    private val auth = mockk<AuthService>()
    private val config = mockk<SupabaseConfig>()
    private var configured = true
    private var body = """{"status":"credited","credited":true,"coins":10,"balance_diamonds":85}"""
    private var status = HttpStatusCode.OK
    private var onToken: () -> Unit = {}
    private var onReply: () -> Unit = {}
    private val requests = AtomicInteger()
    private val client = HttpClient(MockEngine {
        if (it.url.encodedPath.endsWith("wallet_get_or_create")) return@MockEngine respond("""{"user_id":"a","balance_diamonds":75,"balance_debt_diamonds":0}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        requests.incrementAndGet(); onReply()
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json() } }
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
        every { auth.getCurrentUserId() } answers { account }
        every { auth.getAccessToken() } answers { "token-$account" }
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        every { auth.commitForAccountLease(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
        coEvery { auth.ensureValidAccessToken() } returns Result.success("renewed")
        coEvery { auth.getAccessTokenForAccountAwait(any()) } coAnswers { onToken(); "token-${firstArg<String>()}" }
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        repository = WalletRepository(config, auth, context)
    }
    @After fun stop() { client.close(); unmockkStatic(Log::class) }
    private fun switch() { guard.invalidate { account = "b" }; guard.invalidate { account = "a" } }
    private suspend fun prime() { assertTrue(repository.fetchBalance().isSuccess); assertEquals(75,repository.coinBalance.value) }
    private suspend fun send() = repository.verifyGooglePlayPurchase("coins","fictional-token",auth.captureAccountLease())
    private suspend fun invalid(value: String) { prime(); body=value; assertTrue(send().isFailure); assertEquals(75,repository.coinBalance.value) }
    @Test fun aCompleteConfirmationStillPublishesTheNewBalance() = runBlocking {
        prime(); assertEquals("credited",send().getOrThrow().status); assertEquals(85,repository.coinBalance.value)
    }
    @Test fun aConfirmedReplayCanHaveZeroNewCoins() = runBlocking {
        prime(); body="""{"status":"credited","credited":false,"coins":0,"balance_diamonds":85}"""
        assertFalse(send().getOrThrow().credited); assertEquals(85,repository.coinBalance.value)
    }
    @Test fun aPendingPurchaseDoesNotRequireOrPublishABalance() = runBlocking {
        prime(); body="""{"status":"pending","credited":false,"coins":0}"""
        assertEquals("pending",send().getOrThrow().status); assertEquals(75,repository.coinBalance.value)
    }
    @Test fun aMissingBalanceCannotBecomeZero() = runBlocking { invalid("""{"status":"credited","credited":true,"coins":10}""") }
    @Test fun aNegativeBalanceCannotBecomeZero() = runBlocking { invalid("""{"status":"credited","credited":true,"coins":10,"balance_diamonds":-1}""") }
    @Test fun aMissingCreditFlagCannotBecomeAConfirmedReplay() = runBlocking { invalid("""{"status":"credited","coins":10,"balance_diamonds":85}""") }
    @Test fun missingCoinsCannotBecomeAConfirmedCredit() = runBlocking { invalid("""{"status":"credited","credited":true,"balance_diamonds":85}""") }
    @Test fun negativeCoinsCannotBecomeAConfirmedCredit() = runBlocking { invalid("""{"status":"credited","credited":true,"coins":-1,"balance_diamonds":85}""") }
    @Test fun unknownStatusCannotClaimSuccessfulVerification() = runBlocking { invalid("""{"status":"unrecognized","credited":false,"coins":0}""") }
    @Test fun aPendingResponseCannotClaimToCreditCoins() = runBlocking { invalid("""{"status":"pending","credited":true,"coins":10}""") }
    @Test fun anExpiredVisitAfterTheReplyCannotReturnAReceipt() = runBlocking {
        prime(); onReply=::switch
        try { send(); fail("Old visit cannot confirm purchase") } catch (_: CancellationException) { }
        assertEquals(75,repository.coinBalance.value)
    }
    @Test fun aChangedVisitDuringTokenLookupCannotSubmitTheToken() = runBlocking {
        prime(); onToken=::switch
        try { send(); fail("Old visit cannot submit token") } catch (_: CancellationException) { }
        assertEquals(0,requests.get())
    }
    @Test fun purchaseVerificationRenewsCredentials() = runBlocking {
        prime(); clearMocks(auth,answers=false,recordedCalls=true,childMocks=false,verificationMarks=true,exclusionRules=true)
        assertTrue(send().isSuccess); coVerify(exactly=1) { auth.ensureValidAccessToken() }
    }
}
