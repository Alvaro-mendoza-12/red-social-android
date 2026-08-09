package conectate.com.presentation.videos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoEditorTimelineTest {

    @Test
    fun editorInitializationGuard_preservesSameUriProjectAndAcceptsNewUri() {
        assertFalse(
            shouldInitializeEditorTimeline(
                existingInitialUri = "content://videos/original",
                hasVideoProject = true,
                requestedUri = "content://videos/original"
            )
        )
        assertTrue(
            shouldInitializeEditorTimeline(
                existingInitialUri = "content://videos/original",
                hasVideoProject = true,
                requestedUri = "content://videos/new"
            )
        )
        assertTrue(
            shouldInitializeEditorTimeline(
                existingInitialUri = null,
                hasVideoProject = false,
                requestedUri = "content://videos/original"
            )
        )
    }

    @Test
    fun editorTemporaryFileGuard_acceptsOnlyDirectChildren() {
        val directory = File(System.getProperty("java.io.tmpdir"), "novvin-editor-exports")

        assertTrue(isDirectChildOfDirectory(directory, File(directory, "edited.mp4")))
        assertFalse(isDirectChildOfDirectory(directory, File(File(directory, "nested"), "edited.mp4")))
        assertFalse(isDirectChildOfDirectory(directory, File(directory.parentFile, "edited.mp4")))
    }

    @Test
    fun appendVideoClips_capsProjectAtFortyFiveSeconds() {
        val existing = EditorClip(id = 1L, durationMs = 30_000L, sourceDurationMs = 30_000L)
        val candidate = EditorClip(id = 2L, durationMs = 25_000L, sourceDurationMs = 25_000L)
        val timeline = EditorTimeline(
            tracks = listOf(EditorTrack(type = TrackType.VIDEO, clips = listOf(existing)))
        )

        val updated = timeline.appendVideoClipsWithinLimit(listOf(candidate))

        val clips = updated.tracks.first { it.type == TrackType.VIDEO }.clips
        assertEquals(2, clips.size)
        assertEquals(15_000L, clips.last().durationMs)
        assertEquals(45_000L, updated.durationMs())
    }

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
    fun sanitizeTrimRange_capsAnyExportableFragmentAtFortyFiveSeconds() {
        assertEquals(
            20_000L..65_000L,
            sanitizeTrimRange(120_000L, 20_000L, 110_000L)
        )
    }

    @Test
    fun sanitizeTrimRange_respectsRemainingProjectBudget() {
        assertEquals(
            5_000L..15_000L,
            sanitizeTrimRange(
                sourceDurationMs = 60_000L,
                requestedStartMs = 5_000L,
                requestedEndMs = 40_000L,
                maximumClipDurationMs = 10_000L
            )
        )
    }

    @Test
    fun moveTrimRange_preservesLengthAndClampsToSource() {
        assertEquals(30_000L..75_000L, moveTrimRange(93_000L, 45_000L, 30_000L))
        assertEquals(48_000L..93_000L, moveTrimRange(93_000L, 45_000L, 80_000L))
    }

    @Test
    fun losslessTrimConfiguration_acceptsPlainClipAndRejectsEffects() {
        val plain = EditorClip(id = 8L, durationMs = 45_000L, sourceDurationMs = 93_000L)
        val filtered = plain.copy(id = 9L, filterType = FilterType.SEPIA)

        assertTrue(plain.isLosslessTrimConfiguration())
        assertFalse(filtered.isLosslessTrimConfiguration())
    }

    @Test
    fun splitClipAt_rejectsFragmentsTooCloseToEitherEdge() {
        val original = EditorClip(id = 2L, startMs = 0L, durationMs = 4_000L)
        val timeline = EditorTimeline(
            tracks = listOf(EditorTrack(id = 1L, type = TrackType.VIDEO, clips = listOf(original)))
        )

        assertSame(timeline, timeline.splitClipAt(2L, 499L, 3L))
        assertSame(timeline, timeline.splitClipAt(2L, 3_501L, 3L))
        assertEquals(2, timeline.splitClipAt(2L, 500L, 3L).tracks.single().clips.size)
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
