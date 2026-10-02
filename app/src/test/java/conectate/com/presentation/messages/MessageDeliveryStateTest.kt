package conectate.com.presentation.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageDeliveryStateTest {
    @Test fun queuedAndInFlightMessagesWaitWithoutAManualRetry() {
        for (status in listOf("pending", "sending", " SENDING ")) {
            assertEquals(MessageDeliveryState.WAITING, messageDeliveryState(status, null))
        }
    }

    @Test fun onlyFiniteUploadProgressShowsTheUploadControl() {
        for (progress in listOf(-0.1f, 0f, 0.5f, 1f, 1.1f)) {
            assertEquals(MessageDeliveryState.UPLOADING, messageDeliveryState("sending", progress))
        }
        for (progress in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(MessageDeliveryState.WAITING, messageDeliveryState("pending", progress))
        }
    }

    @Test fun failureOverridesAnOldUploadProgressValue() {
        assertEquals(MessageDeliveryState.FAILED, messageDeliveryState(" FAILED ", 1f))
    }

    @Test fun acknowledgedMessagesNeverShowStaleUploadOrRetryControls() {
        for (status in listOf("sent", "delivered", "received", "read")) {
            assertEquals(MessageDeliveryState.COMPLETE, messageDeliveryState(status, 0.5f))
        }
    }
}
