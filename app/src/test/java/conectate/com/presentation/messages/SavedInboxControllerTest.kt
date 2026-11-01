package conectate.com.presentation.messages

import conectate.com.data.localfallback.GlobalSavedMessage
import conectate.com.data.localfallback.GlobalSavedMessagesResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SavedInboxControllerTest {
    private fun item(id: String) = GlobalSavedMessage(roomKind = "chat", roomId = "room", roomName = "Sala", messageId = id)
    private fun page(vararg ids: String, cursor: String? = null) = GlobalSavedMessagesResponse(ids.map(::item), cursor)

    @Test fun failedRefreshKeepsTheConfirmedCollectionAndCanBeRetried() = runTest {
        var online = true
        val inbox = SavedInboxController(backgroundScope, { "a" },
            read = { _, _, _ -> if (online) Result.success(page("one")) else Result.failure(IllegalStateException("Sin conexión.")) },
            removeMarker = { _, _ -> Result.success(Unit) })
        inbox.bindAccount(); runCurrent()
        online = false
        inbox.refresh()
        assertEquals(listOf(item("one")), inbox.state.value.items)
        runCurrent()
        assertEquals(listOf(item("one")), inbox.state.value.items)
        assertNotNull(inbox.state.value.error)
        assertFalse(inbox.state.value.loading)
        online = true
        inbox.refresh(); runCurrent()
        assertNull(inbox.state.value.error)
    }

    @Test fun initialFailureIsNotAConfirmedEmptyCollection() = runTest {
        val inbox = SavedInboxController(backgroundScope, { "a" },
            read = { _, _, _ -> throw IllegalStateException("No se pudo consultar.") }, removeMarker = { _, _ -> Result.success(Unit) })
        inbox.bindAccount(); runCurrent()
        assertTrue(inbox.state.value.items.isEmpty())
        assertNotNull(inbox.state.value.error)
        assertFalse(inbox.state.value.loading)
    }

    @Test fun paginationSendsOncePerCursorAndDoesNotDuplicateRows() = runTest {
        val next = CompletableDeferred<GlobalSavedMessagesResponse>()
        val cursors = mutableListOf<String?>()
        val inbox = SavedInboxController(backgroundScope, { "a" }, read = { _, _, cursor ->
            cursors += cursor
            Result.success(if (cursor == null) page("one", "one", cursor = "next") else next.await())
        }, removeMarker = { _, _ -> Result.success(Unit) })
        inbox.bindAccount(); runCurrent()
        assertEquals(1, inbox.state.value.items.size)
        inbox.loadMore(); inbox.loadMore(); runCurrent()
        assertEquals(listOf(null, "next"), cursors)
        next.complete(page("one", "two")); runCurrent()
        assertEquals(listOf("one", "two"), inbox.state.value.items.map { it.messageId })
    }

    @Test fun anOldFilterResponseCannotReplaceTheCurrentSearch() = runTest {
        val old = CompletableDeferred<GlobalSavedMessagesResponse>()
        val inbox = SavedInboxController(backgroundScope, { "a" }, read = { _, filters, _ ->
            Result.success(if (filters.search.isEmpty()) withContext(NonCancellable) { old.await() } else page("new"))
        }, removeMarker = { _, _ -> Result.success(Unit) })
        inbox.bindAccount(); runCurrent()
        inbox.setFilters(search = "nuevo"); runCurrent()
        old.complete(page("old")); runCurrent()
        assertEquals("nuevo", inbox.state.value.search)
        assertEquals(listOf(item("new")), inbox.state.value.items)
    }

    @Test fun accountChangesHideCachedContentBeforeCollectorsAndRejectLatePages() = runTest {
        var account = "a"
        val old = CompletableDeferred<GlobalSavedMessagesResponse>()
        var reads = 0
        val inbox = SavedInboxController(backgroundScope, { account }, read = { owner, _, _ ->
            reads++
            Result.success(if (owner == "a" && reads > 1) withContext(NonCancellable) { old.await() } else page(owner))
        }, removeMarker = { _, _ -> Result.success(Unit) })
        inbox.bindAccount(); runCurrent()
        inbox.refresh(); runCurrent()
        account = "b"
        val hidden = inbox.state.value.forAccount(account)
        assertTrue(hidden.items.isEmpty()); assertTrue(hidden.rooms.isEmpty())
        inbox.bindAccount(); runCurrent()
        old.complete(page("a-private")); runCurrent()
        assertEquals("b", inbox.state.value.accountId)
        assertEquals(listOf(item("b")), inbox.state.value.items)
    }

    @Test fun removalWaitsForAckAndFailureKeepsARetryableItem() = runTest {
        var ack = CompletableDeferred<Result<Unit>>()
        var writes = 0
        val inbox = SavedInboxController(backgroundScope, { "a" }, read = { _, _, _ -> Result.success(page("one")) },
            removeMarker = { _, _ -> writes++; ack.await() })
        inbox.bindAccount(); runCurrent()
        inbox.remove(item("one"), "a"); inbox.remove(item("one"), "a"); runCurrent()
        assertEquals(1, writes)
        assertEquals(1, inbox.state.value.items.size)
        assertTrue(item("one").savedInboxKey in inbox.state.value.removing)
        ack.complete(Result.failure(IllegalStateException("No se pudo quitar."))); runCurrent()
        assertEquals(1, inbox.state.value.items.size)
        assertTrue(inbox.state.value.removing.isEmpty())
        assertNotNull(inbox.state.value.error)
        ack = CompletableDeferred()
        inbox.remove(item("one"), "a"); runCurrent()
        ack.complete(Result.success(Unit)); runCurrent()
        assertEquals(2, writes)
        assertTrue(inbox.state.value.items.isEmpty())
        assertNull(inbox.state.value.error)
    }

    @Test fun anOutgoingRemovalCannotAlterAnotherAccountsIdenticalBookmark() = runTest {
        var account = "a"
        var writes = 0
        val ack = CompletableDeferred<Unit>()
        val inbox = SavedInboxController(backgroundScope, { account }, read = { _, _, _ -> Result.success(page("same")) },
            removeMarker = { _, _ -> writes++; withContext(NonCancellable) { ack.await() }; Result.success(Unit) })
        inbox.bindAccount(); runCurrent()
        inbox.remove(item("same"), "a"); runCurrent()
        account = "b"; inbox.bindAccount(); runCurrent()
        inbox.remove(item("same"), "a")
        ack.complete(Unit); runCurrent()
        assertEquals(1, writes)
        assertEquals(listOf(item("same")), inbox.state.value.items)
        assertTrue(inbox.state.value.removing.isEmpty())
    }

    @Test fun aRefreshBegunBeforeRemovalCannotResurrectTheBookmark() = runTest {
        var reads = 0
        val old = CompletableDeferred<GlobalSavedMessagesResponse>()
        val inbox = SavedInboxController(backgroundScope, { "a" }, read = { _, _, _ ->
            Result.success(if (++reads == 1) page("one") else withContext(NonCancellable) { old.await() })
        }, removeMarker = { _, _ -> Result.success(Unit) })
        inbox.bindAccount(); runCurrent()
        inbox.refresh(); runCurrent()
        inbox.remove(item("one"), "a"); runCurrent()
        old.complete(page("one")); runCurrent()
        assertTrue(inbox.state.value.items.isEmpty())
        assertFalse(inbox.state.value.loading)
    }

    @Test fun aStalledReadStopsWithFeedbackAndLogoutClearsAllPrivateFilters() = runTest {
        var account: String? = "a"
        val inbox = SavedInboxController(backgroundScope, { account }, read = { _, _, _ -> awaitCancellation() },
            removeMarker = { _, _ -> Result.success(Unit) }, timeoutMillis = 100)
        inbox.bindAccount(); runCurrent()
        advanceTimeBy(101); runCurrent()
        assertFalse(inbox.state.value.loading)
        assertNotNull(inbox.state.value.error)
        inbox.setFilters(search = "texto privado"); runCurrent()
        account = null; inbox.bindAccount(); runCurrent()
        assertEquals(SavedInboxState(), inbox.state.value)
    }

    @Test fun aRemovalAckDoesNotCancelTheNewFiltersReadOrPublishAFalseEmptyResult() = runTest {
        val filtered = CompletableDeferred<GlobalSavedMessagesResponse>()
        val removed = CompletableDeferred<Unit>()
        val inbox = SavedInboxController(backgroundScope, { "a" }, read = { _, filters, _ ->
            Result.success(if (filters.search.isEmpty()) page("one") else filtered.await())
        }, removeMarker = { _, _ -> removed.await(); Result.success(Unit) })
        inbox.bindAccount(); runCurrent()
        inbox.remove(item("one"), "a"); runCurrent()
        inbox.setFilters(search = "otro"); runCurrent()
        removed.complete(Unit); runCurrent()
        assertTrue(inbox.state.value.loading)
        filtered.complete(page("other")); runCurrent()
        assertEquals(listOf(item("other")), inbox.state.value.items)
        assertFalse(inbox.state.value.loading)
    }

    @Test fun aBookmarkSavedAgainAfterRemovalCanReappearInANewerServerRead() = runTest {
        val inbox = SavedInboxController(backgroundScope, { "a" }, read = { _, _, _ -> Result.success(page("one")) },
            removeMarker = { _, _ -> Result.success(Unit) })
        inbox.bindAccount(); runCurrent()
        inbox.remove(item("one"), "a"); runCurrent()
        assertTrue(inbox.state.value.items.isEmpty())
        inbox.refresh(); runCurrent()
        assertEquals(listOf(item("one")), inbox.state.value.items)
    }
}
