package conectate.com.presentation.videos

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EditorDraftStateTest {
    @Test fun allEditingFieldsSurviveTheCheckpoint() {
        val video = EditorClip(7, uri = Uri.parse("content://media/qa/7"), startMs = 900, durationMs = 2400,
            sourceDurationMs = 50_000, startOffsetMs = 7000, speed = 1.5f, volume = .4f,
            brightness = .2f, contrast = 1.3f, saturation = .7f, filterType = FilterType.SEPIA,
            aspectRatio = AspectRatio.RATIO_9_16, originalAudioVolume = .3f, musicVolume = .9f,
            customLutId = "a".repeat(64), customLutTitle = "Mi filtro")
        val text = EditorClip(8, type = ClipType.TEXT, text = "Mi edición", x = 17f, y = 44f, color = 123, fontSize = 30f)
        val sticker = EditorClip(9, type = ClipType.STICKER, resId = 45)
        val draft = EditorDraftState(EditorTimeline(listOf(EditorTrack(1, TrackType.VIDEO, listOf(video)),
            EditorTrack(2, TrackType.TEXT, listOf(text)), EditorTrack(3, TrackType.STICKER, listOf(sticker)))),
            7, 1400, ExportQuality.AUTO)
        assertEquals(draft, EditorDraftState.fromBundle(draft.toBundle()))
    }
    @Test fun oversizedOrUnsupportedStateIsNotSaved() {
        val oversized = EditorDraftState(EditorTimeline(listOf(EditorTrack(1, TrackType.TEXT,
            listOf(EditorClip(1, text = "a".repeat(180_000)))))), null, 0, ExportQuality.AUTO)
        assertNull(oversized.toBundle())
        assertNull(EditorDraftState.fromBundle(Bundle().apply { putInt("schema", 99) }))
        assertNull(EditorDraftState.fromBundle(null))
    }
    @Test fun recreatedViewModelRestoresTimelineSelectionPositionAndQuality() {
        val timeline = EditorTimeline(listOf(EditorTrack(1, TrackType.VIDEO,
            listOf(EditorClip(7, uri = Uri.parse("content://media/qa/7"), durationMs = 4000, sourceDurationMs = 6000)))))
        val draft = EditorDraftState(timeline, 7, 1800, ExportQuality.BALANCED)
        val model = VideoEditorViewModel(SavedStateHandle(mapOf("editor_draft" to draft.toBundle(),
            "editor_input_uri" to "content://media/qa/7")))
        try {
            assertEquals(timeline, model.timeline.value)
            assertEquals(7L, model.selectedClipId.value)
            assertEquals(1800L, model.currentTimeMs.value)
            assertEquals(ExportQuality.BALANCED, model.selectedExportQuality.value)
        } finally { ViewModelStore().apply { put("qa", model); clear() } }
    }
}
