package conectate.com.presentation.social

import conectate.com.data.model.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RankingReadTest {
    @Test fun slowOrFailedSectionDoesNotDiscardOtherRankingsAndReadFinishes() = runTest {
        val result = async { readRankings({ awaitCancellation() }, { Result.success(listOf(Video(id = "available"))) }, { Result.failure(Exception("offline")) }) }
        advanceTimeBy(10_001); runCurrent()
        val (users, videos, posts) = result.await()
        assertTrue(users.isFailure); assertTrue(posts.isFailure)
        assertEquals("available", videos.getOrThrow().single().id)
    }

    @Test fun loadingDoesNotFinishBeforeAllSectionsReturn() = runTest {
        val pending = CompletableDeferred<Result<List<Video>>>()
        val result = async { readRankings({ Result.success(emptyList()) }, { pending.await() }, { Result.success(emptyList()) }) }
        runCurrent(); assertFalse(result.isCompleted)
        pending.complete(Result.success(emptyList())); runCurrent()
        assertTrue(result.isCompleted)
    }
}
