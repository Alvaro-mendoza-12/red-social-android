package conectate.com.core.repository

import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.core.crash.CrashReporter
import conectate.com.core.logging.AppLogger
import conectate.com.core.result.AppError
import conectate.com.core.result.AppResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RepositoryCallHandlerTest {
    private class Diagnostics : AppLogger, CrashReporter {
        val logged = mutableListOf<Throwable?>()
        val reported = mutableListOf<Throwable>()
        override fun d(tag: String, message: String) = Unit
        override fun i(tag: String, message: String) = Unit
        override fun w(tag: String, message: String, throwable: Throwable?) = Unit
        override fun e(tag: String, message: String, throwable: Throwable?) { logged += throwable }
        override fun setUserId(userId: String?) = Unit
        override fun recordException(throwable: Throwable, extra: Map<String, String>) { reported += throwable }
        override fun recordFatalException(threadName: String, throwable: Throwable) = Unit
        override fun recordMessage(message: String, attributes: Map<String, String>) = Unit
    }
    private fun handler(dispatcher: CoroutineDispatcher, diagnostics: Diagnostics): RepositoryCallHandler {
        val provider = object : DispatcherProvider {
            override val io = dispatcher
            override val default = dispatcher
            override val main = dispatcher
            override val mainImmediate = dispatcher
        }
        return RepositoryCallHandler(provider, diagnostics, diagnostics)
    }

    @Test fun leavingAScreenDoesNotBecomeAnErrorOrACrashReport() = runTest {
        val diagnostics = Diagnostics()
        try {
            handler(StandardTestDispatcher(testScheduler), diagnostics).io<Unit>("notifications.fixture") {
                throw CancellationException("screen left")
            }
            fail("Cancellation must reach the caller")
        } catch (_: CancellationException) { }
        assertTrue(diagnostics.logged.isEmpty())
        assertTrue(diagnostics.reported.isEmpty())
    }

    @Test fun aConnectionFailureHasAUsableMessageAndKeepsTheDiagnosticCause() = runTest {
        val diagnostics = Diagnostics()
        val result = handler(StandardTestDispatcher(testScheduler), diagnostics).io<Unit>("notifications.fixture") {
            throw IOException("GET http://internal-host:3080 private details")
        }
        val error = (result as AppResult.Error).error
        assertTrue(error is AppError.Network)
        assertFalse(error.message.contains("internal-host"))
        assertTrue(error.message.contains("conexión"))
        assertTrue(error.cause is IOException)
        assertEquals(1, diagnostics.logged.size)
        assertEquals(1, diagnostics.reported.size)
    }

    @Test fun fatalRuntimeErrorsAreNotTurnedIntoAnOrdinaryRetryResult() = runTest {
        val diagnostics = Diagnostics()
        try {
            handler(StandardTestDispatcher(testScheduler), diagnostics).io<Unit>("notifications.fixture") {
                throw AssertionError("fatal fixture")
            }
            fail("A fatal error must propagate")
        } catch (expected: AssertionError) {
            assertEquals("fatal fixture", expected.message)
        }
        assertTrue(diagnostics.reported.isEmpty())
    }
}
