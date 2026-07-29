package conectate.com.presentation.calls

import conectate.com.data.model.CallParticipant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallMediaVisibilityTest {
    @Test
    fun `video call invitation alone does not replace avatar with black renderer`() {
        val remote = CallParticipant(
            userId = "remote",
            name = "Prueba",
            socketId = "socket",
            isVideoEnabled = false
        )

        assertFalse(callHasVisibleVideo(hasLocalVideoTrack = false, remoteParticipants = listOf(remote)))
    }

    @Test
    fun `advertised remote camera switches from avatar to video`() {
        val remote = CallParticipant(
            userId = "remote",
            name = "Prueba",
            socketId = "socket",
            isVideoEnabled = true
        )

        assertTrue(callHasVisibleVideo(hasLocalVideoTrack = false, remoteParticipants = listOf(remote)))
    }
}
