package conectate.com.data.repository

import conectate.com.data.model.Post
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PostReactionWriteConfirmationTest {
    private val post = Post(id = "post-1", userId = "author", userName = "Author")
    private val droppedResponse = IOException("Response disconnected")

    @Test
    fun aConfirmedWriteNeedsNoRead() = runTest {
        val result = confirmPostReactionWrite(Result.success(null), post.id, "LOVE") {
            error("A successful response already confirms the write")
        }
        assertTrue(result.isSuccess)
    }

    @Test
    fun aLostResponseCanBeConfirmedWithOneRead() = runTest {
        var reads = 0
        val result = confirmPostReactionWrite(Result.failure<Any>(droppedResponse), post.id, "LOVE") {
            reads++
            Result.success(post.copy(likedByMe = true, myReaction = "LOVE"))
        }
        assertTrue(result.isSuccess)
        assertEquals(1, reads)
    }

    @Test
    fun aLostRemovalResponseUsesThePersistedEmptyState() = runTest {
        val result = confirmPostReactionWrite(Result.failure<Any>(droppedResponse), post.id, null) {
            Result.success(post)
        }
        assertTrue(result.isSuccess)
    }

    @Test
    fun theLegacyLikeFlagIsStillAReaction() = runTest {
        val result = confirmPostReactionWrite(Result.failure<Any>(droppedResponse), post.id, null) {
            Result.success(post.copy(likedByMe = true))
        }
        assertSame(droppedResponse, result.exceptionOrNull())
    }

    @Test
    fun aDifferentPostOrReactionCannotConfirmTheWrite() = runTest {
        for (observed in listOf(
            post.copy(id = "other", myReaction = "LOVE"),
            post.copy(myReaction = "WOW"),
        )) {
            val result = confirmPostReactionWrite(Result.failure<Any>(droppedResponse), post.id, "LOVE") {
                Result.success(observed)
            }
            assertSame(droppedResponse, result.exceptionOrNull())
        }
    }

    @Test
    fun aServerRejectionIsNeverRecoveredFromARead() = runTest {
        val denied = IllegalStateException("Request denied")
        val result = confirmPostReactionWrite(Result.failure<Any>(denied), post.id, "LOVE") {
            error("A rejected write must retain the original error")
        }
        assertSame(denied, result.exceptionOrNull())
    }

    @Test
    fun aFailedOrTimedOutReadPreservesTheOriginalFailure() = runTest {
        val failed = confirmPostReactionWrite(Result.failure<Any>(droppedResponse), post.id, "LOVE") {
            Result.failure(IOException("Read unavailable"))
        }
        val timedOut = confirmPostReactionWrite(Result.failure<Any>(droppedResponse), post.id, "LOVE") {
            awaitCancellation()
        }
        assertSame(droppedResponse, failed.exceptionOrNull())
        assertSame(droppedResponse, timedOut.exceptionOrNull())
        assertEquals(2_500L, testScheduler.currentTime)
    }

    @Test
    fun cancellationFromAccountChangeStopsConfirmation() = runTest {
        val cancellation = CancellationException("Account changed")
        val thrown = runCatching {
            confirmPostReactionWrite(Result.failure<Any>(droppedResponse), post.id, "LOVE") {
                throw cancellation
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(cancellation.message, thrown?.message)
    }
}
