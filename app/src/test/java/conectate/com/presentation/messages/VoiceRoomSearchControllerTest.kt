package conectate.com.presentation.messages
import kotlinx.coroutines.test.advanceTimeBy

import conectate.com.data.model.VoiceRoom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VoiceRoomSearchControllerTest {
    @Test fun anUnresponsiveLookupEndsInARetryableState() = runTest {
        val search = VoiceRoomSearchController(backgroundScope, { "account" }) { kotlinx.coroutines.awaitCancellation() }
        search.search("100031")
        runCurrent()
        assertEquals(VoiceRoomSearchState.Searching, search.state.value)
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(search.state.value is VoiceRoomSearchState.Failed)
    }
    private val room = VoiceRoom(id = "qa-room", name = "Prueba", creatorId = "qa-owner", isPublic = false, passwordRequired = true)

    @Test fun privateRoomWithoutDisclosedPasswordCanBeResolved() = runTest {
        var requested = ""
        val search = VoiceRoomSearchController(backgroundScope, { "qa-account" }) {
            requested = it; Result.success(room)
        }
        search.search(" 100031 ")
        runCurrent()
        assertEquals("100031", requested)
        assertEquals(VoiceRoomSearchState.Found(room), search.state.value)
        assertNull(room.password)
    }

    @Test fun emptyCodeDoesNotContactServer() = runTest {
        var requests = 0
        val search = VoiceRoomSearchController(backgroundScope, { "qa-account" }) { requests++; Result.success(room) }
        search.search(" ")
        runCurrent()
        assertTrue(search.state.value is VoiceRoomSearchState.Failed)
        assertEquals(0, requests)
    }

    @Test fun cancelledDialogCannotNavigateFromLateResult() = runTest {
        val response = CompletableDeferred<Result<VoiceRoom>>()
        val search = VoiceRoomSearchController(backgroundScope, { "qa-account" }) {
            withContext(NonCancellable) { response.await() }
        }
        search.search("100031")
        runCurrent()
        search.reset()
        response.complete(Result.success(room))
        runCurrent()
        assertEquals(VoiceRoomSearchState.Idle, search.state.value)
    }

    @Test fun replacedSearchIgnoresOldResult() = runTest {
        val old = CompletableDeferred<Result<VoiceRoom>>()
        val search = VoiceRoomSearchController(backgroundScope, { "qa-account" }) { id ->
            if (id == "old") withContext(NonCancellable) { old.await() }
            else Result.success(room.copy(id = "new"))
        }
        search.search("old")
        runCurrent()
        search.search("new")
        runCurrent()
        old.complete(Result.success(room))
        runCurrent()
        assertEquals("new", (search.state.value as VoiceRoomSearchState.Found).room.id)
    }

    @Test fun changedAccountDropsSearchResult() = runTest {
        var account = "qa-account"
        val response = CompletableDeferred<Result<VoiceRoom>>()
        val search = VoiceRoomSearchController(backgroundScope, { account }) { response.await() }
        search.search("100031")
        runCurrent()
        account = "other-account"
        response.complete(Result.success(room))
        runCurrent()
        assertEquals(VoiceRoomSearchState.Idle, search.state.value)
    }

    @Test fun failedSearchAllowsRetryAndDoesNotShowRawServerError() = runTest {
        var attempts = 0
        val search = VoiceRoomSearchController(backgroundScope, { "qa-account" }) {
            if (attempts++ == 0) Result.failure(IllegalStateException("private backend details")) else Result.success(room)
        }
        search.search("100031")
        runCurrent()
        assertFalse((search.state.value as VoiceRoomSearchState.Failed).message.contains("backend"))
        search.search("100031")
        runCurrent()
        assertEquals(VoiceRoomSearchState.Found(room), search.state.value)
    }
}
