package conectate.com.presentation.videos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VideoEditorTimelineTest {

    @Test
    fun sanitizeTrimRange_preservesRequestedValidRange() {
        assertEquals(2_000L..8_000L, sanitizeTrimRange(10_000L, 2_000L, 8_000L))
    }

    @Test
    fun sanitizeTrimRange_clampsToSourceAndMinimumClipDuration() {
        assertEquals(9_000L..10_000L, sanitizeTrimRange(10_000L, 15_000L, 15_000L))
        assertEquals(0L..1_000L, sanitizeTrimRange(500L, -4_000L, 100L))
    }

    @Test
    fun updateClip_changesOnlyTheTargetClip() {
        val first = EditorClip(id = 1L, durationMs = 5_000L)
        val second = EditorClip(id = 2L, durationMs = 7_000L)
        val timeline = EditorTimeline(
            tracks = listOf(EditorTrack(id = 10L, type = TrackType.VIDEO, clips = listOf(first, second)))
        )

        val updated = timeline.updateClip(2L) { it.copy(durationMs = 3_000L) }

        assertSame(first, updated.tracks.single().clips.first())
        assertEquals(3_000L, updated.tracks.single().clips.last().durationMs)
    }

    @Test
    fun calculateTimelineThumbnailTimes_spreadsFramesAndRespectsLimit() {
        assertEquals(
            listOf(500L, 1_500L, 2_500L, 3_500L),
            calculateTimelineThumbnailTimes(durationMs = 4_000L, requestedCount = 4)
        )
        assertEquals(12, calculateTimelineThumbnailTimes(120_000L, 50).size)
    }

    @Test
    fun durationMs_usesVideoDurationAsProjectBoundary() {
        val timeline = EditorTimeline(
            tracks = listOf(
                EditorTrack(
                    id = 1L,
                    type = TrackType.VIDEO,
                    clips = listOf(EditorClip(id = 2L, startMs = 0L, durationMs = 8_000L))
                ),
                EditorTrack(
                    id = 3L,
                    type = TrackType.TEXT,
                    clips = listOf(EditorClip(id = 4L, type = ClipType.TEXT, startMs = 7_000L, durationMs = 4_000L))
                )
            )
        )

        assertEquals(8_000L, timeline.durationMs())
    }

    @Test
    fun reflowVideoTracks_keepsVideoClipsContiguousWithoutMovingOverlays() {
        val overlay = EditorClip(
            id = 4L,
            type = ClipType.TEXT,
            startMs = 2_500L,
            durationMs = 1_000L
        )
        val timeline = EditorTimeline(
            tracks = listOf(
                EditorTrack(
                    id = 1L,
                    type = TrackType.VIDEO,
                    clips = listOf(
                        EditorClip(id = 2L, startMs = 9_000L, durationMs = 2_000L),
                        EditorClip(id = 3L, startMs = 1_000L, durationMs = 3_000L)
                    )
                ),
                EditorTrack(id = 5L, type = TrackType.TEXT, clips = listOf(overlay))
            )
        )

        val reflowed = timeline.reflowVideoTracks()

        assertEquals(listOf(0L, 2_000L), reflowed.tracks.first().clips.map { it.startMs })
        assertSame(overlay, reflowed.tracks.last().clips.single())
    }

    @Test
    fun splitClipAt_preservesSourceOffsetsAndTotalDuration() {
        val timeline = EditorTimeline(
            tracks = listOf(
                EditorTrack(
                    id = 1L,
                    type = TrackType.VIDEO,
                    clips = listOf(
                        EditorClip(
                            id = 2L,
                            startMs = 0L,
                            durationMs = 10_000L,
                            sourceDurationMs = 20_000L,
                            startOffsetMs = 1_000L
                        )
                    )
                )
            )
        )

        val split = timeline.splitClipAt(clipId = 2L, atTimeMs = 4_000L, newClipId = 3L)
        val clips = split.tracks.single().clips

        assertEquals(listOf(2L, 3L), clips.map { it.id })
        assertEquals(listOf(4_000L, 6_000L), clips.map { it.durationMs })
        assertEquals(listOf(1_000L, 5_000L), clips.map { it.startOffsetMs })
        assertEquals(listOf(0L, 4_000L), clips.map { it.startMs })
        assertEquals(10_000L, split.durationMs())
    }

    @Test
    fun duplicateClipAfter_insertsCopyAndReflowsFollowingVideoClips() {
        val first = EditorClip(id = 1L, startMs = 0L, durationMs = 4_000L)
        val following = EditorClip(id = 2L, startMs = 4_000L, durationMs = 2_000L)
        val timeline = EditorTimeline(
            tracks = listOf(
                EditorTrack(id = 10L, type = TrackType.VIDEO, clips = listOf(first, following))
            )
        )

        val duplicated = timeline.duplicateClipAfter(clipId = 1L, newClipId = 3L)
        val clips = duplicated.tracks.single().clips

        assertEquals(listOf(1L, 3L, 2L), clips.map { it.id })
        assertEquals(listOf(0L, 4_000L, 8_000L), clips.map { it.startMs })
        assertEquals(10_000L, duplicated.durationMs())
    }
}
