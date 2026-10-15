package conectate.com.data.livekit

import org.junit.Assert.*
import org.junit.Test

class LiveRendererRecoveryPolicyTest {
    @Test fun waitsBeforeAttemptingAnInitialRepair() {
        val policy = LiveRendererRecoveryPolicy(100)
        assertFalse(policy.claimRepair(1599, true))
        assertTrue(policy.claimRepair(1600, true))
    }
    @Test fun freshFramesPreventRepair() {
        val policy = LiveRendererRecoveryPolicy(0)
        policy.recordFrame(1400)
        assertFalse(policy.claimRepair(2000, true))
        assertTrue(policy.claimRepair(2900, true))
    }
    @Test fun inactiveRendererDoesNotConsumeTheBudget() {
        val policy = LiveRendererRecoveryPolicy(0)
        repeat(5) { assertFalse(policy.claimRepair(2000L + it * 500, false)) }
        assertTrue(policy.claimRepair(4500, true))
    }
    @Test fun stopsAfterTwoAttempts() {
        val policy = LiveRendererRecoveryPolicy(0)
        assertTrue(policy.claimRepair(1500, true))
        assertTrue(policy.claimRepair(3000, true))
        assertFalse(policy.claimRepair(4500, true))
    }
    @Test fun repairsHaveACooldown() {
        val policy = LiveRendererRecoveryPolicy(0)
        assertTrue(policy.claimRepair(1500, true))
        assertFalse(policy.claimRepair(2999, true))
        assertTrue(policy.claimRepair(3000, true))
    }
    @Test fun monitoringEndsAfterTenSeconds() {
        assertFalse(LiveRendererRecoveryPolicy(100).claimRepair(10101, true))
        assertTrue(LiveRendererRecoveryPolicy(100).claimRepair(10100, true))
    }
    @Test fun olderFrameTimestampDoesNotMoveTheClockBack() {
        val policy = LiveRendererRecoveryPolicy(0)
        policy.recordFrame(2000)
        policy.recordFrame(1000)
        assertFalse(policy.claimRepair(3000, true))
    }
    @Test fun earlierClockIsRejected() {
        assertFalse(LiveRendererRecoveryPolicy(100).claimRepair(99, true))
    }
}
