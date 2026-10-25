package conectate.com.presentation.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VideoCommentSessionTest {
    @Test fun lateVideoAResponseCannotReplaceVideoBComments() = runTest {
        val session = VideoCommentSession { "account" }
        session.open("A")
        val request = session.capture("A")!!
        val response = CompletableDeferred<List<String>>()
        var comments = listOf("B current comments")
        backgroundScope.launch {
            val fetched = response.await()
            if (session.isCurrent(request)) comments = fetched
        }
        runCurrent()
        session.open("B")
        response.complete(listOf("A stale comments")); runCurrent()
        assertEquals(listOf("B current comments"), comments)
    }

    @Test fun closingAndReopeningSameVideoInvalidatesPreviousRequests() {
        val session = VideoCommentSession { "account" }
        session.open("A")
        val oldRequest = session.capture("A")!!
        session.close(); session.open("A")
        assertFalse(session.isCurrent(oldRequest))
        assertTrue(session.isCurrent(session.capture("A")!!))
    }

    @Test fun accountChangeInvalidatesPendingWriteAndReadResults() {
        var account = "first"
        val session = VideoCommentSession { account }
        session.open("A")
        val request = session.capture("A")!!
        account = "second"
        assertFalse(session.isCurrent(request))
        assertNull(session.capture("A"))
    }
}
