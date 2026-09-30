package conectate.com.data.webrtc

import org.webrtc.PeerConnection

/** Promoting an already connected viewer does not require new ICE credentials. */
internal fun shouldSkipExistingPeerOffer(
    reusedExistingPeer: Boolean,
    explicitRecovery: Boolean,
    iceState: PeerConnection.IceConnectionState?
): Boolean = reusedExistingPeer && !explicitRecovery && when (iceState) {
    PeerConnection.IceConnectionState.NEW,
    PeerConnection.IceConnectionState.CHECKING,
    PeerConnection.IceConnectionState.CONNECTED,
    PeerConnection.IceConnectionState.COMPLETED -> true
    else -> false
}
