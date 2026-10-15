package conectate.com.presentation.live

import org.junit.Assert.*
import org.junit.Test

class LiveParticipantLayoutPolicyTest {
    @Test fun duetKeepsTwoWidePanels() { assertEquals(2, liveParticipantColumnCount(2)) }
    @Test fun sevenToNinePeopleUseThreeColumns() {
        (7..9).forEach { assertEquals(3, liveParticipantColumnCount(it)) }
    }
    @Test fun camerasFollowUserIdentityAfterReordering() {
        assertEquals(1, matchingLiveParticipantIndex("USER-B", listOf("user-a", "user-b")))
        assertEquals(0, matchingLiveParticipantIndex("user-b", listOf("user-b", "user-a")))
    }
    @Test fun missingIdentityNeverBorrowsSomeoneElsesCamera() {
        assertNull(matchingLiveParticipantIndex("missing", listOf("other")))
        assertNull(matchingLiveParticipantIndex("", listOf("")))
    }
}
