package conectate.com.data.repository

import conectate.com.data.model.VoiceParticipant
import conectate.com.data.model.VoiceRoom
import conectate.com.data.model.VoiceStageRoles
import org.junit.Assert.*
import org.junit.Test

class VoiceRoomAdmissionStateTest {
    private val stage = VoiceRoom(id = "room", creatorId = "owner", roomMode = "stage")
    private val people = listOf(VoiceParticipant("viewer", "Viewer"), VoiceParticipant("owner", "Owner"))

    @Test fun stageVisitorStartsAsAudience() {
        assertEquals(VoiceStageRoles.AUDIENCE, initialVoiceParticipantRole(stage, "viewer"))
    }

    @Test fun stageOwnerStartsAsModeratorAndCasualVisitorAsSpeaker() {
        assertEquals(VoiceStageRoles.MODERATOR, initialVoiceParticipantRole(stage, " OWNER "))
        assertEquals(VoiceStageRoles.SPEAKER, initialVoiceParticipantRole(stage.copy(roomMode = "casual"), "viewer"))
    }

    @Test fun authoritativeRoleUpdatesOnlySelf() {
        val result = applyVoiceAdmissionState(people, "VIEWER", "speaker", false, true, false)
        assertEquals(people[1], result[1])
        assertEquals(VoiceStageRoles.SPEAKER, result[0].role)
        assertFalse(result[0].isMuted)
        assertTrue(result[0].handRaised)
    }

    @Test fun serverCannotUndoLocalMute() {
        assertTrue(applyVoiceAdmissionState(people, "viewer", "moderator", false, false, true)[0].isMuted)
    }

    @Test fun audienceAndUnknownRolesCannotPublish() {
        for (role in listOf("audience", "pending", "", "admin")) {
            val self = applyVoiceAdmissionState(people, "viewer", role, false, false, false)[0]
            assertEquals(VoiceStageRoles.AUDIENCE, self.role)
            assertTrue(self.isMuted)
        }
    }

    @Test fun missingAccountDoesNotModifySnapshot() {
        assertSame(people, applyVoiceAdmissionState(people, "", "speaker", false, false, false))
    }
}
