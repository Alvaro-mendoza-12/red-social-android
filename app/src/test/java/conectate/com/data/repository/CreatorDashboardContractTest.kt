package conectate.com.data.repository

import conectate.com.data.supabase.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(Parameterized::class)
class CreatorDashboardContractTest(private val source: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun sources() = listOf("creator_earnings", "creator_program_applications", "creator_payout_accounts")
    }
    private val guard = AuthSessionMutationGuard()
    private var account = "a"
    private val auth = mockk<AuthService>()
    private val config = mockk<SupabaseConfig>()
    private var badStatus = HttpStatusCode.OK
    private var badBody: String? = null
    private var populated = false
    private var beforeToken: (() -> Unit)? = null
    private var duringRead: (() -> Unit)? = null
    private val reads = CopyOnWriteArrayList<String>()
    private val client = HttpClient(MockEngine { request ->
        val name = request.url.pathSegments.last()
        reads += name
        if (name == source) duringRead?.invoke()
        val body = if (name == source && badBody != null) badBody!! else if (populated) when (name) {
            "creator_earnings" -> """[{"id":"earning","creator_id":"a","status":"available","amount_diamonds":25,"net_amount_credits":3.5}]"""
            "creator_program_applications" -> """[{"id":"app","user_id":"a","status":"approved"}]"""
            else -> """[{"user_id":"a","provider":"paypal","external_account_id":"a@example.invalid","currency":"USD"}]"""
        } else "[]"
        respond(body, if (name == source) badStatus else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
    })
    private val repository: CreatorProgramRepository
    init {
        every { config.httpClient } returns client
        every { config.json } returns Json { ignoreUnknownKeys = true }
        every { config.supabaseUrl } returns "https://fixture.invalid"
        every { config.supabaseAnonKey } returns "qa-anon"
        every { config.isConfigured } returns true
        every { auth.getCurrentUserId() } answers { account }
        every { auth.getAccessToken() } answers { beforeToken?.invoke(); "token-a" }
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        coEvery { auth.ensureValidAccessToken() } returns Result.success("token-a")
        coEvery { auth.getAccessTokenForAccountAwait(any()) } coAnswers { beforeToken?.invoke(); "token-a" }
        repository = CreatorProgramRepository(config,auth)
    }
    @After fun close() { client.close() }
    private fun roundTrip() { guard.invalidate { account="b" }; guard.invalidate { account="a" } }
    private suspend fun expired() {
        try { repository.loadDashboard(); fail("The response belongs to a previous visit") } catch (_: CancellationException) { }
    }
    @Test fun aServerFailureCannotLookLikeAConfirmedEmptySection() = runBlocking {
        badStatus=HttpStatusCode.ServiceUnavailable
        assertTrue(repository.loadDashboard().isFailure)
    }
    @Test fun aPermissionFailureCannotLookLikeAConfirmedEmptySection() = runBlocking {
        badStatus=HttpStatusCode.Forbidden
        assertTrue(repository.loadDashboard().isFailure)
    }
    @Test fun aSuccessfulEmptyBodyDoesNotConfirmAnEmptyArray() = runBlocking {
        badBody=""
        assertTrue(repository.loadDashboard().isFailure)
    }
    @Test fun aVisitChangedDuringTokenLookupCannotSendTheQueries() = runBlocking {
        beforeToken=::roundTrip; expired(); assertTrue(reads.isEmpty())
    }
    @Test fun aVisitChangedDuringAQueryCannotReturnPrivateResults() = runBlocking {
        populated=true; duringRead=::roundTrip; expired()
    }
    @Test fun confirmedEmptySectionsRemainSupported() = runBlocking {
        val dashboard=repository.loadDashboard().getOrThrow()
        assertTrue(dashboard.activity.isEmpty()); assertNull(dashboard.application); assertNull(dashboard.payoutAccount)
    }
    @Test fun confirmedEarningsAndAccountStillLoadTogether() = runBlocking {
        populated=true
        val dashboard=repository.loadDashboard().getOrThrow()
        assertEquals(BigDecimal("3.5"),dashboard.earnings.availableCredits)
        assertEquals("a@example.invalid",dashboard.payoutAccount?.externalAccountId)
        assertEquals("approved",dashboard.application?.status)
    }
}
