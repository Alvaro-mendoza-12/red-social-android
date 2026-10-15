package conectate.com.presentation.video

import conectate.com.data.model.Video
import org.junit.Assert.*
import org.junit.Test

class VideoFeedAuthoritativeEmptyTest {
    @Test fun authoritativeEmptyReplacesProvisionalSnapshotInSameSession() {
        val store = VideoFeedPageStore()
        store.publishInitial(listOf(Video(id = "cached", userId = "creator")), "visit")
        store.publishInitial(emptyList(), "visit", allowEmpty = true)
        assertTrue(store.value.isEmpty())
        assertEquals("visit", store.snapshot.value.sessionId)
    }
    @Test fun authoritativeEmptyReplacesPriorSessionWithoutReplayingIt() {
        val store = VideoFeedPageStore()
        store.publishInitial(listOf(Video(id = "prior", userId = "creator")), "prior-visit")
        store.publishInitial(emptyList(), "next-visit", allowEmpty = true)
        assertTrue(store.value.isEmpty())
        assertEquals("next-visit", store.snapshot.value.sessionId)
    }
    @Test fun provisionalEmptyStillKeepsUsableCachedPage() {
        val store = VideoFeedPageStore()
        val videos = listOf(Video(id = "cached", userId = "creator"))
        store.publishInitial(videos, "visit")
        store.publishInitial(emptyList(), "visit")
        assertEquals(videos, store.value)
    }
    @Test fun nextSuccessfulPageReplacesAcceptedEmptySnapshot() {
        val store = VideoFeedPageStore()
        store.publishInitial(emptyList(), "empty", allowEmpty = true)
        val videos = listOf(Video(id = "new", userId = "creator"))
        store.publishInitial(videos, "new-visit")
        assertEquals(videos, store.value)
        assertEquals("new-visit", store.snapshot.value.sessionId)
    }
}
