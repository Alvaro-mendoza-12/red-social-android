package conectate.com.presentation.video

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaylistReconciliationTest {
    private fun entry(id: String, key: String = id) = VideoPlaylistEntry(id, key)

    @Test
    fun `pagination appends without replacing active media`() {
        val result = reconcileVideoPlaylist(
            existing = listOf(entry("a"), entry("b")),
            desired = listOf(entry("a"), entry("b"), entry("c")),
            settledVideoId = "b",
            currentMediaId = "b",
            currentPositionMs = 8_500L,
        )

        assertEquals(VideoPlaylistUpdate.Append(fromIndex = 2), result)
    }

    @Test
    fun `reordering preserves position of the visible media`() {
        val result = reconcileVideoPlaylist(
            existing = listOf(entry("a"), entry("b")),
            desired = listOf(entry("b"), entry("a")),
            settledVideoId = "b",
            currentMediaId = "b",
            currentPositionMs = 8_500L,
        )

        assertEquals(VideoPlaylistUpdate.Replace(targetIndex = 0, startPositionMs = 8_500L), result)
    }

    @Test fun `late ranking replaces upcoming items without resetting buffered video`() {
        assertEquals(VideoPlaylistUpdate.ReplaceTail(1), reconcileVideoPlaylist(
            listOf(entry("a"), entry("b"), entry("c")), listOf(entry("a"), entry("c"), entry("d")),
            "a", "a", 0L))
    }

    @Test fun `shorter page removes only the suffix after the active item`() {
        assertEquals(VideoPlaylistUpdate.ReplaceTail(2), reconcileVideoPlaylist(
            listOf(entry("a"), entry("b"), entry("c")), listOf(entry("a"), entry("b")),
            "b", "b", 4000L))
    }

    @Test fun `changed upcoming uri leaves current decoder untouched`() {
        assertEquals(VideoPlaylistUpdate.ReplaceTail(1), reconcileVideoPlaylist(
            listOf(entry("a"), entry("b")), listOf(entry("a"), entry("b", "renewed")),
            "a", "a", 7000L))
    }

    @Test
    fun `new feed starts its first item from zero`() {
        val result = reconcileVideoPlaylist(
            existing = listOf(entry("a"), entry("b")),
            desired = listOf(entry("c"), entry("d")),
            settledVideoId = null,
            currentMediaId = "a",
            currentPositionMs = 8_500L,
        )

        assertEquals(VideoPlaylistUpdate.Replace(targetIndex = 0, startPositionMs = 0L), result)
    }

    @Test
    fun `rotated backend uri replaces source but preserves visible position`() {
        val result = reconcileVideoPlaylist(
            existing = listOf(
                entry("a").copy(playbackUri = "https://old.example/media/a.mp4"),
                entry("b").copy(playbackUri = "https://old.example/media/b.mp4"),
            ),
            desired = listOf(
                entry("a").copy(playbackUri = "https://new.example/media/a.mp4"),
                entry("b").copy(playbackUri = "https://new.example/media/b.mp4"),
            ),
            settledVideoId = "b",
            currentMediaId = "b",
            currentPositionMs = 4_200L,
        )

        assertEquals(VideoPlaylistUpdate.Replace(targetIndex = 1, startPositionMs = 4_200L), result)
    }
}
