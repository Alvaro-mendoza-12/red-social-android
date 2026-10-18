package conectate.com.data.livekit

import org.junit.Assert.*
import org.junit.Test

class LiveHostMediaControlPolicyTest {
    @Test fun hostPublisherStaysConnectedWhileMediaIsMutedOrPaused() {
        assertTrue(shouldFollowLiveHostControls("host"))
    }
    @Test fun guestAndViewerCannotInheritHostPublisherControls() {
        assertFalse(shouldFollowLiveHostControls("guest"))
        assertFalse(shouldFollowLiveHostControls("viewer"))
    }
}
