package conectate.com.presentation.messages

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisiblePollRefreshControllerTest {
    @Test fun `only visible polls are requested and never more than six`() = runTest {
        val ids = visiblePollIds(listOf("a" to "poll1", "b" to "poll2", "c" to null), setOf("b", "c"))
        assertEquals(setOf("poll2"), ids)
        val requested = mutableListOf<String>()
        val refresh = VisiblePollRefreshController(backgroundScope, { true },
            { id -> requested += id; Result.success(listOf(1)) }, { _, _ -> })
        refresh.update(true, "group", (1..20).map { "poll$it" }.toSet())
        runCurrent()
        assertEquals(6, requested.size)
        refresh.update(false, "group", ids)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(6, requested.size)
    }

    @Test fun `nonempty state is refreshed and a realtime event wakes it immediately`() = runTest {
        var calls = 0
        val applied = mutableListOf<Int>()
        val refresh = VisiblePollRefreshController(backgroundScope, { true },
            { Result.success(listOf(++calls)) }, { _, state -> applied += state.single() })
        refresh.update(true, "group", setOf("poll")); runCurrent()
        assertEquals(listOf(1), applied)
        refresh.requestRefresh("other"); runCurrent()
        assertEquals(1, calls)
        refresh.requestRefresh("poll"); runCurrent()
        assertEquals(listOf(1, 2), applied)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(1, 2, 3), applied)
    }

    @Test fun `a fetch started before voting cannot replace the optimistic vote`() = runTest {
        val oldResponse = CompletableDeferred<List<Int>>()
        var calls = 0
        val applied = mutableListOf<Int>()
        val refresh = VisiblePollRefreshController(backgroundScope, { true },
            { Result.success(if (++calls == 1) oldResponse.await() else listOf(2)) },
            { _, state -> applied += state.single() })
        refresh.update(true, "group", setOf("poll")); runCurrent()
        refresh.voteStarted("poll")
        oldResponse.complete(listOf(0)); runCurrent()
        assertTrue(applied.isEmpty())
        refresh.voteFinished("poll"); runCurrent()
        assertEquals(listOf(2), applied)
    }

    @Test fun `a late response cannot write into another room even if cancellation was ignored`() = runTest {
        val old = CompletableDeferred<List<Int>>()
        val applied = mutableListOf<Int>()
        var calls = 0
        val refresh = VisiblePollRefreshController(backgroundScope, { true },
            { Result.success(if (++calls == 1) withContext(NonCancellable) { old.await() } else listOf(2)) },
            { _, state -> applied += state.single() })
        refresh.update(true, "first", setOf("poll")); runCurrent()
        refresh.update(true, "second", setOf("poll")); runCurrent()
        old.complete(listOf(1)); runCurrent()
        assertEquals(listOf(2), applied)
    }

    @Test fun `an account change rejects pending state and stops future requests`() = runTest {
        val result = CompletableDeferred<List<Int>>()
        var current = true
        var calls = 0
        val applied = mutableListOf<Int>()
        val refresh = VisiblePollRefreshController(backgroundScope, { current },
            { calls++; Result.success(result.await()) }, { _, state -> applied += state.single() })
        refresh.update(true, "group", setOf("poll")); runCurrent()
        current = false; result.complete(listOf(1)); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue(applied.isEmpty())
        assertEquals(1, calls)
    }

    @Test fun `polls leaving the viewport stop fetching and recover when visible again`() = runTest {
        val calls = mutableListOf<String>()
        val refresh = VisiblePollRefreshController(backgroundScope, { true },
            { calls += it; Result.success(listOf(1)) }, { _, _ -> })
        refresh.update(true, "group", setOf("first")); runCurrent()
        refresh.update(true, "group", setOf("second")); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("first", "second", "second"), calls)
        refresh.update(true, "group", setOf("first")); runCurrent()
        assertEquals("first", calls.last())
    }

    @Test fun `failed initial requests back off rather than hammer the server`() = runTest {
        var calls = 0
        val refresh = VisiblePollRefreshController<Int>(backgroundScope, { true },
            { calls++; Result.failure(IllegalStateException()) }, { _, _ -> })
        refresh.update(true, "group", setOf("poll")); runCurrent()
        advanceTimeBy(999); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(1_999); runCurrent(); assertEquals(2, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(3, calls)
        refresh.stop()
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, calls)
    }
}
