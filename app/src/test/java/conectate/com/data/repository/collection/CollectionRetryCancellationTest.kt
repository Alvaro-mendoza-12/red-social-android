package conectate.com.data.repository.collection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionRetryCancellationTest {
    private var confirmations = 0
    private var failures = 0
    private var classifications = 0

    private suspend fun send(action: suspend () -> Result<Unit>): Result<Unit> =
        CollectionPendingMessageRetrySupport.retryPendingMessageSend(
            messageId = "synthetic-message", maxAttempts = 1, sendAction = action,
            isDuplicateKeyError = { classifications++; false },
            isRetryableNetworkError = { classifications++; false },
            onConfirmedSuccess = { confirmations++ },
            onDuplicateTreatedAsSuccess = { confirmations++ },
            onTerminalFailure = { failures++ }, failureMessage = "send failed",
        )

    @Test fun thrownCancellationDoesNotMarkTheMessageFailed() = runTest {
        val cancellation = CancellationException("account changed")
        try {
            send { throw cancellation }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(0, failures)
        assertEquals(0, confirmations)
        assertEquals(0, classifications)
    }

    @Test fun cancellationWrappedInResultDoesNotMarkTheMessageFailed() = runTest {
        val cancellation = CancellationException("screen closed")
        try {
            send { Result.failure(cancellation) }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(0, failures)
        assertEquals(0, confirmations)
        assertEquals(0, classifications)
    }

    @Test fun lateSuccessAfterCancellationDoesNotPublishAConfirmation() = runTest {
        val job = launch {
            send { cancel(); Result.success(Unit) }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, confirmations)
        assertEquals(0, failures)
    }

    @Test fun lateFailureAfterCancellationDoesNotUpdateTheMessage() = runTest {
        val job = launch {
            send { cancel(); Result.failure(java.io.IOException("network closed")) }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, failures)
        assertEquals(0, classifications)
    }

    @Test fun ordinaryFailuresStillReachTheFailureCallback() = runTest {
        val error = java.io.IOException("offline")
        assertSame(error, send { Result.failure(error) }.exceptionOrNull())
        assertEquals(1, failures)
        assertEquals(0, confirmations)
    }

    @Test fun normalSuccessStillConfirmsDelivery() = runTest {
        assertTrue(send { Result.success(Unit) }.isSuccess)
        assertEquals(1, confirmations)
        assertEquals(0, failures)
    }

    @Test fun deleteCancellationNeverEntersTheNetworkRetryPolicy() = runTest {
        val cancellation = CancellationException("account changed")
        try {
            CollectionDeleteRetrySupport.retryRemoteDelete(
                logTag = "test", messageId = "synthetic-message",
                remoteDelete = { throw cancellation },
                isRetryableNetworkError = { classifications++; false },
            )
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals(0, classifications)
    }

    @Test fun lateDeleteCompletionAfterCancellationDoesNotReportSuccess() = runTest {
        var confirmed = false
        val job = launch {
            val result = CollectionDeleteRetrySupport.retryRemoteDelete(
                logTag = "test", messageId = "synthetic-message",
                remoteDelete = { cancel() }, isRetryableNetworkError = { false },
            )
            confirmed = result.isSuccess
        }
        job.join()
        assertFalse(confirmed)
    }
}
