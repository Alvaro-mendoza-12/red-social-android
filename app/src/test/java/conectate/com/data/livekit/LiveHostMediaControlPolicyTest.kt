package conectate.com.data.livekit

import org.junit.Assert.*
import org.junit.Test

class LiveHostMediaControlPolicyTest {
    @Test fun receiveOnlyHostDoesNotPublishOnStateChanges() {
        assertFalse(shouldFollowLiveHostControls("host", false, false, false))
    }
    @Test fun hostPublicationFollowsControls() {
        assertTrue(shouldFollowLiveHostControls("host", true, false, false))
        assertTrue(shouldFollowLiveHostControls("host", false, true, false))
    }
    @Test fun pausedAndMutedBattleCanResumeWithTheSameControls() {
        assertTrue(shouldFollowLiveHostControls("host", false, false, true))
    }
    @Test fun guestAndViewerCannotInheritHostControls() {
        assertFalse(shouldFollowLiveHostControls("guest", true, true, true))
        assertFalse(shouldFollowLiveHostControls("viewer", true, true, true))
    }
}
