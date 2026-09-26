package conectate.com.data.music

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommunityMusicUploadCleanupTest {
    @Test fun `cancelled upload can roll back acknowledged media in reverse order`() = runTest {
        val deleted = mutableListOf<String>()
        val job = launch {
            currentCoroutineContext().cancel()
            cleanupUncommittedCommunityMusicUploads(listOf("audio" to "track", "thumbnails" to "cover")) { bucket, key ->
                delay(1)
                deleted += "$bucket/$key"
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("thumbnails/cover", "audio/track"), deleted)
    }

    @Test fun `failed cleanup continues and a stalled delete has a deadline`() = runTest {
        val attempted = mutableListOf<String>()
        cleanupUncommittedCommunityMusicUploads(listOf("audio" to "track", "thumbnails" to "cover")) { _, key ->
            attempted += key
            if (key == "cover") error("offline")
            awaitCancellation()
        }
        assertEquals(listOf("cover", "track"), attempted)
    }
}
