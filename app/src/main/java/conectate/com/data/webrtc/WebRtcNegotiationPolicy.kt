package conectate.com.data.webrtc

import org.webrtc.PeerConnection

/** Pure guard used by callbacks that may be re-entered by vendor WebRTC stacks. */
internal object WebRtcNegotiationPolicy {
    fun shouldDeferLocalOffer(
        makingOffer: Boolean,
        settingRemoteOffer: Boolean,
        settingRemoteAnswer: Boolean,
        signalingState: PeerConnection.SignalingState
    ): Boolean =
        makingOffer ||
            settingRemoteOffer ||
            settingRemoteAnswer ||
            signalingState != PeerConnection.SignalingState.STABLE
}
