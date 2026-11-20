package conectate.com.data.repository

import android.app.Application
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.data.supabase.SupabaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class CallControlAccountTest {
    private val guard = AuthSessionMutationGuard()
    @Volatile private var accountId = "account-a"
    private val owner = guard.captureAccountLease { accountId }
    private val auth = mockk<AuthService>()
    private val config = mockk<SupabaseConfig>()
    private val tokens = mutableListOf<String?>()
    private val paths = mutableListOf<String>()
    private var onToken: () -> Unit = {}
    private var onResponse: () -> Unit = {}
    private var status = HttpStatusCode.OK
    private var response = "{}"
    private val client = HttpClient(MockEngine { request ->
        tokens += request.headers[HttpHeaders.Authorization]
        paths += request.url.encodedPath
        onResponse()
        respond(response, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json() } }
    private val control = CallControlClient(config, auth)

    init {
        every { config.httpClient } returns client
        coEvery { auth.getAccessTokenAwait() } coAnswers {
            onToken()
            "token-$accountId"
        }
        coEvery { auth.getAccessTokenForAccountAwait("account-a") } coAnswers {
            onToken()
            "token-$accountId"
        }
    }

    @After fun close() = client.close()

    private fun switchTo(id: String) = guard.invalidate { accountId = id }

    @Test fun prepareCannotUseTheNextAccountsToken() = runBlocking {
        onToken = { switchTo("account-b") }
        val result = runCatching { control.prepare(owner, "call", "group", "room", listOf("peer")) }

        assertEquals(emptyList<String>(), tokens)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun acceptingAfterReturningToTheSameAccountRequiresANewAction() = runBlocking {
        onToken = { switchTo("account-b"); switchTo("account-a") }
        val result = runCatching { control.accept(owner, "call") }

        assertEquals(emptyList<String>(), tokens)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun aDeferredLeaveCannotUseTheNewSession() = runBlocking {
        switchTo("account-b")
        val result = runCatching { control.leave(owner, "call") }

        assertEquals(emptyList<String>(), tokens)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun aLateAcceptanceReceiptBelongsToTheOriginalVisit() = runBlocking {
        onResponse = { switchTo("account-b"); switchTo("account-a") }
        val result = runCatching { control.accept(owner, "call") }

        assertEquals(listOf("Bearer token-account-a"), tokens)
        assertTrue(result.exceptionOrNull() is CancellationException)
    }

    @Test fun currentAccountCanPrepareAcceptAndLeave() = runBlocking {
        control.prepare(owner, "call", "dm", "conversation", listOf("peer")).getOrThrow()
        control.accept(owner, "call").getOrThrow()
        control.leave(owner, "call", "cancel").getOrThrow()

        assertEquals(List(3) { "Bearer token-account-a" }, tokens)
        assertEquals(listOf("/v1/data/calls/prepare", "/v1/data/calls/call/accept", "/v1/data/calls/call/leave"), paths)
    }

    @Test fun aBusyReplyFromTheCurrentVisitStillReachesTheCaller() = runBlocking {
        status = HttpStatusCode.Conflict
        response = """{"error":"CALL_ACCOUNT_BUSY"}"""
        assertEquals("Ya hay otra llamada activa.", control.accept(owner, "call").exceptionOrNull()?.message)
    }

    @Test fun answeredElsewhereConflictMapsToMultiDeviceNotice() = runBlocking {
        status = HttpStatusCode.Conflict
        response = """{"error":"CALL_ANSWERED_ELSEWHERE"}"""
        assertEquals(
            "Llamada respondida en otro dispositivo.",
            control.accept(owner, "call").exceptionOrNull()?.message,
        )
    }

    @Test fun currentAccountCanInviteAnotherParticipantToActiveCall() = runBlocking {
        status = HttpStatusCode.OK
        response = "{}"
        control.invite(owner, "call-42", "peer-2").getOrThrow()
        assertEquals(listOf("Bearer token-account-a"), tokens)
        assertEquals(listOf("/v1/data/calls/call-42/invite"), paths)
    }
}
