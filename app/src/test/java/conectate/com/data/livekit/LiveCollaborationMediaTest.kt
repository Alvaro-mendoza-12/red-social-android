package conectate.com.data.livekit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveCollaborationMediaTest {
    @Test fun audioAndVideoStartTogetherInsteadOfAddingTheirWaits() = runTest {
        val entered = mutableSetOf<String>()
        applyCollaborationMedia(
            audio = { entered += "audio"; delay(500); assertTrue("video" in entered) },
            video = { entered += "video"; delay(800); assertTrue("audio" in entered) }
        )
        assertEquals(800L, testScheduler.currentTime)
        assertEquals(setOf("audio", "video"), entered)
    }

    @Test fun aFailedSourceCancelsAndJoinsItsSiblingBeforeReturning() = runTest {
        val videoStarted = CompletableDeferred<Unit>()
        var videoClosed = false
        try {
            applyCollaborationMedia(
                audio = { videoStarted.await(); error("audio failed") },
                video = { try { videoStarted.complete(Unit); awaitCancellation() } finally { videoClosed = true } }
            )
            fail("Expected failure")
        } catch (error: IllegalStateException) {
            assertEquals("audio failed", error.message)
            assertTrue(videoClosed)
        }
    }

    @Test fun cancellationRemainsCancellationAndBothSourcesAreClosed() = runTest {
        val started = CompletableDeferred<Unit>()
        var audioClosed = false
        var videoClosed = false
        var entered = 0
        val job = launch {
            applyCollaborationMedia(
                audio = { try { if (++entered == 2) started.complete(Unit); awaitCancellation() } finally { audioClosed = true } },
                video = { try { if (++entered == 2) started.complete(Unit); awaitCancellation() } finally { videoClosed = true } }
            )
        }
        started.await()
        job.cancel(CancellationException("viewer left"))
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(audioClosed)
        assertTrue(videoClosed)
    }
}
