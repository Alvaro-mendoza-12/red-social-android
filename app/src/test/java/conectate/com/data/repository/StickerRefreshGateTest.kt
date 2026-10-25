package conectate.com.data.repository

import org.junit.Assert.*
import org.junit.Test

class StickerRefreshGateTest {
    @Test fun reopeningPickersCannotStartDuplicateRequestsWhileTheServerIsSlow() {
        val gate = StickerRefreshGate(100)
        val lease = requireNotNull(gate.claim("a", 0))
        assertNull(gate.claim("a", 10))
        assertNull(gate.claim("a", 1_000))
        gate.finish(lease)
        assertNotNull(gate.claim("a", 1_001))
    }

    @Test fun completedRequestsKeepAShortRetryBudgetWithoutBlockingLocalData() {
        val gate = StickerRefreshGate(100)
        gate.finish(requireNotNull(gate.claim("a", 0)))
        assertNull(gate.claim("a", 99))
        assertNotNull(gate.claim("a", 100))
    }

    @Test fun accountChangeCanRefreshImmediatelyAndOldCompletionCannotReleaseIt() {
        val gate = StickerRefreshGate(100)
        val old = requireNotNull(gate.claim("a", 0))
        val current = requireNotNull(gate.claim("b", 1))
        gate.finish(old)
        assertNull(gate.claim("b", 200))
        gate.finish(current)
        assertNotNull(gate.claim("b", 201))
        assertNull(gate.claim("", 202))
    }
}
