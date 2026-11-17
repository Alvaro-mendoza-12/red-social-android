package conectate.com.data.repository

import conectate.com.data.model.*
import conectate.com.data.payout.CreatorPayoutApiClient
import conectate.com.data.payout.CreatorPayoutRetryStore
import conectate.com.data.payout.PendingCreatorPayout
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CreatorPayoutAccountBoundaryTest(private val operation: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun operations() = listOf("summary", "account", "request", "cancel")
    }
    private val guard = AuthSessionMutationGuard()
    private var account = "a"
    private val auth = mockk<AuthService>()
    private val api = mockk<CreatorPayoutApiClient>()
    private val retries = mockk<CreatorPayoutRetryStore>()
    private val pending = mutableMapOf<String, PendingCreatorPayout>()
    private val repository = CreatorPayoutRepository(auth, api, retries)
    private val sent = mutableListOf<String>()
    private var reply: CompletableDeferred<Unit>? = null

    init {
        coEvery { retries.load(any()) } coAnswers { pending[firstArg<String>()] }
        coEvery { retries.save(any(), any()) } coAnswers { pending[firstArg()] = secondArg(); Unit }
        coEvery { retries.clear(any(), any()) } coAnswers { pending.remove(firstArg<String>()); Unit }
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        coEvery { api.summary(any()) } coAnswers { sent += firstArg<String>(); reply?.await(); Result.success(CreatorPayoutSummary()) }
        coEvery { api.updateAccount(any(), any(), any()) } coAnswers {
            sent += firstArg<String>(); reply?.await(); Result.success(CreatorPayoutAccountResponse(CreatorPayoutAccountSummary()))
        }
        coEvery { api.requestPayout(any(), any(), any()) } coAnswers {
            sent += firstArg<String>(); reply?.await(); Result.success(CreatorPayoutRequestResponse(CreatorPayoutRequest("request", 100, "USD", "REQUESTED")))
        }
        coEvery { api.cancel(any(), any()) } coAnswers {
            sent += firstArg<String>(); reply?.await(); Result.success(CreatorPayoutRequestResponse(CreatorPayoutRequest("request", 100, "USD", "CANCELLED")))
        }
    }

    private suspend fun call() {
        when (operation) {
            "summary" -> repository.summary().getOrThrow()
            "account" -> repository.updateAccount("qa@example.invalid", "USD").getOrThrow()
            "request" -> repository.request(100).getOrThrow()
            "cancel" -> repository.cancel("request").getOrThrow()
        }
    }

    @Test fun renewalForAnotherAccountCannotAuthorizeTheOldPayoutAction() = runTest {
        val token = CompletableDeferred<Result<String>>()
        coEvery { auth.ensureValidAccessToken() } coAnswers { token.await() }
        val task = async { call() }
        runCurrent(); guard.invalidate { account = "b" }; token.complete(Result.success("token-b"))
        try { task.await() } catch (_: CancellationException) { }
        assertTrue("A payout action for A must never use B's credential", sent.isEmpty())
    }

    @Test fun returningToTheSameAccountCannotAuthorizeTheOldPayoutAction() = runTest {
        val token = CompletableDeferred<Result<String>>()
        coEvery { auth.ensureValidAccessToken() } coAnswers { token.await() }
        val task = async { call() }
        runCurrent(); guard.invalidate { account = "b" }; guard.invalidate { account = "a" }
        token.complete(Result.success("token-a-new"))
        try { task.await() } catch (_: CancellationException) { }
        assertTrue(sent.isEmpty())
    }

    @Test fun currentAccountCanStillUseThePayoutEndpoint() = runTest {
        coEvery { auth.ensureValidAccessToken() } returns Result.success("token-a")
        call()
        assertEquals(listOf("token-a"), sent)
    }

    @Test fun anOldPayoutResponseCannotReturnToTheNewAccount() = runTest {
        coEvery { auth.ensureValidAccessToken() } returns Result.success("token-a")
        reply = CompletableDeferred()
        val task = async { call(); "visible" }
        runCurrent(); assertEquals(listOf("token-a"), sent)
        guard.invalidate { account = "b" }; reply!!.complete(Unit)
        try { task.await(); fail("The response belongs to the previous account") }
        catch (_: CancellationException) { }
    }
}
