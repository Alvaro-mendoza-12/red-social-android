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

class WalletBalanceConfirmationTest {
    @Volatile private var account: String? = "a"
    private val guard = AuthSessionMutationGuard()
    private val auth = mockk<AuthService>()
    private val config = mockk<SupabaseConfig>()
    private var rpcStatus = HttpStatusCode.OK
    private var restStatus = HttpStatusCode.OK
    private var rpcBody = """{"user_id":"a","balance_diamonds":75,"balance_debt_diamonds":9}"""
    private var restBody = "[]"
    private var configured = true
    private var duringToken: (() -> Unit)? = null
    private var duringReply: (() -> Unit)? = null
    private val requests = AtomicInteger()
    private val client = HttpClient(MockEngine { request ->
        requests.incrementAndGet()
        duringReply?.invoke()
        if (request.url.encodedPath.endsWith("wallet_get_or_create"))
            respond(rpcBody, rpcStatus, headersOf(HttpHeaders.ContentType, "application/json"))
        else respond(restBody, restStatus, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json() } }
    private lateinit var repository: WalletRepository

    @Before fun start() {
        mockkStatic(Log::class)
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { config.httpClient } returns client
        every { config.supabaseUrl } returns "https://fixture.invalid"
        every { config.supabaseAnonKey } returns "qa-anon"
        every { config.isConfigured } answers { configured }
        every { config.json } returns Json { ignoreUnknownKeys = true }
        every { auth.currentSession } returns MutableStateFlow<AuthResponse?>(null)
        every { auth.accountVisitChanges } returns guard.accountVisitChanges
        every { auth.getCurrentUserId() } answers { account }
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        every { auth.commitForAccountLease(any(), any()) } answers { guard.commitForLease(firstArg(), secondArg()) }
        coEvery { auth.ensureValidAccessToken() } returns Result.success("token-a")
        coEvery { auth.getAccessTokenForAccountAwait(any()) } coAnswers { duringToken?.invoke(); "token-a" }
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns mockk<SharedPreferences>(relaxed = true)
        repository = WalletRepository(config, auth, context)
    }
    @After fun close() { client.close(); unmockkStatic(Log::class) }
    private suspend fun prime() {
        assertTrue(repository.fetchBalance().isSuccess)
        assertEquals(75, repository.coinBalance.value)
        assertEquals(9, repository.coinDebt.value)
    }
    private fun roundTrip() { guard.invalidate { account = "b" }; guard.invalidate { account = "a" } }

    @Test fun aConfirmedBalanceAndDebtArePublished() = runBlocking { prime() }
    @Test fun twoFailedEndpointsPreserveTheLastConfirmedBalanceAndDebt() = runBlocking {
        prime(); rpcStatus = HttpStatusCode.ServiceUnavailable; restStatus = HttpStatusCode.ServiceUnavailable
        val result = repository.fetchBalance()
        assertTrue("A failed load is not a confirmed zero", result.isFailure)
        assertEquals(75, repository.coinBalance.value); assertEquals(9, repository.coinDebt.value)
    }
    @Test fun anExpiredSessionCannotTurnTheWalletIntoAZeroBalance() = runBlocking {
        prime(); rpcStatus = HttpStatusCode.Unauthorized; restStatus = HttpStatusCode.Unauthorized
        assertTrue(repository.fetchBalance().isFailure)
        assertEquals(75, repository.coinBalance.value); assertEquals(9, repository.coinDebt.value)
    }
    @Test fun missingRpcCanUseAConfirmedLegacyWallet() = runBlocking {
        rpcStatus = HttpStatusCode.NotFound
        restBody = """[{"user_id":"a","balance_diamonds":42,"balance_debt_diamonds":3}]"""
        assertTrue(repository.fetchBalance().isSuccess)
        assertEquals(42, repository.coinBalance.value); assertEquals(3, repository.coinDebt.value)
    }
    @Test fun aConfirmedEmptyLegacyWalletCanStillHaveZeroBalance() = runBlocking {
        rpcStatus = HttpStatusCode.NotFound
        assertTrue(repository.fetchBalance().isSuccess)
        assertEquals(0, repository.coinBalance.value); assertEquals(0, repository.coinDebt.value)
    }
    @Test fun anInvalidSuccessfulBodyDoesNotEraseTheConfirmedSnapshot() = runBlocking {
        prime(); rpcBody = "{}"; restBody = "{}"
        assertTrue(repository.fetchBalance().isFailure)
        assertEquals(75, repository.coinBalance.value); assertEquals(9, repository.coinDebt.value)
    }
    @Test fun missingConfigurationIsNotAConfirmedRefresh() = runBlocking {
        configured = false
        assertTrue(repository.fetchBalance().isFailure); assertEquals(0, requests.get())
    }
    @Test fun signingOutDoesNotReturnASuccessfulBalanceLoad() = runBlocking {
        guard.invalidate { account = null }
        assertTrue(repository.fetchBalance().isFailure); assertEquals(0, requests.get())
    }
    @Test fun aChangedVisitDuringTokenLookupCannotSendTheWalletRequest() = runBlocking {
        duringToken = ::roundTrip
        try { repository.fetchBalance(); fail("The initiating visit expired") } catch (_: CancellationException) { }
        assertEquals(0, requests.get())
    }
    @Test fun aReplyFromThePreviousVisitCannotConfirmARefresh() = runBlocking {
        duringReply = ::roundTrip
        try { repository.fetchBalance(); fail("The previous visit cannot confirm this refresh") } catch (_: CancellationException) { }
    }
}
