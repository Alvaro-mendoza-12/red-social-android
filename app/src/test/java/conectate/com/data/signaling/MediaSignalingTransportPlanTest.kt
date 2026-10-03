package conectate.com.data.signaling

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MediaSignalingTransportPlanTest {
    @Test fun directHandshakeIsBoundedAndPollingPreservesCompatibility() {
        assertArrayEquals(arrayOf("websocket"), MediaSignalingTransportPlan().transports)
        assertEquals(2_500L, MediaSignalingTransportPlan().timeoutMs)
        val fallback = MediaSignalingTransportPlan(pollingFallback = true)
        assertArrayEquals(arrayOf("polling", "websocket"), fallback.transports)
        assertEquals(10_000L, fallback.timeoutMs)
    }

    @Test fun aStaleErrorCannotConsumeTheCurrentAttempt() {
        val gate = MediaSignalingFallback(MediaSignalingTransportPlan())
        assertFalse(gate.claim(false))
        assertTrue(gate.claim(true))
        assertFalse(gate.claim(true))
    }

    @Test fun pollingFailureCannotLoopBackThroughAnotherFallback() {
        assertFalse(MediaSignalingFallback(MediaSignalingTransportPlan(true)).claim(true))
        assertFalse(MediaSignalingFallback(MediaSignalingTransportPlan(preparationFailed = true)).claim(true))
    }

    @Test fun queuedConcurrentErrorsScheduleOnlyOneReplacement() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val gate = MediaSignalingFallback(MediaSignalingTransportPlan())
            val start = CountDownLatch(1)
            val results = (1..24).map {
                executor.submit<Boolean> { start.await(); gate.claim(true) }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(3, TimeUnit.SECONDS) })
        } finally { executor.shutdownNow() }
    }

    @Test fun leftSessionOrChangedAccountRejectsLateEvents() {
        assertTrue(isCurrentMediaSignalingSession(" ROOM ", "room", " User ", "user"))
        assertFalse(isCurrentMediaSignalingSession("room", null, "user", "user"))
        assertFalse(isCurrentMediaSignalingSession("room", "other-room", "user", "user"))
        assertFalse(isCurrentMediaSignalingSession("room", "room", "user", "other-user"))
        assertFalse(isCurrentMediaSignalingSession("room", "room", "user", null))
    }

    @Test fun blankIdentifiersCannotBeAnActiveMediaSession() {
        assertFalse(isCurrentMediaSignalingSession("", "", "user", "user"))
        assertFalse(isCurrentMediaSignalingSession("room", "room", "", ""))
        assertFalse(isCurrentMediaSignalingSession(" ", " ", "user", "user"))
    }
}
