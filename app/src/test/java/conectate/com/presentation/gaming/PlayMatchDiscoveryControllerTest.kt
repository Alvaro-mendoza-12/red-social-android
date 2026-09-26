package conectate.com.presentation.gaming

import conectate.com.data.model.PlayMatchPlayersResponse
import conectate.com.data.model.PlayMatchProfile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayMatchDiscoveryControllerTest {
    @Test fun lateSearchCannotReplaceNewFilter() = runTest {
        val state = MutableStateFlow(PlayMatchUiState())
        val controller = PlayMatchDiscoveryController(this, state) { query ->
            if (query.game == "old") withContext(NonCancellable) { delay(2000) }
            Result.success(PlayMatchPlayersResponse(listOf(PlayMatchProfile(query.game.orEmpty()))))
        }
        controller.search("", "old", null)
        runCurrent()
        controller.search("", "new", null)
        advanceUntilIdle()
        assertEquals(listOf("new"), state.value.players.map { it.userId })
        assertFalse(state.value.isSearchingPlayers)
    }

    @Test fun oldPaginationCannotContaminateNewResults() = runTest {
        val state = MutableStateFlow(PlayMatchUiState())
        val controller = PlayMatchDiscoveryController(this, state) { query ->
            if (query.cursor != null) withContext(NonCancellable) { delay(2000) }
            Result.success(PlayMatchPlayersResponse(listOf(PlayMatchProfile(query.game.orEmpty())), "page-2"))
        }
        controller.search("", "old", null)
        runCurrent()
        controller.loadMore()
        runCurrent()
        controller.search("", "new", null)
        advanceUntilIdle()
        assertEquals(listOf("new"), state.value.players.map { it.userId })
    }

    @Test fun repeatedCursorStopsPaginationAndDuplicatesAreRemoved() = runTest {
        val state = MutableStateFlow(PlayMatchUiState())
        val controller = PlayMatchDiscoveryController(this, state) {
            Result.success(PlayMatchPlayersResponse(listOf(PlayMatchProfile("one"), PlayMatchProfile("one")), "same"))
        }
        controller.search("", null, null)
        runCurrent()
        controller.loadMore()
        advanceUntilIdle()
        assertEquals(1, state.value.players.size)
        assertNull(state.value.nextCursor)
    }

    @Test fun availabilityAndFiltersSurvivePagination() = runTest {
        val state = MutableStateFlow(PlayMatchUiState(availableNowOnly = true, regionFilter = "CO"))
        val calls = mutableListOf<PlayMatchPlayerQuery>()
        val controller = PlayMatchDiscoveryController(this, state) { query ->
            calls += query
            Result.success(PlayMatchPlayersResponse(listOf(PlayMatchProfile("one")), "next"))
        }
        controller.search(" ana ", "game", "PC")
        runCurrent()
        controller.loadMore()
        advanceUntilIdle()
        assertEquals(PlayMatchPlayerQuery("ana", "game", "PC", "CO", "next", true), calls.last())
    }

    @Test fun timeoutRestoresUsableSearch() = runTest {
        val state = MutableStateFlow(PlayMatchUiState())
        val controller = PlayMatchDiscoveryController(this, state) {
            delay(60_000)
            Result.success(PlayMatchPlayersResponse())
        }
        controller.search("ana", null, null)
        advanceUntilIdle()
        assertFalse(state.value.isSearchingPlayers)
        assertNotNull(state.value.error)
    }

    @Test fun pastedLongQueryAndItsNextPageUseTheSameSearchWhileInputKeepsSpaces() = runTest {
        val state = MutableStateFlow(PlayMatchUiState())
        val calls = mutableListOf<PlayMatchPlayerQuery>()
        val controller = PlayMatchDiscoveryController(this, state) { query ->
            calls += query
            Result.success(PlayMatchPlayersResponse(listOf(PlayMatchProfile("one")), "next"))
        }
        val pasted = "   " + "a".repeat(130)
        controller.search(pasted, null, null)
        runCurrent()
        controller.loadMore()
        advanceUntilIdle()
        assertEquals(pasted.take(120), state.value.query)
        assertEquals("a".repeat(117), calls.first().text)
        assertEquals(calls.first().text, calls.last().text)
        assertEquals("next", calls.last().cursor)
    }
}
