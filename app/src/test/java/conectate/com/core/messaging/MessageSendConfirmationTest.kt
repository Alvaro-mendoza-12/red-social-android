package conectate.com.core.messaging

import org.junit.Assert.*
import org.junit.Test

class MessageSendConfirmationTest {
    @Test fun acknowledgedStatesRequireASynchronizedRecord() {
        for (state in listOf("sent", "delivered", "read", " SENT ")) {
            assertTrue(messageSendConfirmation(true, state).isSuccess)
            assertTrue(messageSendConfirmation(false, state).isFailure)
        }
    }
    @Test fun queuedOrUnknownStatesNeverCompleteTheShareSelector() {
        for (state in listOf("sending", "pending", "failed", "", "unknown"))
            assertTrue(messageSendConfirmation(true, state).isFailure)
    }
    @Test fun pendingActionsAndRecalledMessagesRemainUnconfirmed() {
        for (action in listOf("send", "delete", "edit"))
            assertTrue(messageSendConfirmation(true, "sent", action).isFailure)
        assertTrue(messageSendConfirmation(true, "sent", deletedForAll = true).isFailure)
    }
}
