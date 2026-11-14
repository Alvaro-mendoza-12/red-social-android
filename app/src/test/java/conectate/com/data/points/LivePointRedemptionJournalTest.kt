package conectate.com.data.points

import android.util.AtomicFile
import conectate.com.data.model.LivePointRedemption
import conectate.com.data.model.LivePointRedemptionResponse
import conectate.com.data.repository.LiveChannelPointsRepository
import conectate.com.data.repository.RecordingLivePointsApi
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class LivePointRedemptionJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private val response = LivePointRedemptionResponse(LivePointRedemption("redemption", "live", "reward", "a", 3, "requested"), 7)

    @Test fun aLostAcknowledgementSurvivesReconstructingBothRepositoryAndJournal() = runTest {
        val directory = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val guard = AuthSessionMutationGuard()
        val api = RecordingLivePointsApi()
        val debits = mutableSetOf<String>()
        var loseAck = true
        api.redeemReward = { nonce ->
            assertEquals(1, directory.listFiles()!!.count { it.extension == "json" })
            assertFalse(directory.listFiles()!!.single { it.extension == "json" }.readText().contains("token-a"))
            debits += nonce
            if (loseAck) Result.failure(IllegalStateException("Network lost after commit")) else Result.success(response)
        }
        fun repository() = LiveChannelPointsRepository(api, { guard.captureAccountLease { "a" } }, { "token-a" }, LivePointRedemptionJournal(directory, dispatcher))
        assertTrue(repository().redeem("live", "reward").isFailure)
        loseAck = false
        assertTrue(repository().redeem("live", "reward").isSuccess)
        assertEquals(2, api.redemptionNonces.size)
        assertEquals(api.redemptionNonces.first(), api.redemptionNonces.last())
        assertEquals(1, debits.size)
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun accountRoundTripKeepsTheOriginalNonceButNeverSharesItWithTheOtherAccount() = runTest {
        val directory = temporary.newFolder()
        val journal = LivePointRedemptionJournal(directory, StandardTestDispatcher(testScheduler))
        val guard = AuthSessionMutationGuard()
        var account = "a"
        var first = ""
        val lease = guard.captureAccountLease { account }
        val old = async {
            try { journal.execute("a", "live", "reward", lease::requireCurrent) {
                first = it
                guard.invalidate { account = "b" }
                response
            }; false } catch (_: CancellationException) { true }
        }
        assertTrue(old.await())
        val b = guard.captureAccountLease { account }
        journal.execute("b", "live", "reward", b::requireCurrent) { assertNotEquals(first, it); response }
        guard.invalidate { account = "a" }
        val returned = guard.captureAccountLease { account }
        journal.execute("a", "live", "reward", returned::requireCurrent) { assertEquals(first, it); response }
    }

    @Test fun cancellingAfterDispatchRetainsTheNonceForTheNextProcessInstance() = runTest {
        val directory = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var nonce = ""
        val send = launch {
            LivePointRedemptionJournal(directory, dispatcher).execute("a", "live", "reward", {}) {
                nonce = it; started.complete(Unit)
                withContext(NonCancellable) { finish.await() }
                response
            }
        }
        started.await(); send.cancel(); finish.complete(Unit); send.join()
        LivePointRedemptionJournal(directory, dispatcher).execute("a", "live", "reward", {}) { assertEquals(nonce, it); response }
    }

    @Test fun overlappingStoreInstancesUseTheSameNonce() = runTest {
        val directory = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val finish = CompletableDeferred<Unit>()
        val nonces = mutableListOf<String>()
        val first = async { LivePointRedemptionJournal(directory, dispatcher).execute("a", "live", "reward", {}) { nonces += it; finish.await(); response } }
        runCurrent()
        val second = async { LivePointRedemptionJournal(directory, dispatcher).execute("a", "live", "reward", {}) { nonces += it; response } }
        runCurrent(); finish.complete(Unit); first.await(); second.await()
        assertEquals(2, nonces.size); assertEquals(nonces.first(), nonces.last())
    }

    @Test fun anOldConcurrentAcknowledgementCannotRemoveANewerPendingRedemption() = runTest {
        val directory = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = LivePointRedemptionJournal(directory, dispatcher)
        val finish = CompletableDeferred<Unit>()
        var old = ""
        var newer = ""
        val first = async { journal.execute("a", "live", "reward", {}) { old = it; finish.await(); response } }
        runCurrent()
        journal.execute("a", "live", "reward", {}) { assertEquals(old, it); response }
        try { journal.execute("a", "live", "reward", {}) { newer = it; throw IllegalStateException("Lost new ACK") } } catch (_: IllegalStateException) { }
        assertNotEquals(old, newer)
        finish.complete(Unit); first.await()
        LivePointRedemptionJournal(directory, dispatcher).execute("a", "live", "reward", {}) { assertEquals(newer, it); response }
    }

    @Test fun anUnreadablePendingCommandNeverDispatchesWithANewNonce() = runTest {
        val directory = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val journal = LivePointRedemptionJournal(directory, dispatcher)
        try { journal.execute("a", "live", "reward", {}) { throw IllegalStateException("Offline") } } catch (_: IllegalStateException) { }
        val file = directory.listFiles()!!.single(); file.writeText("corrupted")
        var sent = false
        assertTrue(runCatching { journal.execute("a", "live", "reward", {}) { sent = true; response } }.isFailure)
        assertFalse(sent); assertEquals("corrupted", file.readText())
    }

    @Test fun aStorageFailureStopsDispatchBeforeAnyDebit() = runTest {
        val blocked = File(temporary.root, "not-a-directory").apply { writeText("fixture") }
        val journal = LivePointRedemptionJournal(blocked, StandardTestDispatcher(testScheduler))
        var sent = false
        assertTrue(runCatching { journal.execute("a", "live", "reward", {}) { sent = true; response } }.isFailure)
        assertFalse(sent)
    }

    @Test fun anInterruptedAtomicWriteRecoversTheOriginalPendingCommand() = runTest {
        val directory = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        var nonce = ""
        try { LivePointRedemptionJournal(directory, dispatcher).execute("a", "live", "reward", {}) { nonce = it; throw IllegalStateException("Lost ACK") } } catch (_: IllegalStateException) { }
        val file = AtomicFile(directory.listFiles()!!.single())
        // Emulate a process exit between startWrite and finishWrite, preserving the backup.
        file.startWrite().use { it.write("interrupted".toByteArray()) }
        LivePointRedemptionJournal(directory, dispatcher).execute("a", "live", "reward", {}) { assertEquals(nonce, it); response }
    }
}
