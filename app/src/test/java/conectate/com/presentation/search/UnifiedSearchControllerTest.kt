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
}
