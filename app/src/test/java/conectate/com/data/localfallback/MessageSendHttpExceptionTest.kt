package conectate.com.data.localfallback

import conectate.com.data.repository.collection.CollectionRepositorySupport
import org.junit.Assert.*
import org.junit.Test

class MessageSendHttpExceptionTest {
    private val transportHints = listOf("timeout", "connection", "network", "502", "503", "504")

    @Test fun pendingFriendRequestKeepsItsConflictStatusAndStopsAutomaticRetries() {
        val error = MessageSendHttpException(409, "FRIEND_REQUEST_PENDING", Exception("FRIEND_REQUEST_PENDING"))
        assertEquals(409, error.statusCode)
        assertEquals("FRIEND_REQUEST_PENDING", error.errorCode)
        assertFalse(error.isRetryable)
        assertFalse(CollectionRepositorySupport.isRetryableNetworkError(error, transportHints))
    }

    @Test fun accessRejectionRemainsTerminalEvenWhenItsTextLooksLikeANetworkProblem() {
        val error = MessageSendHttpException(403, "CHAT_ACCESS_DENIED", Exception("connection permission denied"))
        assertFalse(error.isRetryable)
        assertFalse(CollectionRepositorySupport.isRetryableNetworkError(error, transportHints))
    }

    @Test fun temporaryResponsesRetryWithoutDependingOnErrorText() {
        for (status in listOf(408, 425, 429, 500, 502, 503, 504, 599)) {
            val error = MessageSendHttpException(status, "", Exception("Intenta más tarde"))
            assertTrue("status=$status", error.isRetryable)
            assertTrue(CollectionRepositorySupport.isRetryableNetworkError(error, transportHints))
        }
    }

    @Test fun otherClientRejectionsDoNotRetryAutomatically() {
        for (status in listOf(400, 401, 404, 409, 410, 413, 415, 422)) {
            assertFalse("status=$status", isRetryableMessageHttpStatus(status))
        }
    }

    @Test fun originalFriendlyMessageAndCauseAreRetained() {
        val cause = Exception("Este archivo ya no está disponible")
        val error = MessageSendHttpException(403, "MEDIA_ACCESS_DENIED", cause)
        assertSame(cause, error.cause)
        assertEquals(cause.message, error.message)
    }

    @Test fun untypedTransportFailuresStillUseTheExistingNetworkPolicy() {
        assertTrue(CollectionRepositorySupport.isRetryableNetworkError(Exception("connection reset"), transportHints))
        assertFalse(CollectionRepositorySupport.isRetryableNetworkError(Exception("validation failed"), transportHints))
    }
}
