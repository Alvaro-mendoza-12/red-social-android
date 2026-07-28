package conectate.com.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStagePolicyTest {

    @Test
    fun casualAndStageModesRemainDistinct() {
        assertFalse(VoiceRoom(roomMode = VoiceRoomModes.CASUAL).isStageMode())
        assertTrue(VoiceRoom(roomMode = VoiceRoomModes.STAGE).isStageMode())
        assertTrue(VoiceRoom(roomMode = "STAGE").isStageMode())
    }

    @Test
    fun onlyModeratorsAndSpeakersCanPublishOnStage() {
        assertTrue(VoiceParticipant(id = "mod", name = "Mod", role = VoiceStageRoles.MODERATOR).canPublishOnStage())
        assertTrue(VoiceParticipant(id = "speaker", name = "Speaker", role = VoiceStageRoles.SPEAKER).canPublishOnStage())
        assertFalse(VoiceParticipant(id = "audience", name = "Audience", role = VoiceStageRoles.AUDIENCE).canPublishOnStage())
    }
}
