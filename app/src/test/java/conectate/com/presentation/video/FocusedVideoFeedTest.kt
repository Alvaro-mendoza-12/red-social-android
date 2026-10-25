package conectate.com.presentation.video

import conectate.com.data.model.Video
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusedVideoFeedTest {
    @Test
    fun recommendedCoverXRemainsInitialVideoXWhenFeedArrivesLater() {
        val videoX = Video(id = "video-x", title = "Portada X", videoUrl = "https://example.test/x.mp4")
        val videoY = Video(id = "video-y", title = "Video Y", videoUrl = "https://example.test/y.mp4")

        val merged = mergeFocusedVideoPage(listOf(videoX), listOf(videoY))

        assertEquals("video-x", merged.first().id)
        assertEquals(listOf("video-x", "video-y"), merged.map(Video::id))
    }

    @Test
    fun focusedVideoIsNotDuplicatedWhenServerPageAlsoContainsIt() {
        val videoX = Video(id = "video-x")

        val merged = mergeFocusedVideoPage(listOf(videoX), listOf(Video(id = "video-x"), Video(id = "video-y")))

        assertEquals(listOf("video-x", "video-y"), merged.map(Video::id))
    }

    @Test fun canonicalIdCaseCannotDuplicateTheEntryOrReplaceItsResolvedSource() {
        val focused = Video(id = "VIDEO-X", videoUrl = "https://example.test/original.mp4")
        val merged = mergeFocusedVideoPage(listOf(focused), listOf(Video(id = "video-x"), Video(id = "video-y")))
        assertEquals(2, merged.size)
        assertEquals(focused, merged.first())
    }
}
