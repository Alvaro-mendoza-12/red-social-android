package conectate.com.presentation.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnifiedSearchControllerTest {
    @Test fun debouncesAndNormalizesHandles() = runTest {
        val calls = mutableListOf<String>()
        val controller = UnifiedSearchController(this, { query -> calls += query; Result.success(listOf(query)) }, { Result.success(emptyList<String>()) })
        controller.search("ma")
        advanceTimeBy(100)
        controller.search(" @maria ")
        advanceUntilIdle()
        assertEquals(listOf("maria"), calls)
        assertEquals(listOf("maria"), controller.users.value)
        assertFalse(controller.loading.value)
    }

    @Test fun deletingToOneCharacterClearsStaleResults() = runTest {
        val controller = UnifiedSearchController(this, { Result.success(listOf(it)) }, { Result.success(listOf(it)) })
        controller.search("ana")
        advanceUntilIdle()
        controller.search("a")
        assertTrue(controller.users.value.isEmpty())
        assertTrue(controller.content.value.isEmpty())
        assertFalse(controller.loading.value)
    }

    @Test fun peopleAppearBeforeSlowContentAndFailureIsIndependent() = runTest {
        val releaseContent = CompletableDeferred<Unit>()
        val controller = UnifiedSearchController(this, { Result.success(listOf("ana")) }, {
            releaseContent.await()
            Result.failure<List<String>>(IllegalStateException("unavailable"))
        })
        controller.search("ana")
        advanceTimeBy(350)
        runCurrent()
        assertEquals(listOf("ana"), controller.users.value)
        assertTrue(controller.loading.value)
        releaseContent.complete(Unit)
        advanceUntilIdle()
        assertNotNull(controller.contentError.value)
        assertNull(controller.usersError.value)
        assertFalse(controller.loading.value)
    }

    @Test fun staleNonCooperativeResponseCannotReplaceNewerQuery() = runTest {
        val controller = UnifiedSearchController(this, { query ->
            if (query == "old") withContext(NonCancellable) { delay(2000) }
            Result.success(listOf(query))
        }, { Result.success(emptyList<String>()) })
        controller.search("old")
        advanceTimeBy(350)
        runCurrent()
        controller.search("new")
        advanceTimeBy(350)
        runCurrent()
        assertEquals(listOf("new"), controller.users.value)
        advanceUntilIdle()
        assertEquals(listOf("new"), controller.users.value)
    }

    @Test fun clearRejectsInFlightResponses() = runTest {
        val controller = UnifiedSearchController(this, { query ->
            withContext(NonCancellable) { delay(2000) }
            Result.success(listOf(query))
        }, { Result.success(emptyList<String>()) })
        controller.search("ana")
        advanceTimeBy(350)
        runCurrent()
        controller.clear()
        advanceUntilIdle()
        assertTrue(controller.users.value.isEmpty())
        assertFalse(controller.loading.value)
    }

    @Test fun readsRealPagesOnceAndDeduplicatesStableResults() = runTest {
        val offsets = mutableListOf<Int>()
        val controller = UnifiedSearchController(this, { Result.success(emptyList<String>()) },
            { error("The unpaged endpoint must not be called") }, findContentPage = { _, offset ->
                offsets += offset
                Result.success(if (offset == 0) SearchContentPage(listOf("a", "b"), 2)
                    else SearchContentPage(listOf("b", "c")))
            })
        controller.search("casas", immediate = true)
        advanceUntilIdle()
        assertTrue(controller.hasMoreContent.value)
        controller.loadMoreContent()
        controller.loadMoreContent()
        advanceUntilIdle()
        assertEquals(listOf(0, 2), offsets)
        assertEquals(listOf("a", "b", "c"), controller.content.value)
        assertFalse(controller.hasMoreContent.value)
    }

    @Test fun latePageCannotLeakIntoDifferentQueryOrClearedAccount() = runTest {
        val controller = UnifiedSearchController(this, { Result.success(emptyList<String>()) },
            { Result.success(emptyList<String>()) }, findContentPage = { query, offset ->
                if (offset > 0) withContext(NonCancellable) { delay(2000) }
                Result.success(SearchContentPage(listOf("$query:$offset"), if (offset == 0) 1 else null))
            })
        controller.search("old", immediate = true)
        runCurrent()
        controller.loadMoreContent()
        runCurrent()
        controller.search("new", immediate = true)
        advanceUntilIdle()
        assertEquals(listOf("new:0"), controller.content.value)
        controller.loadMoreContent()
        runCurrent()
        controller.clear()
        advanceUntilIdle()
        assertTrue(controller.content.value.isEmpty())
        assertFalse(controller.hasMoreContent.value)
        assertFalse(controller.loadingMore.value)
    }

    @Test fun failedNextPageKeepsResultsAndRetriesSameOffset() = runTest {
        var attempts = 0
        val controller = UnifiedSearchController(this, { Result.success(emptyList<String>()) },
            { Result.success(emptyList<String>()) }, findContentPage = { _, offset ->
                if (offset == 0) Result.success(SearchContentPage(listOf("first"), 1))
                else if (++attempts == 1) Result.failure(IllegalStateException("offline"))
                else Result.success(SearchContentPage(listOf("second")))
            })
        controller.search("música", immediate = true)
        advanceUntilIdle()
        controller.loadMoreContent()
        advanceUntilIdle()
        assertEquals(listOf("first"), controller.content.value)
        assertNotNull(controller.contentError.value)
        assertTrue(controller.hasMoreContent.value)
        controller.loadMoreContent()
        advanceUntilIdle()
        assertEquals(listOf("first", "second"), controller.content.value)
        assertNull(controller.contentError.value)
        assertFalse(controller.hasMoreContent.value)
    }

    @Test fun olderServerWithoutPaginationAndNonAdvancingOffsetsCannotLoop() = runTest {
        val controller = UnifiedSearchController(this, { Result.success(emptyList<String>()) },
            { Result.success(emptyList<String>()) }, findContentPage = { _, offset ->
                Result.success(SearchContentPage(listOf(offset.toString()), if (offset == 0) 1 else 1))
            })
        controller.search("tema", immediate = true)
        advanceUntilIdle()
        controller.loadMoreContent()
        advanceUntilIdle()
        assertFalse(controller.hasMoreContent.value)
        val oldServer = UnifiedSearchController(this, { Result.success(emptyList<String>()) },
            { Result.success(emptyList<String>()) }, findContentPage = { _, _ -> Result.success(SearchContentPage(listOf("legacy"))) })
        oldServer.search("tema", immediate = true)
        advanceUntilIdle()
        assertEquals(listOf("legacy"), oldServer.content.value)
        assertFalse(oldServer.hasMoreContent.value)
    }
}
