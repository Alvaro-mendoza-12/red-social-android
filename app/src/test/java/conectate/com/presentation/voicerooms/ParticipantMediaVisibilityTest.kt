package conectate.com.presentation.voicerooms

import conectate.com.data.model.VoiceParticipant
import org.junit.Assert.assertEquals
import org.junit.Test

class ParticipantMediaVisibilityTest {
    private val participant = VoiceParticipant(
        id = "User-1",
        name = "Prueba",
        avatarUrl = "https://example.invalid/avatar.jpg",
        socketId = "socket-1"
    )

    @Test
    fun `remote transceiver without advertised sharing keeps avatar visible`() {
        assertEquals(
            false to false,
            participantSharingState(participant, mapOf("socket-1" to (false to false)))
        )
    }

    @Test
    fun `sharing state resolves by normalized user id after reconnect`() {
        assertEquals(
            true to false,
            participantSharingState(participant, mapOf("user-1" to (true to false)))
        )
    }
}
