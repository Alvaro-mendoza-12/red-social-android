package conectate.com.presentation.posts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PostCommentReadTest {
    @Test fun aConfirmedEmptyThreadIsSuccessful() = runTest {
        assertEquals(emptyList<Any>(), readPostComments { flowOf(emptyList()) }.getOrThrow())
    }
    @Test fun serverFailureIsNotAnEmptyThread() = runTest {
        val error = java.io.IOException("HTTP 503")
        val caught = readPostComments { flow { throw error } }.exceptionOrNull()
        assertNotNull(caught)
        assertEquals(error.javaClass, caught!!.javaClass)
        assertEquals(error.message, caught.message)
        // Coroutine stack recovery may copy the exception across withTimeout.
        assertTrue(generateSequence(caught) { it.cause }.take(8).any { it === error })
    }
    @Test fun aSlowReadStopsWithARetryableError() = runTest {
        assertTrue(readPostComments(100) { flow { awaitCancellation() } }.exceptionOrNull() is java.io.IOException)
    }
    @Test fun leavingTheScreenDoesNotPublishATimeoutOrAnEmptyThread() = runTest {
        val started = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            readPostComments { flow { started.complete(Unit); awaitCancellation() } }
            published = true
        }
        started.await(); job.cancel(); job.join()
        assertFalse(published)
    }
    @Test fun aFailureCanBeRetriedWithoutChangingItsMeaning() = runTest {
        var available = false
        suspend fun read() = readPostComments { flow {
            if (!available) throw java.io.IOException("offline")
            emit(emptyList())
        } }
        assertTrue(read().isFailure)
        available = true
        assertTrue(read().isSuccess)
    }
}
