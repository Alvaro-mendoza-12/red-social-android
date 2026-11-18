package conectate.com.data.repository.message

import conectate.com.data.supabase.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MessageReportResponseTest(private val operation: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun operations() = listOf("update", "delete", "mine", "all")
    }
    private var account = "a"
    private val guard = AuthSessionMutationGuard()
    private val auth = mockk<AuthService>()
    private var status = HttpStatusCode.OK
    private var body = "[{\"id\":\"report\",\"status\":\"revisado\"}]"
    private var pending: CompletableDeferred<Unit>? = null
    private val sent = mutableListOf<String>()
    private val client = HttpClient(MockEngine { request ->
        sent += request.headers[HttpHeaders.Authorization].orEmpty()
        pending?.await()
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    private val config = mockk<SupabaseConfig>()
    private val store: MessageReportStore
    init {
        every { config.httpClient } returns client
        every { config.supabaseUrl } returns "https://fixture.invalid"
        every { config.supabaseAnonKey } returns "qa-anon"
        every { auth.getAccessToken() } answers { "token-$account" }
        every { auth.getCurrentUserId() } answers { account }
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        coEvery { auth.ensureValidAccessToken() } returns Result.success("token-a")
        coEvery { auth.getAccessTokenForAccountAwait(any()) } coAnswers { "token-$account" }
        store = MessageReportStore(mockk(), config, auth, mockk(), mockk(), mockk(), mockk(), mockk(relaxed = true), mockk())
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
    }
    @After fun close() { client.close(); unmockkStatic(android.util.Log::class) }
    private suspend fun call(): Result<*> = when (operation) {
        "update" -> store.updateReportStatus("report", "revisado")
        "delete" -> store.deleteReport("report")
        "mine" -> store.getMyReports()
        else -> store.getAllReports()
    }
    @Test fun aForbiddenResponseCannotBeReportedAsSuccess() = runTest {
        status = HttpStatusCode.Forbidden; body = "{\"error\":\"DENIED\"}"
        assertTrue(call().isFailure)
    }
    @Test fun aServerFailureCannotBeReportedAsSuccess() = runTest {
        status = HttpStatusCode.ServiceUnavailable; body = "{\"error\":\"TEMPORARY\"}"
        assertTrue(call().isFailure)
    }
    @Test fun confirmedServerResponseIsStillSuccessful() = runTest { assertTrue(call().isSuccess) }
    @Test fun emptyRowsConfirmAnEmptyReadButNotAMutation() = runTest {
        body = "[]"
        assertEquals(operation == "mine" || operation == "all", call().isSuccess)
    }
    @Test fun anotherReportsRowDoesNotConfirmTheRequestedMutation() = runTest {
        body = "[{\"id\":\"another-report\",\"status\":\"revisado\"}]"
        assertEquals(operation == "mine" || operation == "all", call().isSuccess)
    }
    @Test fun aDifferentStatusDoesNotConfirmTheRequestedUpdate() = runTest {
        body = "[{\"id\":\"report\",\"status\":\"pendiente\"}]"
        assertEquals(operation != "update", call().isSuccess)
    }
    @Test fun malformedDataCannotBeReportedAsSuccess() = runTest {
        body = "not-json"
        assertTrue(call().isFailure)
    }
    @Test fun aChangedVisitDuringCredentialRenewalCannotSendAnAuthenticatedRequest() = runTest {
        coEvery { auth.ensureValidAccessToken() } coAnswers {
            guard.invalidate { account = "b" }; guard.invalidate { account = "a" }
            Result.success("token-a")
        }
        try { call(); fail("Credential renewal lost its initiating visit") } catch (_: CancellationException) { }
        assertTrue("No HTTP request may be sent after the visit changes", sent.isEmpty())
    }
    @Test fun aChangedVisitDuringTokenLookupCannotSendAnAuthenticatedRequest() = runTest {
        coEvery { auth.getAccessTokenForAccountAwait(any()) } coAnswers {
            guard.invalidate { account = "b" }; guard.invalidate { account = "a" }
            "token-a"
        }
        try { call(); fail("Token lookup lost its initiating visit") } catch (_: CancellationException) { }
        assertTrue(sent.isEmpty())
    }
    @Test fun cancellationEscapesInsteadOfBecomingAnOrdinaryFailure() = runTest {
        pending = CompletableDeferred()
        val task = async { call() }
        runCurrent(); task.cancel()
        try { task.await(); fail("Cancellation must escape") } catch (_: CancellationException) { }
    }
    @Test fun anOldServerReplyDoesNotReturnToAnotherAccount() = runTest {
        pending = CompletableDeferred()
        val task = async { call() }
        runCurrent(); guard.invalidate { account = "b" }; pending!!.complete(Unit)
        try { task.await(); fail("The reply belongs to account A") } catch (_: CancellationException) { }
    }
    @Test fun aNewVisitToTheSameAccountCannotReceiveTheOldServerReply() = runTest {
        pending = CompletableDeferred()
        val task = async { call() }
        runCurrent(); guard.invalidate { account = "b" }; guard.invalidate { account = "a" }; pending!!.complete(Unit)
        try { task.await(); fail("A new visit does not own the old reply") } catch (_: CancellationException) { }
    }
}
