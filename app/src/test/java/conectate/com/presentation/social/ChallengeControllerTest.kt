package conectate.com.presentation.social

import conectate.com.data.model.ActiveChallenge
import conectate.com.data.model.Challenge
import conectate.com.data.model.ChallengeSubmission
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChallengeControllerTest {
    private class Fixture(val test: TestScope) : ChallengeDataSource {
        val guard = AuthSessionMutationGuard()
        var owner: String? = "a"
        var now = 0L
        var roundCalls = 0
        var submissionsCalls = 0
        val votes = mutableListOf<List<Any>>()
        var active = round()
        var rows = listOf(row())
        var readRound: suspend (String) -> Result<ActiveChallenge> = { Result.success(active) }
        var readSubmissions: suspend (String) -> Result<List<ChallengeSubmission>> = { Result.success(rows) }
        var readParticipation: suspend (String) -> Result<Boolean> = { Result.success(true) }
        var readMine: suspend (String) -> Result<List<Challenge>> = { Result.success(listOf(active.challenge)) }
        var writeVote: suspend (Boolean) -> Result<Pair<Int, Boolean>> = { Result.success(if (it) 3 to true else 2 to false) }
        override suspend fun round(owner: String): Result<ActiveChallenge> { roundCalls++; return readRound(owner) }
        override suspend fun submissions(owner: String, round: ActiveChallenge): Result<List<ChallengeSubmission>> { submissionsCalls++; return readSubmissions(owner) }
        override suspend fun participated(owner: String, round: ActiveChallenge) = readParticipation(owner)
        override suspend fun mine(owner: String) = readMine(owner)
        override suspend fun vote(owner: String, round: ActiveChallenge, id: String, desired: Boolean): Result<Pair<Int, Boolean>> {
            votes += listOf(owner, round.id, id, desired)
            return writeVote(desired)
        }
        val controller = ChallengeController(test.backgroundScope, { guard.captureAccountLease { owner } }, this, { now })
        fun start() { controller.setVisible(true); test.runCurrent() }
        fun switch(to: String?) { guard.invalidate { owner = to }; controller.sessionChanged(); test.runCurrent() }
    }

    @Test fun oneInitialReadAndPollingOnlyWhileVisible() = runTest {
        val f = Fixture(this)
        assertEquals(0, f.roundCalls)
        f.start()
        f.controller.setVisible(true); runCurrent()
        assertEquals(1, f.roundCalls)
        advanceTimeBy(15_001); runCurrent()
        assertEquals(2, f.roundCalls)
        f.controller.setVisible(false)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, f.roundCalls)
        f.controller.setVisible(true); runCurrent()
        assertEquals(3, f.roundCalls)
    }

    @Test fun aFailedRefreshPreservesConfirmedSubmissions() = runTest {
        val f = Fixture(this); f.start()
        f.readSubmissions = { Result.failure(IOException("offline")) }
        f.controller.loadSubmissions(); runCurrent()
        assertEquals(f.rows, f.controller.state.value.submissions)
        assertNotNull(f.controller.state.value.error)
        assertFalse(f.controller.state.value.loadingSubmissions)
    }

    @Test fun submissionDoesNotUnlockUntilTheAcknowledgementAndCanRetryAfterFailure() = runTest {
        val f = Fixture(this)
        f.readParticipation = { Result.success(false) }; f.start()
        val entry = f.controller.entry()!!
        val reply = CompletableDeferred<Result<Unit>>()
        val first = async { f.controller.submit(entry) { _, owner -> assertEquals("a", owner); reply.await() } }
        runCurrent()
        assertTrue(f.controller.state.value.submitting)
        assertFalse(f.controller.state.value.hasParticipated)
        reply.complete(Result.failure(IOException("offline"))); runCurrent()
        assertTrue(first.await().isFailure)
        assertFalse(f.controller.state.value.submitting)
        assertFalse(f.controller.state.value.hasParticipated)
        assertTrue(f.controller.submit(entry) { _, _ -> Result.success(Unit) }.isSuccess)
        assertTrue(f.controller.state.value.hasParticipated)
    }

    @Test fun participationReadStartedBeforeSubmissionCannotClearItsAcknowledgement() = runTest {
        val f = Fixture(this)
        val participation = CompletableDeferred<Boolean>()
        f.readParticipation = { Result.success(participation.await()) }; f.start()
        val entry = f.controller.entry()!!
        assertTrue(f.controller.submit(entry) { _, _ -> Result.success(Unit) }.isSuccess)
        participation.complete(false); runCurrent()
        assertTrue(f.controller.state.value.hasParticipated)
    }

    @Test fun anEditorCannotPublishIntoANewRoundEvenIfTheActiveIdWasReused() = runTest {
        val f = Fixture(this); f.start()
        val entry = f.controller.entry()!!
        f.active = round(master = "other-prompt")
        f.controller.refresh(); runCurrent()
        var sent = false
        val result = f.controller.submit(entry) { _, _ -> sent = true; Result.success(Unit) }
        assertTrue(result.isFailure)
        assertFalse(sent)
    }

    @Test fun anExpiredEditorCannotStartAnUpload() = runTest {
        val f = Fixture(this); f.start()
        val entry = f.controller.entry()!!
        f.now = 600_001
        var sent = false
        assertTrue(f.controller.submit(entry) { _, _ -> sent = true; Result.success(Unit) }.isFailure)
        assertFalse(sent)
    }

    @Test fun aSubmissionAckAfterTheRoundChangesReturnsFailureWithoutUnlockingTheNextPrompt() = runTest {
        val f = Fixture(this); f.readParticipation = { Result.success(false) }; f.start()
        val entry = f.controller.entry()!!
        val reply = CompletableDeferred<Result<Unit>>()
        val send = async { f.controller.submit(entry) { _, _ -> reply.await() } }
        runCurrent()
        f.active = round("next")
        f.controller.refresh(); runCurrent()
        reply.complete(Result.success(Unit)); runCurrent()
        assertTrue(send.await().isFailure)
        assertEquals("next", f.controller.state.value.active?.id)
        assertFalse(f.controller.state.value.hasParticipated)
        assertFalse(f.controller.state.value.submitting)
    }

    @Test fun anOldSubmissionsReadCannotLeaveTheNextRoundLoadingForever() = runTest {
        val f = Fixture(this); f.start()
        val old = CompletableDeferred<List<ChallengeSubmission>>()
        f.readSubmissions = { Result.success(old.await()) }
        f.controller.loadSubmissions(); runCurrent()
        f.active = round("next")
        f.rows = listOf(row(round = "next"))
        f.readSubmissions = { Result.success(f.rows) }
        f.controller.refresh(); runCurrent()
        old.complete(listOf(row())); runCurrent()
        assertEquals(f.rows, f.controller.state.value.submissions)
        assertFalse(f.controller.state.value.loadingSubmissions)
    }

    @Test fun anOldAccountReplyCannotPopulateTheNextAccount() = runTest {
        val f = Fixture(this)
        val old = CompletableDeferred<ActiveChallenge>()
        f.readRound = { owner -> if (owner == "a") withContext(NonCancellable) { Result.success(old.await()) } else Result.success(round("b-round")) }
        f.start()
        f.switch("b")
        old.complete(round("a-round")); runCurrent()
        assertEquals("b", f.controller.state.value.ownerId)
        assertEquals("b-round", f.controller.state.value.active?.id)
        assertFalse(f.controller.state.value.loadingRound)
    }

    @Test fun aNewLoginOfTheSameIdentityInvalidatesItsOldEditorAndVisibleSnapshot() = runTest {
        val f = Fixture(this); f.start()
        val snapshot = f.controller.state.value
        val entry = f.controller.entry()!!
        f.guard.invalidate {}
        assertNull(f.controller.visibleState(snapshot).ownerId)
        f.controller.sessionChanged(); runCurrent()
        var sent = false
        val error = runCatching { f.controller.submit(entry) { _, _ -> sent = true; Result.success(Unit) } }.exceptionOrNull()
        assertTrue(error is kotlinx.coroutines.CancellationException)
        assertFalse(sent)
        assertTrue(f.controller.state.value.sessionEpoch > snapshot.sessionEpoch)
    }

    @Test fun aReadFromBeforeVoteAcknowledgementCannotRestoreTheOldVote() = runTest {
        val f = Fixture(this); f.start()
        val read = CompletableDeferred<List<ChallengeSubmission>>()
        f.readSubmissions = { Result.success(read.await()) }
        f.controller.loadSubmissions(); runCurrent()
        f.controller.toggleVote("s"); runCurrent()
        assertEquals(true, f.controller.state.value.submissions.single().votedByMe)
        read.complete(listOf(row(voted = false, count = 2))); runCurrent()
        assertEquals(true, f.controller.state.value.submissions.single().votedByMe)
        assertEquals(3, f.controller.state.value.submissions.single().voteCount)
        assertTrue(f.controller.state.value.pendingVotes.isEmpty())
    }

    @Test fun anUncertainVoteIsReadBackWithoutSendingAnotherToggle() = runTest {
        val f = Fixture(this); f.start()
        f.writeVote = { desired ->
            f.rows = listOf(row(voted = desired, count = 3))
            Result.failure(IOException("ACK lost"))
        }
        f.controller.toggleVote("s"); runCurrent()
        assertEquals(listOf(listOf("a", "round", "s", true)), f.votes)
        assertTrue(f.controller.state.value.submissions.single().votedByMe)
        assertEquals(3, f.controller.state.value.submissions.single().voteCount)
        assertTrue(f.controller.state.value.pendingVotes.isEmpty())
    }

    @Test fun repeatedClicksDoNotSendASecondVoteWhilePending() = runTest {
        val f = Fixture(this); f.start()
        val reply = CompletableDeferred<Result<Pair<Int, Boolean>>>()
        f.writeVote = { reply.await() }
        f.controller.toggleVote("s"); f.controller.toggleVote("s"); runCurrent()
        assertEquals(1, f.votes.size)
        reply.complete(Result.success(3 to true)); runCurrent()
        f.controller.toggleVote("s"); runCurrent()
        assertEquals(false, f.votes.last().last())
    }

    @Test fun aPreviousRoundVoteCannotReleaseANewerPendingVoteWithTheSameSubmissionId() = runTest {
        val f = Fixture(this); f.start()
        val oldReply = CompletableDeferred<Result<Pair<Int, Boolean>>>()
        f.writeVote = { withContext(NonCancellable) { oldReply.await() } }
        f.controller.toggleVote("s"); runCurrent()
        f.active = round(master = "other")
        f.rows = listOf(row(master = "other"))
        f.controller.refresh(); runCurrent()
        val newReply = CompletableDeferred<Result<Pair<Int, Boolean>>>()
        f.writeVote = { newReply.await() }
        f.controller.toggleVote("s"); runCurrent()
        oldReply.complete(Result.success(99 to true)); runCurrent()
        assertTrue("s" in f.controller.state.value.pendingVotes)
        assertEquals(3, f.controller.state.value.submissions.single().voteCount)
        newReply.complete(Result.success(4 to true)); runCurrent()
        assertTrue(f.controller.state.value.pendingVotes.isEmpty())
        assertEquals(4, f.controller.state.value.submissions.single().voteCount)
    }

    @Test fun aLateSubmissionCannotUnlockAnotherSession() = runTest {
        val f = Fixture(this); f.readParticipation = { Result.success(false) }; f.start()
        val entry = f.controller.entry()!!
        val reply = CompletableDeferred<Result<Unit>>()
        val send = async { f.controller.submit(entry) { _, _ -> withContext(NonCancellable) { reply.await() } } }
        runCurrent(); f.switch("b")
        reply.complete(Result.success(Unit)); runCurrent()
        assertTrue(runCatching { send.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals("b", f.controller.state.value.ownerId)
        assertFalse(f.controller.state.value.hasParticipated)
        assertFalse(f.controller.state.value.submitting)
    }

    @Test fun aLateMineReadCannotExposeThePreviousOwnersList() = runTest {
        val f = Fixture(this)
        val reply = CompletableDeferred<List<Challenge>>()
        f.readMine = { owner -> if (owner == "a") withContext(NonCancellable) { Result.success(reply.await()) } else Result.success(emptyList()) }
        f.controller.loadMine(); runCurrent(); f.switch("b")
        reply.complete(listOf(round().challenge)); runCurrent()
        assertTrue(f.controller.state.value.userChallenges.isEmpty())
    }

    @Test fun cancellingTheSubmitCallerCancelsItsUploadAndReleasesTheButton() = runTest {
        val f = Fixture(this); f.start()
        var cancelled = false
        val send = async {
            f.controller.submit(f.controller.entry()!!) { _, _ ->
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        runCurrent(); send.cancel(); runCurrent()
        assertTrue(cancelled)
        assertFalse(f.controller.state.value.submitting)
    }

    @Test fun countdownUsesTheDeadlineRatherThanSubtractingTicksAfterSuspension() = runTest {
        val f = Fixture(this); f.start()
        assertEquals(600, f.controller.state.value.remainingSeconds)
        f.now = 410_001
        f.controller.tick()
        assertEquals(190, f.controller.state.value.remainingSeconds)
        f.now = 900_000; f.controller.tick()
        assertEquals(0, f.controller.state.value.remainingSeconds)
    }

    @Test fun duplicateOrUnrelatedSubmissionsAreNotShown() = runTest {
        val f = Fixture(this)
        f.rows = listOf(row(), row(), row(round = "old"), row(master = "old-master"))
        f.start()
        assertEquals(listOf(row()), f.controller.state.value.submissions)
    }

    @Test fun accountMutationStaysPendingUntilAckAndFailureDoesNotClaimSuccess() = runTest {
        val f = Fixture(this)
        val reply = CompletableDeferred<Result<Unit>>()
        val request = async { f.controller.mutate { reply.await() } }
        runCurrent()
        assertTrue(f.controller.state.value.mutating)
        reply.complete(Result.failure(IOException("offline"))); runCurrent()
        assertTrue(request.await().isFailure)
        assertFalse(f.controller.state.value.mutating)
    }

    @Test fun aMineReadStartedBeforeCreationCannotReplaceTheRefreshedList() = runTest {
        val f = Fixture(this)
        val old = CompletableDeferred<List<Challenge>>()
        f.readMine = { withContext(NonCancellable) { Result.success(old.await()) } }
        f.controller.loadMine(); runCurrent()
        assertTrue(f.controller.mutate { Result.success(Unit) }.isSuccess)
        val created = round(master = "created").challenge
        f.readMine = { Result.success(listOf(created)) }
        f.controller.loadMine(); runCurrent()
        old.complete(emptyList()); runCurrent()
        assertEquals(listOf(created), f.controller.state.value.userChallenges)
    }

    companion object {
        private fun round(id: String = "round", master: String = "master") = ActiveChallenge(id, master,
            Challenge(master, "Prompt", "Description", "text"), Instant.ofEpochMilli(600_000).toString(), status = "active")
        private fun row(round: String = "round", master: String = "master", voted: Boolean = false, count: Int = 2) =
            ChallengeSubmission("s", master, round, "friend", textContent = "answer", voteCount = count, votedByMe = voted, createdAt = "now")
    }
}
