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

class BillingCapabilitiesContractTest {
    @Volatile private var account: String? = "a"
    private val guard = AuthSessionMutationGuard()
    private val auth = mockk<AuthService>()
    private val config = mockk<SupabaseConfig>()
    private var configured = true
    private var body = """{"enabled":true,"provider":"google_play","products":[{"product_id":"coins","coins":10}]}"""
    private var status = HttpStatusCode.OK
    private var onToken: () -> Unit = {}
    private var onReply: () -> Unit = {}
    private val requests = AtomicInteger()
    private val client = HttpClient(MockEngine {
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
    private suspend fun expires() {
        try { repository.fetchBillingCapabilities(); fail("An expired visit cannot return a current catalog") } catch (_: CancellationException) { }
    }
    @Test fun aConfirmedCatalogStillLoads() = runBlocking {
        val result = repository.fetchBillingCapabilities().getOrThrow()
        assertTrue(result.enabled); assertEquals(10, result.products.single().coins)
    }
    @Test fun aConfirmedDisabledStoreStillWorksWithoutProducts() = runBlocking {
        body = """{"enabled":false}"""
        assertFalse(repository.fetchBillingCapabilities().getOrThrow().enabled)
    }
    @Test fun missingConfigurationIsNotConfirmedUnavailability() = runBlocking {
        configured = false; assertTrue(repository.fetchBillingCapabilities().isFailure); assertEquals(0, requests.get())
    }
    @Test fun aSignedOutReadDoesNotUseAnOldBearer() = runBlocking {
        guard.invalidate { account = null }
        assertTrue(repository.fetchBillingCapabilities().isFailure); assertEquals(0, requests.get())
    }
    @Test fun blankSuccessIsNotConfirmedUnavailability() = runBlocking {
        body = ""; assertTrue(repository.fetchBillingCapabilities().isFailure)
    }
    @Test fun anErrorObjectIsNotConfirmedUnavailability() = runBlocking {
        body = "{}"; assertTrue(repository.fetchBillingCapabilities().isFailure)
    }
    @Test fun malformedEnabledCannotBecomeADisabledStore() = runBlocking {
        body = """{"enabled":"unknown"}"""; assertTrue(repository.fetchBillingCapabilities().isFailure)
    }
    @Test fun anEnabledStoreNeedsAProductList() = runBlocking {
        body = """{"enabled":true}"""; assertTrue(repository.fetchBillingCapabilities().isFailure)
    }
    @Test fun invalidCoinAmountsCannotDisappearSilently() = runBlocking {
        body = """{"enabled":true,"products":[{"product_id":"coins","coins":-10}]}"""
        assertTrue(repository.fetchBillingCapabilities().isFailure)
    }
    @Test fun duplicateProductIdsCannotCreateDuplicateUiKeys() = runBlocking {
        body = """{"enabled":true,"products":[{"product_id":"coins","coins":10},{"product_id":"coins","coins":20}]}"""
        assertTrue(repository.fetchBillingCapabilities().isFailure)
    }
    @Test fun aServerFailureIsNotAConfirmedDisabledStore() = runBlocking {
        status = HttpStatusCode.ServiceUnavailable
        assertTrue(repository.fetchBillingCapabilities().isFailure)
    }
    @Test fun credentialsAreRenewedBeforeTheRequest() = runBlocking {
        assertTrue(repository.fetchBillingCapabilities().isSuccess)
        coVerify(exactly = 1) { auth.ensureValidAccessToken() }
    }
    @Test fun changingVisitDuringTokenLookupCannotSendTheRequest() = runBlocking {
        onToken = ::switch; expires(); assertEquals(0, requests.get())
    }
    @Test fun anOldReplyCannotReturnThePreviousVisitsProducts() = runBlocking {
        onReply = ::switch; expires()
    }
}
