package conectate.com.presentation.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingShareLaunchPolicyTest {
    @Test fun freshSharesRemainAllowedIncludingTheSameFileSentAgain() {
        repeat(2) { assertTrue(shouldAcceptRestoredShare(fromHistory = false, alreadyConsumed = false)) }
    }
    @Test fun recentsMustNotReplayAnOldShare() {
        assertFalse(shouldAcceptRestoredShare(fromHistory = true, alreadyConsumed = false))
        assertFalse(shouldAcceptRestoredShare(fromHistory = true, alreadyConsumed = true))
    }
    @Test fun recreatingACompletedShareMustNotReopenTheDialog() {
        assertFalse(shouldAcceptRestoredShare(fromHistory = false, alreadyConsumed = true))
    }
    @Test fun rotationKeepsAnUnconsumedDelivery() {
        assertTrue(shouldAcceptRestoredShare(fromHistory = false, alreadyConsumed = false))
    }
}
