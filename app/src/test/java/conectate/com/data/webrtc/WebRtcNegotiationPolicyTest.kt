package conectate.com.data.webrtc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection

class WebRtcNegotiationPolicyTest {
    @Test
    fun `remote offer being applied blocks reentrant renegotiation`() {
        assertTrue(
            WebRtcNegotiationPolicy.shouldDeferLocalOffer(
                makingOffer = false,
                settingRemoteOffer = true,
                settingRemoteAnswer = false,
                signalingState = PeerConnection.SignalingState.STABLE
            )
        )
    }

    @Test
    fun `stable idle peer may create local offer`() {
        assertFalse(
            WebRtcNegotiationPolicy.shouldDeferLocalOffer(
                makingOffer = false,
                settingRemoteOffer = false,
                settingRemoteAnswer = false,
                signalingState = PeerConnection.SignalingState.STABLE
            )
        )
    }
}
