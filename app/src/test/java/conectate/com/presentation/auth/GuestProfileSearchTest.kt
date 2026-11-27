package conectate.com.presentation.auth

import conectate.com.data.localfallback.GuestProfile
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuestProfileSearchTest {
    @Test fun onlyTheLatestNormalizedQueryIsSentAfterDebouncing() = runTest {
        val queries = mutableListOf<String>()
        val search = GuestProfileSearch(backgroundScope) { query -> queries += query; emptyList() }

        search.submit(" j ")
        assertNull(search.state.value)
        search.submit(" jo ")
        advanceTimeBy(200)
        search.submit(" jorge ")
        advanceTimeBy(349)
        assertTrue(queries.isEmpty())
        advanceTimeBy(2)
        search.submit("jorge")
        advanceTimeBy(400)

        assertEquals(listOf("jorge"), queries)
        assertEquals(GuestProfileSearchState.Results("jorge", emptyList()), search.state.value)
    }

    @Test fun aFailureIsDistinctFromNoResultsAndRetryUsesTheSameQuery() = runTest {
        val queries = mutableListOf<String>()
        val search = GuestProfileSearch(backgroundScope) { query ->
            queries += query
            if (queries.size == 1) throw IOException("transport details")
            listOf(GuestProfile("id"), GuestProfile("id"))
        }

        search.submit("luna")
        advanceTimeBy(351)
        val failure = search.state.value as GuestProfileSearchState.Failed
        assertTrue(failure.message.startsWith("No pudimos buscar personas"))
        search.retry(failure)
        search.retry(failure)
        runCurrent()

        assertEquals(listOf("luna", "luna"), queries)
        assertEquals(listOf(GuestProfile("id")), (search.state.value as GuestProfileSearchState.Results).profiles)
    }

    @Test fun anEarlierResponseDoesNotReplaceTheNewQuery() = runTest {
        val late = CompletableDeferred<List<GuestProfile>>()
        val search = GuestProfileSearch(backgroundScope) { query ->
            if (query == "an") withContext(NonCancellable) { late.await() }
            else listOf(GuestProfile(query))
        }

        search.submit("an")
        try {
            advanceTimeBy(351)
            search.submit("ana")
            advanceTimeBy(351)
        } finally {
            late.complete(listOf(GuestProfile("old")))
            runCurrent()
        }
        assertEquals(GuestProfileSearchState.Results("ana", listOf(GuestProfile("ana"))), search.state.value)
    }

    @Test fun leavingTheSectionCancelsTheReadAndAnOldRetryIsIgnored() = runTest {
        var attempts = 0
        var cancelled = false
        val search = GuestProfileSearch(backgroundScope) { query ->
            attempts++
            if (query == "offline") throw IOException()
            try { awaitCancellation() } finally { cancelled = true }
        }

        search.submit("offline")
        advanceTimeBy(351)
        val oldFailure = search.state.value as GuestProfileSearchState.Failed
        search.submit("other")
        advanceTimeBy(351)
        val pending = search.state.value
        search.retry(oldFailure)
        assertSame(pending, search.state.value)
        search.clear()
        runCurrent()
        assertNull(search.state.value)
        assertTrue(cancelled)
        assertEquals(2, attempts)
    }

    @Test fun aSlowReadStopsLoadingAndALongQueryIsBounded() = runTest {
        val search = GuestProfileSearch(backgroundScope) { awaitCancellation() }
        search.submit("a".repeat(80))
        advanceTimeBy(12_351)

        val failure = search.state.value as GuestProfileSearchState.Failed
        assertEquals("a".repeat(50), failure.query)
        assertTrue(failure.message.startsWith("No pudimos buscar personas"))
    }
}
