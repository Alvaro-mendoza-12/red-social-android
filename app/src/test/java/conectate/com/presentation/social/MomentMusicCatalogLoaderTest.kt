package conectate.com.presentation.social

import conectate.com.data.music.ApprovedMomentMusic
import conectate.com.data.music.MomentMusicCatalogPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MomentMusicCatalogLoaderTest {
    @Test
    fun `fast typing only sends the final query`() = runTest {
        val remote = Remote()
        val state = MutableStateFlow(MusicCatalogUiState())
        val loader = MomentMusicCatalogLoader(this, state, remote::fetch)
        loader.search("ro", "Todo", "community")
        runCurrent()
        advanceTimeBy(200)
        loader.search("rock", "Todo", "community")
        runCurrent()
        advanceTimeBy(349)
        assertTrue(remote.requests.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals("rock", remote.requests.single().query)
        remote.requests.single().complete("rock-song")
        runCurrent()
        assertEquals(listOf("rock-song"), state.value.items.map { it.id })
        assertFalse(state.value.isLoading)
    }

    @Test
    fun `late first page cannot replace a newer search`() = runTest {
        val remote = Remote(ignoreCancellation = true)
        val state = MutableStateFlow(MusicCatalogUiState())
        val loader = MomentMusicCatalogLoader(this, state, remote::fetch, debounceMillis = 0)
        loader.search("old", "Todo", "community")
        runCurrent()
        loader.search("new", "Todo", "community")
        runCurrent()
        remote.requests[1].complete("new-song")
        runCurrent()
        remote.requests[0].complete("old-song")
        runCurrent()
        assertEquals("new", state.value.query)
        assertEquals(listOf("new-song"), state.value.items.map { it.id })
        assertNull(state.value.error)
    }

    @Test
    fun `old page success and failure cannot touch a replacement query`() = runTest {
        for (lateFailure in listOf(false, true)) {
            val remote = Remote(ignoreCancellation = true)
            val state = MutableStateFlow(MusicCatalogUiState())
            val loader = MomentMusicCatalogLoader(this, state, remote::fetch, debounceMillis = 0)
            loader.search("old", "Todo", "community")
            runCurrent()
            remote.requests[0].complete("old-first", cursor = "old-next")
            runCurrent()
            loader.loadMore()
            runCurrent()
            loader.search("new", "Todo", "community")
            runCurrent()
            remote.requests[2].complete("new-first", cursor = "new-next")
            runCurrent()
            if (lateFailure) remote.requests[1].fail() else remote.requests[1].complete("old-next")
            runCurrent()
            assertEquals(listOf("new-first"), state.value.items.map { it.id })
            assertEquals("new-next", state.value.nextCursor)
            assertNull(state.value.error)
            assertNull(state.value.loadMoreError)
            assertFalse(state.value.isLoadingMore)
        }
    }

    @Test
    fun `generation changes immediately before new query debounce`() = runTest {
        val remote = Remote(ignoreCancellation = true)
        val state = MutableStateFlow(MusicCatalogUiState())
        val loader = MomentMusicCatalogLoader(this, state, remote::fetch)
        loader.search("", "Todo", "community")
        runCurrent()
        remote.requests[0].complete("first", cursor = "next")
        runCurrent()
        loader.loadMore()
        runCurrent()
        loader.search("new", "Todo", "community")
        runCurrent()
        remote.requests[1].complete("stale")
        runCurrent()
        assertTrue(state.value.items.isEmpty())
        assertTrue(state.value.isLoading)
        assertEquals("new", state.value.query)
        advanceTimeBy(350)
        runCurrent()
        remote.requests[2].complete("new")
        runCurrent()
    }

    @Test
    fun `changing category or section never appends old pages`() = runTest {
        for (changeSection in listOf(false, true)) {
            val remote = Remote(ignoreCancellation = true)
            val state = MutableStateFlow(MusicCatalogUiState())
            val loader = MomentMusicCatalogLoader(this, state, remote::fetch)
            loader.search("", "Todo", "community")
            runCurrent()
            remote.requests[0].complete("first", cursor = "next")
            runCurrent()
            loader.loadMore()
            runCurrent()
            loader.search("", if (changeSection) "Todo" else "Rock", if (changeSection) "mine" else "community")
            runCurrent()
            assertTrue(state.value.items.isEmpty())
            remote.requests[2].complete("new")
            remote.requests[1].complete("old")
            runCurrent()
            assertEquals(listOf("new"), state.value.items.map { it.id })
        }
    }

    @Test
    fun `refresh keeps loaded songs through failure then replaces on success`() = runTest {
        val remote = Remote()
        val state = MutableStateFlow(MusicCatalogUiState())
        val loader = MomentMusicCatalogLoader(this, state, remote::fetch)
        loader.search("", "Todo", "community")
        runCurrent()
        remote.requests[0].complete("first", cursor = "next")
        runCurrent()
        loader.search("", "Todo", "community")
        assertEquals(listOf("first"), state.value.items.map { it.id })
        assertTrue(state.value.isLoading)
        loader.loadMore()
        runCurrent()
        assertEquals(2, remote.requests.size)
        remote.requests[1].fail()
        runCurrent()
        assertEquals(listOf("first"), state.value.items.map { it.id })
        assertEquals("next", state.value.nextCursor)
        assertNotNull(state.value.error)
        assertFalse(state.value.isLoading)
        loader.search("", "Todo", "community")
        runCurrent()
        remote.requests[2].complete("fresh")
        runCurrent()
        assertEquals(listOf("fresh"), state.value.items.map { it.id })
        assertNull(state.value.error)
    }

    @Test
    fun `authoritative empty refresh removes stale songs`() = runTest {
        val remote = Remote()
        val state = MutableStateFlow(MusicCatalogUiState(items = listOf(song("old")), section = "community", nextCursor = "old-next"))
        val loader = MomentMusicCatalogLoader(this, state, remote::fetch)
        loader.search("", "Todo", "community")
        runCurrent()
        remote.requests.single().complete()
        runCurrent()
        assertTrue(state.value.items.isEmpty())
        assertNull(state.value.nextCursor)
        assertFalse(state.value.isLoading)
    }

    @Test
    fun `pagination is single flight deduplicated and stops on cursor cycles`() = runTest {
        val remote = Remote()
        val state = MutableStateFlow(MusicCatalogUiState())
        val loader = MomentMusicCatalogLoader(this, state, remote::fetch)
        loader.search("", "Todo", "community")
        runCurrent()
        remote.requests[0].complete("a", "a", cursor = "page-2")
        runCurrent()
        assertEquals(1, state.value.items.size)
        repeat(4) { loader.loadMore() }
        runCurrent()
        assertEquals(2, remote.requests.size)
        remote.requests[1].complete("a", "b", cursor = "page-3")
        runCurrent()
        loader.loadMore()
        runCurrent()
        remote.requests[2].complete("c", cursor = "page-2")
        runCurrent()
        assertEquals(listOf("a", "b", "c"), state.value.items.map { it.id })
        assertNull(state.value.nextCursor)
        loader.loadMore()
        runCurrent()
        assertEquals(3, remote.requests.size)
    }

    @Test
    fun `page deadline exposes retry without deleting items or cursor`() = runTest {
        val remote = Remote()
        val state = MutableStateFlow(MusicCatalogUiState(items = listOf(song("first")), section = "community", nextCursor = "next"))
        val loader = MomentMusicCatalogLoader(this, state, remote::fetch, deadlineMillis = 1_000)
        loader.loadMore()
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(state.value.isLoadingMore)
        assertNotNull(state.value.loadMoreError)
        assertNull(state.value.error)
        assertEquals("next", state.value.nextCursor)
        assertEquals(listOf("first"), state.value.items.map { it.id })
        loader.loadMore()
        runCurrent()
        assertNull(state.value.loadMoreError)
        assertEquals("next", remote.requests[1].cursor)
        remote.requests[1].complete("second")
        runCurrent()
        assertEquals(listOf("first", "second"), state.value.items.map { it.id })
        assertNull(state.value.loadMoreError)
    }

    @Test
    fun `first page deadline stops spinner and can recover`() = runTest {
        val remote = Remote()
        val state = MutableStateFlow(MusicCatalogUiState())
        val loader = MomentMusicCatalogLoader(this, state, remote::fetch, deadlineMillis = 1_000)
        loader.search("", "Todo", "community")
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(state.value.isLoading)
        assertNotNull(state.value.error)
        assertTrue(state.value.items.isEmpty())
        loader.search("", "Todo", "community")
        runCurrent()
        remote.requests[1].complete("recovered")
        runCurrent()
        assertEquals(listOf("recovered"), state.value.items.map { it.id })
        assertNull(state.value.error)
    }

    @Test
    fun `superseded cooperative requests are actually cancelled`() = runTest {
        val remote = Remote()
        val state = MutableStateFlow(MusicCatalogUiState())
        val loader = MomentMusicCatalogLoader(this, state, remote::fetch)
        loader.search("", "Todo", "community")
        runCurrent()
        loader.search("", "Rock", "community")
        runCurrent()
        assertTrue(remote.requests[0].cancelled)
        assertNull(state.value.error)
        remote.requests[1].complete("rock")
        runCurrent()
    }

    @Test
    fun `thrown read errors are recoverable state not coroutine crashes`() = runTest {
        val state = MutableStateFlow(MusicCatalogUiState())
        val loader = MomentMusicCatalogLoader(this, state, { _, _, _, _ -> error("BACKEND_UNAVAILABLE") })
        loader.search("", "Todo", "community")
        runCurrent()
        assertFalse(state.value.isLoading)
        assertNotNull(state.value.error)
    }

    private class Remote(private val ignoreCancellation: Boolean = false) {
        val requests = mutableListOf<Request>()
        suspend fun fetch(query: String, category: String, cursor: String?, section: String): Result<MomentMusicCatalogPage> {
            val request = Request(query, category, cursor, section)
            requests += request
            return try {
                if (ignoreCancellation) withContext(NonCancellable) { request.result.await() }
                else request.result.await()
            } catch (cancelled: CancellationException) {
                request.cancelled = true
                throw cancelled
            }
        }
    }

    private class Request(val query: String, val category: String, val cursor: String?, val section: String) {
        val result = CompletableDeferred<Result<MomentMusicCatalogPage>>()
        var cancelled = false
        fun complete(vararg ids: String, cursor: String? = null) {
            result.complete(Result.success(MomentMusicCatalogPage(ids.map(::song), cursor)))
        }
        fun fail() { result.complete(Result.failure(IllegalStateException("BACKEND_UNAVAILABLE"))) }
    }

    companion object {
        private fun song(id: String) = ApprovedMomentMusic(
            id = id, title = id, artist = "Test", durationMs = 240_000,
            audioUrl = "https://example.invalid/$id.mp3", sourcePageUrl = "https://example.invalid/$id",
            source = "community", license = "CC0 1.0",
        )
    }
}
