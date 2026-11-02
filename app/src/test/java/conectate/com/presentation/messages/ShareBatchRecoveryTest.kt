package conectate.com.presentation.messages

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShareBatchRecoveryTest {
    private val destination = ShareDestination("chat", ShareDestinationType.CHAT, "Original friend")
    private val item = ShareBatchItem("text", destination, ShareOutgoingContent.Text("Original text"))
    private val saved = SavedShareBatch(listOf(item), emptyMap(), false)

    @Test fun anAbsentCommandIsTheOnlyRecoveryThatUnlocksAnEditableDraft() = runTest {
        val recovery = ShareBatchRecovery(); var restored: SavedShareBatch? = saved
        recovery.load("r", {}, { null }) { restored = it }
        assertNull(restored)
        assertEquals(ShareRecoveryState.Mode.EDITABLE, recovery.state.value.mode)
        assertFalse(recovery.state.value.blocked)
    }

    @Test fun pendingRecoveryShowsTheOriginalPlanInsteadOfAnotherDraft() = runTest {
        val recovery = ShareBatchRecovery(); var restored: SavedShareBatch? = null
        recovery.load("r", {}, { saved }) { restored = it }
        assertEquals(saved, restored)
        assertEquals(listOf(item), recovery.state.value.plan)
        assertEquals(ShareRecoveryState.Mode.PENDING, recovery.state.value.mode)
    }

    @Test fun aReadFailureBlocksSendingUntilRecoverySucceeds() = runTest {
        val recovery = ShareBatchRecovery(); var restored = false
        recovery.load("r", {}, { throw IOException("disk unavailable") }) { restored = true }
        assertFalse(restored); assertTrue(recovery.state.value.blocked)
        assertEquals(ShareRecoveryState.Mode.FAILED, recovery.state.value.mode)
        recovery.load("r", {}, { saved }) { restored = true }
        assertTrue(restored); assertEquals(ShareRecoveryState.Mode.PENDING, recovery.state.value.mode)
    }

    @Test fun anotherAccountsRecoveryNeverExposesThePreviousRecipientsOrText() = runTest {
        val recovery = ShareBatchRecovery()
        recovery.load("r", {}, { throw ShareBatchOwnerMismatch() }) { error("Must not restore") }
        assertEquals(ShareRecoveryState.Mode.OTHER_ACCOUNT, recovery.state.value.mode)
        assertTrue(recovery.state.value.blocked); assertTrue(recovery.state.value.plan.isEmpty())
    }

    @Test fun closingIsRestoredAsClosedAndNeverAsAnEditableNewShare() = runTest {
        val recovery = ShareBatchRecovery()
        recovery.load("r", {}, { SavedShareBatch(emptyList(), emptyMap(), true) }) {}
        assertEquals(ShareRecoveryState.Mode.CLOSED, recovery.state.value.mode)
        assertTrue(recovery.state.value.blocked)
    }

    @Test fun lateDiskReadCannotUnlockOrPopulateTheReplacementRequest() = runTest {
        val recovery = ShareBatchRecovery(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var oldRestored = false
        val old = async {
            recovery.load("old", {}, { withContext(NonCancellable) { entered.complete(Unit); release.await() }; saved }) { oldRestored = true }
        }
        runCurrent(); entered.await()
        recovery.load("new", {}, { null }) {}
        release.complete(Unit); runCurrent()
        assertTrue(runCatching { old.await() }.exceptionOrNull() is CancellationException)
        assertFalse(oldRestored)
        assertEquals("new", recovery.state.value.requestId)
        assertEquals(ShareRecoveryState.Mode.EDITABLE, recovery.state.value.mode)
    }

    @Test fun losingTheAccountWhileReadingCannotRestorePrivateContent() = runTest {
        val recovery = ShareBatchRecovery(); var valid = true; var restored = false
        val error = runCatching {
            recovery.load("r", { if (!valid) throw CancellationException("Account changed") },
                { valid = false; saved }) { restored = true }
        }.exceptionOrNull()
        assertTrue(error is CancellationException); assertFalse(restored)
        assertTrue(recovery.state.value.plan.isEmpty())
    }
}
