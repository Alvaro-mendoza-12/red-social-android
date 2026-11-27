package conectate.com.data.service

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNotificationActionTest {
    @Test
    fun timeoutAfterDurableSaveAcceptsReplyAndReschedulesItsExistingOutboxEntry() = runTest {
        var recoveries = 0
        val accepted = acceptNotificationReply(
            send = { queued ->
                delay(5)
                queued()
                awaitCancellation()
            },
            resumePendingDelivery = { recoveries++ },
            timeoutMillis = 10,
        )

        assertTrue(accepted)
        assertEquals(1, recoveries)
    }

    @Test
    fun timeoutBeforeDurableSaveLeavesReplyAvailableForRetry() = runTest {
        val accepted = acceptNotificationReply(
            send = { awaitCancellation() },
            resumePendingDelivery = { error("There is no pending reply to recover") },
            timeoutMillis = 10,
        )

        assertFalse(accepted)
    }

    @Test
    fun confirmedReplyDoesNotScheduleAnotherDelivery() = runTest {
        val accepted = acceptNotificationReply(
            send = { Result.success(Unit) },
            resumePendingDelivery = { error("The reply already completed") },
            timeoutMillis = 10,
        )

        assertTrue(accepted)
    }

    @Test
    fun rejectionBeforeDurableSaveDoesNotClearTheNotification() = runTest {
        val accepted = acceptNotificationReply(
            send = { Result.failure<Unit>(IllegalStateException("No active session")) },
            resumePendingDelivery = { error("A rejected reply was never queued") },
            timeoutMillis = 10,
        )

        assertFalse(accepted)
    }

    @Test
    fun cancellingTheWorkerStillCancelsAQueuedReplyAction() = runTest {
        val failure = runCatching {
            withTimeout(5L) {
                acceptNotificationReply(
                    send = { queued ->
                        queued()
                        awaitCancellation()
                    },
                    resumePendingDelivery = { error("Cancellation is not the action's timeout") },
                    timeoutMillis = 10,
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException)
    }
}
