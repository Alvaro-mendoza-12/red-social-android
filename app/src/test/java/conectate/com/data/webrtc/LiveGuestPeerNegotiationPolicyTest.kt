package conectate.com.data.webrtc

import org.webrtc.PeerConnection.IceConnectionState
import org.junit.Assert.*
import org.junit.Test

class LiveGuestPeerNegotiationPolicyTest {
    @Test fun aNewPeerAlwaysNegotiates() {
        IceConnectionState.values().forEach { assertFalse(shouldSkipExistingPeerOffer(false, false, it)) }
    }
    @Test fun explicitRecoveryStillNegotiates() {
        IceConnectionState.values().forEach { assertFalse(shouldSkipExistingPeerOffer(true, true, it)) }
    }
    @Test fun healthyOrConnectingViewersDoNotRestartWhenPromoted() {
        listOf(IceConnectionState.NEW, IceConnectionState.CHECKING, IceConnectionState.CONNECTED, IceConnectionState.COMPLETED)
            .forEach { assertTrue(shouldSkipExistingPeerOffer(true, false, it)) }
    }
    @Test fun interruptedConnectionsCanRecover() {
        listOf(IceConnectionState.DISCONNECTED, IceConnectionState.FAILED)
            .forEach { assertFalse(shouldSkipExistingPeerOffer(true, false, it)) }
    }
    @Test fun closedOrUnknownConnectionsAreNotAssumedHealthy() {
        assertFalse(shouldSkipExistingPeerOffer(true, false, IceConnectionState.CLOSED))
        assertFalse(shouldSkipExistingPeerOffer(true, false, null))
    }
}
