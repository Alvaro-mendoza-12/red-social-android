package conectate.com.data.model
import org.junit.Assert.*
import org.junit.Test
class DirectMessageRequestStateTest {
    @Test fun onlyAllowedStatesPermitSending() {
        listOf("open", "new", "pending_incoming").forEach { assertTrue(DirectMessageRequestState(it).permitsSending) }
        listOf("loading", "error", "pending_outgoing", "rejected", "blocked", "unavailable", "unexpected").forEach { assertFalse(DirectMessageRequestState(it).permitsSending) }
        assertTrue(DirectMessageRequestState("new").needsFirstMessageWarning)
        assertFalse(DirectMessageRequestState("open").needsFirstMessageWarning)
    }
}
