package conectate.com.presentation.social

import conectate.com.data.model.MomentHighlightItemPreview
import conectate.com.data.model.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MomentHighlightLoaderTest {
    private fun item(id: String, author: String = "author") = MomentHighlightItemPreview(
        id = id, highlightId = "highlight", momentId = id, mediaUrl = "video", mediaType = "video", userId = author,
    )

    @Test fun `items are visible before author enrichment finishes and authors fetched once per batch`() = runTest {
        val state = MutableStateFlow(MomentHighlightViewerUiState())
        val authors = CompletableDeferred<Result<List<User>>>()
        val requests = mutableListOf<List<String>>()
        val loader = MomentHighlightLoader(this, state,
            { Result.success(listOf(item("one"), item("two", "AUTHOR"), item("three", "other"))) },
            { requests += it; authors.await() })
        loader.load("highlight")
        runCurrent()
        assertFalse(state.value.loading)
        assertEquals(3, state.value.items.size)
        assertEquals(listOf(listOf("author", "other")), requests)
        authors.complete(Result.success(listOf(User(id = "author", display_name = "Author", avatar_url = "avatar"))))
        runCurrent()
        assertEquals("Author", state.value.items[0].userDisplayName)
        assertEquals("avatar", state.value.items[1].userAvatarUrl)
        assertNull(state.value.items[2].userDisplayName)
    }

    @Test fun `same highlight can retry after failure but successful load is not repeated automatically`() = runTest {
        val state = MutableStateFlow(MomentHighlightViewerUiState())
        var calls = 0
        val loader = MomentHighlightLoader(this, state,
            { if (++calls == 1) Result.failure(Exception("network")) else Result.success(listOf(item("one"))) },
            { Result.success(emptyList()) })
        loader.load("highlight"); runCurrent()
        assertNotNull(state.value.error)
        loader.load("highlight"); runCurrent()
        assertNull(state.value.error)
        loader.load("highlight"); runCurrent()
        assertEquals(2, calls)
        loader.load("highlight", retry = true); runCurrent()
        assertEquals(3, calls)
    }

    @Test fun `late items from a cancelled load cannot overwrite another highlight`() = runTest {
        val state = MutableStateFlow(MomentHighlightViewerUiState())
        val old = CompletableDeferred<Result<List<MomentHighlightItemPreview>>>()
        val loader = MomentHighlightLoader(this, state,
            { if (it == "old") withContext(NonCancellable) { old.await() } else Result.success(listOf(item("new"))) },
            { Result.success(emptyList()) })
        loader.load("old"); runCurrent()
        loader.load("new"); runCurrent()
        old.complete(Result.success(listOf(item("old")))); runCurrent()
        assertEquals("new", state.value.items.single().id)
    }

    @Test fun `late author response cannot restore the previous highlight`() = runTest {
        val state = MutableStateFlow(MomentHighlightViewerUiState())
        val old = CompletableDeferred<Result<List<User>>>()
        val loader = MomentHighlightLoader(this, state, { Result.success(listOf(item(it, it))) },
            { if (it.single() == "old") withContext(NonCancellable) { old.await() } else Result.success(emptyList()) })
        loader.load("old"); runCurrent()
        loader.load("new"); runCurrent()
        old.complete(Result.success(listOf(User(id = "old", display_name = "Old")))); runCurrent()
        assertEquals("new", state.value.items.single().id)
        assertNull(state.value.items.single().userDisplayName)
    }

    @Test fun `initial timeout ends loading and author timeout preserves playable items`() = runTest {
        val state = MutableStateFlow(MomentHighlightViewerUiState())
        val loader = MomentHighlightLoader(this, state,
            { if (it == "slow") awaitCancellation() else Result.success(listOf(item("one"))) },
            { awaitCancellation() }, deadlineMillis = 1000)
        loader.load("slow"); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertFalse(state.value.loading)
        assertNotNull(state.value.error)
        loader.load("fast"); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals("one", state.value.items.single().id)
        assertNull(state.value.error)
    }

    @Test fun `unknown author is not attributed to the logged in viewer`() = runTest {
        val state = MutableStateFlow(MomentHighlightViewerUiState())
        var called = false
        val loader = MomentHighlightLoader(this, state, { Result.success(listOf(item("one", ""))) },
            { called = true; Result.success(listOf(User(id = "viewer", display_name = "Viewer"))) })
        loader.load("highlight"); runCurrent()
        assertFalse(called)
        assertEquals("", state.value.items.single().userId)
        assertNull(state.value.items.single().userDisplayName)
    }
}
