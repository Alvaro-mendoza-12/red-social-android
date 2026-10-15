package conectate.com.data.model
import org.junit.Assert.*
import org.junit.Test
class DirectMessageOfflineQueuePolicyTest {
    @Test fun unknownVerificationStillAllowsDurableLocalMessages() {
        for(s in listOf("loading","error","offline")) {
            val p=DirectMessageRequestState(s);assertTrue(p.permitsLocalQueue);assertFalse(p.permitsSending)
        }
    }
    @Test fun temporaryNetworkFailureDoesNotErasePreviouslyAcceptedConversation() {
        assertEquals("open",DirectMessageRequestState("open").afterVerificationFailure().status)
        assertEquals("offline",DirectMessageRequestState().afterVerificationFailure().status)
    }
    @Test fun knownRejectionsAndUnacceptedOutgoingRequestsRemainEnforced() {
        for(s in listOf("rejected","pending_outgoing")) {
            val p=DirectMessageRequestState(s);assertFalse(p.permitsLocalQueue);assertEquals(p,p.afterVerificationFailure())
        }
    }
    @Test fun knownFirstMessageStillRequiresTheRequestNotice() {
        val p=DirectMessageRequestState("new");assertTrue(p.permitsLocalQueue);assertTrue(p.needsFirstMessageWarning)
    }
}
