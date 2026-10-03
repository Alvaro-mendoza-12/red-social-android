package conectate.com.presentation.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileVisitRecorderTest {
    @Test fun cachedAndNetworkDisplayRecordOneVisit() = runTest {
        var calls = 0
        val recorder = ProfileVisitRecorder(backgroundScope, { "viewer" }) { calls++; Result.success(Unit) }
        recorder.displayed("target", true); recorder.displayed("target", true); runCurrent()
        recorder.displayed("target", true); runCurrent()
        assertEquals(1, calls)
    }
    @Test fun failedWriteCanBeRetriedWhenTheProfileIsDisplayedAgain() = runTest {
        var calls = 0
        val recorder = ProfileVisitRecorder(backgroundScope, { "viewer" }) {
            calls++; if (calls == 1) Result.failure(IllegalStateException()) else Result.success(Unit)
        }
        recorder.displayed("target", true); runCurrent()
        recorder.displayed("target", true); runCurrent()
        assertEquals(2, calls)
    }
    @Test fun ownProfileAndPrivacyOptOutAreNotRecorded() = runTest {
        var calls = 0
        val recorder = ProfileVisitRecorder(backgroundScope, { "viewer" }) { calls++; Result.success(Unit) }
        recorder.displayed("viewer", true); recorder.displayed("target", false); runCurrent()
        assertEquals(0, calls)
    }
    @Test fun changingAccountsDoesNotReuseThePreviousVisitHistory() = runTest {
        var account = "viewer-one"; var calls = 0
        val recorder = ProfileVisitRecorder(backgroundScope, { account }) { calls++; Result.success(Unit) }
        recorder.displayed("target", true); runCurrent()
        account = "viewer-two"
        recorder.displayed("target", true); runCurrent()
        assertEquals(2, calls)
    }
    @Test fun lateOldAccountResultCannotMarkTheNewAccountAsRecorded() = runTest {
        var account = "viewer-one"; var calls = 0
        val gate = CompletableDeferred<Unit>()
        val recorder = ProfileVisitRecorder(backgroundScope, { account }) {
            calls++; if (calls == 1) gate.await(); Result.success(Unit)
        }
        recorder.displayed("target", true); runCurrent()
        account = "viewer-two"
        recorder.displayed("target", true); runCurrent()
        gate.complete(Unit); runCurrent()
        recorder.displayed("target", true); runCurrent()
        assertEquals(2, calls)
    }
}
