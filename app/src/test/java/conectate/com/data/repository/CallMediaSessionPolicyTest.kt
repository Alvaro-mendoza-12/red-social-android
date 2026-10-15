package conectate.com.data.repository

import conectate.com.data.model.ActiveCall
import conectate.com.data.model.CallConnectionState
import org.junit.Assert.*
import org.junit.Test

class CallMediaSessionPolicyTest {
    private val active=ActiveCall(callId="call-a",title="Prueba",initiatorUserId="qa",connectionState=CallConnectionState.CONNECTING)
    @Test fun anAbsentOrEndedCallCannotRefreshMedia() {
        assertFalse(canRefreshCallMedia(null))
        assertFalse(canRefreshCallMedia(active.copy(connectionState=CallConnectionState.IDLE)))
        assertFalse(canRefreshCallMedia(active.copy(connectionState=CallConnectionState.ENDED)))
    }
    @Test fun workFromAnOlderCallCannotRefreshItsReplacement() {
        assertFalse(canRefreshCallMedia(active,"call-b"))
        assertTrue(canRefreshCallMedia(active,"call-a"))
        assertTrue(canRefreshCallMedia(active.copy(connectionState=CallConnectionState.CONNECTED),"CALL-A"))
    }
    @Test fun reconnectionPreservesTheOriginalDuration() {
        assertEquals(1_000L, connectedCallStartTime(active.copy(
            connectionState=CallConnectionState.RECONNECTING, startedAtEpochMs=1_000L), 8_000L))
    }
    @Test fun firstConnectionStartsTheDuration() {
        assertEquals(8_000L, connectedCallStartTime(active, 8_000L))
    }
    @Test fun signalingRejoinRenegotiatesAnExistingPeer() {
        assertTrue(shouldRenegotiateCallParticipant(existingRemoteSocket=true, signalingRejoined=true))
        assertFalse(shouldRenegotiateCallParticipant(existingRemoteSocket=true, signalingRejoined=false))
    }
    @Test fun newRemotePeersAlwaysNegotiate() {
        assertTrue(shouldRenegotiateCallParticipant(existingRemoteSocket=false, signalingRejoined=false))
        assertTrue(shouldRenegotiateCallParticipant(existingRemoteSocket=false, signalingRejoined=true))
    }
}
