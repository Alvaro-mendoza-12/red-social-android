package conectate.com.data.livekit

import org.junit.Assert.*
import org.junit.Test

class LiveCollaborationParticipantCleanupTest {
    @Test fun aNewConfirmedAttemptInTheSameLiveKeepsItsState() {
        assertFalse(shouldClearPreviousCollaborationParticipant("live", "owner", "live", "owner"))
    }
    @Test fun changingRoomsClearsThePreviousRoom() {
        assertTrue(shouldClearPreviousCollaborationParticipant("old", "owner", "new", "owner"))
    }
    @Test fun switchingAccountsClearsOnlyThePreviousAccount() {
        assertTrue(shouldClearPreviousCollaborationParticipant("live", "old", "live", "new"))
    }
    @Test fun noConfirmedConnectionClearsThePreviousParticipant() {
        assertTrue(shouldClearPreviousCollaborationParticipant("live", "owner", null, null))
    }
}
