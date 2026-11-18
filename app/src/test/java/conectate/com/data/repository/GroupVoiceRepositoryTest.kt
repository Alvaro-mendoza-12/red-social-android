package conectate.com.data.repository

import conectate.com.data.groupvoice.*
import conectate.com.data.supabase.AuthService
import conectate.com.data.supabase.AuthSessionMutationGuard
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GroupVoiceRepositoryTest {
    private val guard = AuthSessionMutationGuard()
    private var account = "a"
    private val auth = mockk<AuthService>()
    private val api = mockk<GroupVoiceApi>()
    private val store = mockk<GroupVoiceIntentStore>()
    private var saved: GroupVoiceIntent? = null
    private val sent = mutableListOf<GroupVoiceIntent>()
    private val intent = GroupVoiceIntent("11111111-1111-4111-8111-111111111111", "selected", listOf("friend"))
    private fun repo() = GroupVoiceRepository(auth, api, store)
    init {
        every { auth.captureAccountLease() } answers { guard.captureAccountLease { account } }
        coEvery { auth.ensureValidAccessToken() } returns Result.success("token-a")
        coEvery { auth.getAccessTokenForAccountAwait(any()) } returns "token-a"
        coEvery { store.load(any(), any()) } coAnswers { saved }
        coEvery { store.save(any(), any(), any()) } coAnswers { saved = thirdArg() }
        coEvery { store.clear(any(), any(), any()) } coAnswers { if (saved?.requestId == thirdArg<String>()) saved = null }
        coEvery { api.create(any(), any(), any()) } coAnswers { sent += thirdArg<GroupVoiceIntent>(); GroupVoiceCreated(intent.requestId, 1) }
        coEvery { api.roster(any(), any()) } returns GroupVoiceRoster("group", "Grupo", listOf(GroupVoiceMember("friend", "Amiga")))
    }
    @Test fun changingAccountDuringRenewalNeverSendsAnInvitation() = runTest {
        val token = CompletableDeferred<Result<String>>()
        coEvery { auth.ensureValidAccessToken() } coAnswers { token.await() }
        val repository = repo(); val lease = repository.capture()
        val task = async { repository.create(lease, "group", intent) }
        runCurrent(); guard.invalidate { account = "b" }; token.complete(Result.success("token-b"))
        try { task.await() } catch (_: CancellationException) { }
        assertTrue(sent.isEmpty())
    }
    @Test fun sameAccountAfterLogoutCannotResumeTheOldInvitation() = runTest {
        val token = CompletableDeferred<Result<String>>()
        coEvery { auth.ensureValidAccessToken() } coAnswers { token.await() }
        val repository = repo(); val lease = repository.capture()
        val task = async { repository.create(lease, "group", intent) }
        runCurrent(); guard.invalidate { account = "b" }; guard.invalidate { account = "a" }; token.complete(Result.success("token-new-a"))
        try { task.await() } catch (_: CancellationException) { }
        assertTrue(sent.isEmpty())
    }
    @Test fun rosterFromAnOldAccountCannotReachTheNewAccount() = runTest {
        val response = CompletableDeferred<GroupVoiceRoster>()
        coEvery { api.roster(any(), any()) } coAnswers { response.await() }
        val repository = repo(); val task = async { repository.roster(repository.capture(), "group") }
        runCurrent(); guard.invalidate { account = "b" }
        response.complete(GroupVoiceRoster("group", "Privado", emptyList()))
        try { task.await(); fail("Old roster must be cancelled") } catch (_: CancellationException) { }
    }
    @Test fun aLostConfirmationSurvivesRepositoryReconstructionAndKeepsItsRecipients() = runTest {
        coEvery { api.create(any(), any(), any()) } coAnswers { sent += thirdArg<GroupVoiceIntent>(); throw IOException("lost ACK") }
        val first = repo(); assertTrue(first.create(first.capture(), "group", intent).isFailure)
        val second = repo()
        assertEquals(intent, second.pending(second.capture(), "group"))
        coEvery { api.create(any(), any(), any()) } coAnswers { sent += thirdArg<GroupVoiceIntent>(); GroupVoiceCreated(intent.requestId, 1, true) }
        assertEquals(intent.requestId, second.create(second.capture(), "group", intent).getOrThrow().roomId)
        assertEquals(listOf(intent, intent), sent)
        assertNull(saved)
    }
    @Test fun anotherSelectionCannotReplaceAnAmbiguousInvitation() = runTest {
        saved = intent
        val repository = repo()
        assertTrue(repository.create(repository.capture(), "group", intent.copy(recipientIds = listOf("other"))).isFailure)
        assertTrue(sent.isEmpty()); assertEquals(intent, saved)
    }
    @Test fun aFailedDurableWriteNeverAuthorizesHttpEvenOnRetry() = runTest {
        coEvery { store.save(any(), any(), any()) } coAnswers { saved = thirdArg(); throw IOException("disk") }
        val repository = repo()
        repeat(2) { assertTrue(repository.create(repository.capture(), "group", intent).isFailure) }
        assertTrue(sent.isEmpty())
    }
    @Test fun cleanupFailureDoesNotLoseTheServerAckOrAllocateAnotherIntent() = runTest {
        coEvery { store.clear(any(), any(), any()) } throws IOException("disk")
        val repository = repo()
        assertTrue(repository.create(repository.capture(), "group", intent).isSuccess)
        assertEquals(intent, saved)
        assertTrue(repository.create(repository.capture(), "group", intent).isSuccess)
        assertEquals(listOf(intent, intent), sent)
    }
    @Test fun aConfirmedRosterRejectionAllowsReconfigurationButRetainsNoUnknownFailure() = runTest {
        coEvery { api.create(any(), any(), any()) } throws GroupVoiceApiException("GROUP_VOICE_MEMBERS_CHANGED", 409)
        val repository = repo()
        assertTrue(repository.create(repository.capture(), "group", intent).isFailure)
        assertNull(saved)
        coEvery { api.create(any(), any(), any()) } throws GroupVoiceApiException("SERVER_TEMPORARY", 503)
        assertTrue(repository.create(repository.capture(), "group", intent).isFailure)
        assertEquals(intent, saved)
    }
    @Test fun aReplyAfterAccountChangeDoesNotClearTheOutgoingIntentOrReturnItsRoom() = runTest {
        val reply = CompletableDeferred<GroupVoiceCreated>()
        coEvery { api.create(any(), any(), any()) } coAnswers { reply.await() }
        val repository = repo(); val task = async { repository.create(repository.capture(), "group", intent) }
        runCurrent(); guard.invalidate { account = "b" }; reply.complete(GroupVoiceCreated(intent.requestId, 1))
        try { task.await(); fail("Old room must not navigate") } catch (_: CancellationException) { }
        assertEquals(intent, saved)
    }
}
