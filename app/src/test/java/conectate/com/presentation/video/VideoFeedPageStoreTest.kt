package conectate.com.presentation.video

import conectate.com.data.model.Video
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoFeedPageStoreTest {
    @Test fun `refresh publishes new ordering and navigation session atomically`() = runTest {
        val store = VideoFeedPageStore()
        val observed = mutableListOf<VideoFeedSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.snapshot.collect { observed += it }
        }
        store.publishInitial(listOf(Video(id = "a"), Video(id = "b")), "one")
        store.publishInitial(listOf(Video(id = "b"), Video(id = "a")), "two")
        assertEquals(listOf(null, "one", "two"), observed.map { it.sessionId })
        assertEquals(listOf("b", "a"), observed.last().videos.map { it.id })
        assertFalse(observed.any { it.sessionId == "two" && it.videos.firstOrNull()?.id == "a" })
    }

    @Test fun `refresh of identical candidates still signals new navigation session`() {
        val store = VideoFeedPageStore()
        val videos = listOf(Video(id = "only-video"))
        store.publishInitial(videos, "one")
        store.publishInitial(videos, "two")
        assertEquals("two", store.snapshot.value.sessionId)
        assertEquals(videos, store.value)
    }

    @Test fun `empty offline emission keeps the playable page and position`() {
        val store = VideoFeedPageStore()
        store.publishInitial(listOf(Video(id = "cached")), "one")
        val before = store.snapshot.value
        store.publishInitial(emptyList(), "two")
        assertEquals(before, store.snapshot.value)
    }

    @Test fun `pagination and item changes never trigger a new session`() {
        val store = VideoFeedPageStore()
        store.publishInitial(listOf(Video(id = "first")), "one")
        store.value = store.value + Video(id = "next")
        store.value = store.value.filterNot { it.id == "first" }
        assertEquals("one", store.snapshot.value.sessionId)
        assertEquals(listOf("next"), store.value.map { it.id })
    }

    @Test fun `account cleanup removes content and navigation identity`() {
        val store = VideoFeedPageStore()
        store.publishInitial(listOf(Video(id = "private-snapshot")), "one")
        store.clear()
        assertEquals(VideoFeedSnapshot(), store.snapshot.value)
    }
}
