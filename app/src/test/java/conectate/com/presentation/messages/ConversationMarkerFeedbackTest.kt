package conectate.com.presentation.messages
import org.junit.Assert.*
import org.junit.Test
class ConversationMarkerFeedbackTest {
    private val timeout=IllegalStateException("Request timeout has expired [url=https://server.invalid/v1/data/messages/markers/chat/private-id, request_timeout=25000 ms]")
    @Test fun passiveRefreshIsSilentWhileOffline() { assertNull(conversationMarkerFailureFeedback(timeout,false)) }
    @Test fun explicitRetryNeverExposesTheRawUrlOrTimeoutConfiguration() {
        val value=conversationMarkerFailureFeedback(timeout,true)!!
        assertFalse(value.contains("https://"));assertFalse(value.contains("request_timeout"));assertFalse(value.contains("private-id"))
    }
    @Test fun passiveAuthenticationFailureDoesNotBlockChatWithAPopup() {
        assertNull(conversationMarkerFailureFeedback(IllegalStateException("No token"),false))
    }
}
