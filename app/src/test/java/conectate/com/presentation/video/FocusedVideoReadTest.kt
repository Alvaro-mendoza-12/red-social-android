package conectate.com.presentation.video

import conectate.com.core.network.ReadDeadlineExceededException
import conectate.com.data.model.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FocusedVideoReadTest {
    private fun video(id: String = "video-x") = Video(id = id, videoUrl = "https://example.test/$id.mp4")

    @Test fun normalizedLinkAndCanonicalServerIdResolveTheSameVideo() = runTest {
        var requested: String? = null
        val result = readFocusedVideo("  VIDEO-X ") { id -> requested = id; Result.success(video()) }
        assertEquals("video-x", requested)
        assertEquals("video-x", result.getOrThrow().id)
    }

    @Test fun stalledLookupFinishesWithRetryableFailureAndCancelsItsWork() = runTest {
        var cleaned = false
        val result = readFocusedVideo("video-x", timeoutMs = 100) {
            try { awaitCancellation() } finally { cleaned = true }
        }
        assertTrue(cleaned)
        assertTrue(result.exceptionOrNull() is ReadDeadlineExceededException)
        assertEquals("video-x", readFocusedVideo("video-x") { Result.success(video()) }.getOrThrow().id)
    }

    @Test fun blankWrongAndUnplayableResponsesCannotLeaveAnUnresolvableEntry() = runTest {
        var requests = 0
        assertTrue(readFocusedVideo("") { requests++; Result.success(video()) }.isFailure)
        assertEquals(0, requests)
        assertTrue(readFocusedVideo("video-x") { Result.success(video("video-y")) }.isFailure)
        assertTrue(readFocusedVideo("video-x") { Result.success(Video(id = "video-x")) }.isFailure)
    }

    @Test fun leavingTheEntryCancelsInsteadOfPublishingAnObsoleteFailure() = runTest {
        val entered = CompletableDeferred<Unit>()
        var published = false
        var cleaned = false
        val request = launch {
            readFocusedVideo("video-x") {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cleaned = true }
            }
            published = true
        }
        entered.await()
        request.cancel()
        request.join()
        assertTrue(request.isCancelled)
        assertTrue(cleaned)
        assertFalse(published)
    }
}
