package conectate.com.data.repository.user

import conectate.com.data.repository.BlockRelationship
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfirmedBlockRelationshipTest {
    @Test fun networkFailureDoesNotPublishAnUnblockAndAConfirmedChangeDoes() = runTest {
        val states = mutableListOf<BlockRelationship>()
        var reads = 0
        backgroundScope.launch {
            confirmedBlockRelationships({ "a" }, intervalMillis = 100) {
                when (++reads) {
                    1 -> Result.success(BlockRelationship(blockedByMe = true))
                    2 -> throw java.io.IOException("offline")
                    else -> Result.success(BlockRelationship())
                }
            }.collect { states += it }
        }
        runCurrent()
        assertEquals(listOf(BlockRelationship(blockedByMe = true)), states)
        advanceTimeBy(100); runCurrent()
        assertEquals(1, states.size)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(BlockRelationship(blockedByMe = true), BlockRelationship()), states)
    }

    @Test fun aLateResponseAfterAccountChangeIsNotPublished() = runTest {
        var account = "a"
        val response = CompletableDeferred<Result<BlockRelationship>>()
        val states = mutableListOf<BlockRelationship>()
        val job = backgroundScope.launch {
            confirmedBlockRelationships({ account }) { response.await() }.collect { states += it }
        }
        runCurrent()
        account = "b"
        response.complete(Result.success(BlockRelationship(blockedMe = true)))
        runCurrent()
        assertTrue(states.isEmpty())
        assertTrue(job.isCompleted)
    }

    @Test fun wrappedCancellationIsNotRetriedOrPublishedAsUnblocked() = runTest {
        var reads = 0
        val states = mutableListOf<BlockRelationship>()
        val job = backgroundScope.launch {
            confirmedBlockRelationships({ "a" }) {
                reads++
                Result.failure(CancellationException("leaving"))
            }.collect { states += it }
        }
        runCurrent()
        assertEquals(1, reads)
        assertTrue(job.isCancelled)
        assertTrue(states.isEmpty())
    }
}
