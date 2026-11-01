package conectate.com.presentation.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReportSubmissionTest {
    @Test fun confirmationWaitsForTheServerAndDoubleTapSendsOnce() = runTest {
        val ack = CompletableDeferred<Result<Unit>>()
        var writes = 0
        val form = ReportSubmission(this, { true })
        val send: suspend () -> Result<Unit> = { writes++; ack.await() }
        form.submit(send)
        form.submit(send)
        assertTrue(form.state.value.isSubmitting)
        assertFalse(form.state.value.confirmed)
        runCurrent()
        assertEquals(1, writes)
        ack.complete(Result.success(Unit))
        runCurrent()
        assertTrue(form.state.value.confirmed)
        assertFalse(form.state.value.isSubmitting)
        form.submit(send)
        runCurrent()
        assertEquals(1, writes)
    }

    @Test fun failureKeepsTheFormRetryableInsteadOfConfirming() = runTest {
        val form = ReportSubmission(this, { true })
        form.submit { Result.failure(IllegalStateException("Inténtalo más tarde.")) }
        runCurrent()
        assertFalse(form.state.value.confirmed)
        assertEquals("Inténtalo más tarde.", form.state.value.error)
        form.submit { Result.success(Unit) }
        runCurrent()
        assertTrue(form.state.value.confirmed)
        assertNull(form.state.value.error)
    }

    @Test fun anUnexpectedFailureDoesNotLeaveTheFormSending() = runTest {
        val form = ReportSubmission(this, { true })
        form.submit { throw IllegalStateException("No se pudo leer la evidencia.") }
        runCurrent()
        assertFalse(form.state.value.isSubmitting)
        assertFalse(form.state.value.confirmed)
        assertNotNull(form.state.value.error)
    }

    @Test fun lateResponseCannotConfirmAnotherAccountsForm() = runTest {
        var account = "A"
        val ack = CompletableDeferred<Result<Unit>>()
        val form = ReportSubmission(this, { account == "A" })
        form.submit { ack.await() }
        runCurrent()
        account = "B"
        ack.complete(Result.success(Unit))
        runCurrent()
        assertEquals(ReportSubmissionState(), form.state.value)
        var writes = 0
        form.submit { writes++; Result.success(Unit) }
        runCurrent()
        assertEquals(0, writes)
    }

    @Test fun leavingTheFormDoesNotPublishFailureOrSuccess() = runTest {
        val owner = Job(coroutineContext[Job])
        val form = ReportSubmission(CoroutineScope(coroutineContext + owner), { true })
        form.submit { awaitCancellation() }
        runCurrent()
        owner.cancel()
        owner.join()
        assertEquals(ReportSubmissionState(), form.state.value)
    }

    @Test fun wrappedCancellationIsNotTreatedAsAUserFacingFailure() = runTest {
        val form = ReportSubmission(this, { true })
        form.submit { Result.failure(CancellationException("Leaving")) }
        runCurrent()
        assertEquals(ReportSubmissionState(), form.state.value)
    }

    @Test fun unresponsiveRequestStopsWithAnUnconfirmedRetry() = runTest {
        val form = ReportSubmission(this, { true }, timeoutMillis = 100)
        form.submit { awaitCancellation() }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertFalse(form.state.value.isSubmitting)
        assertFalse(form.state.value.confirmed)
        assertTrue(form.state.value.error.orEmpty().contains("confirmar"))
    }
}
