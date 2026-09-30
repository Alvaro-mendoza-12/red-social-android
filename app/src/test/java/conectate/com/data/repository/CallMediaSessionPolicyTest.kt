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
}
