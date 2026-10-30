package conectate.com.presentation.videos

import org.junit.Assert.*
import org.junit.Test

class EditorOverlayGeometryTest {
    @Test fun `portrait video in a wide preview uses its fitted frame not the black margins`() {
        val frame = fittedEditorVideoCanvas(1080f, 500f, 9f / 16f)
        assertEquals(281.25f, frame.width, .001f)
        assertEquals(399.375f, frame.left, .001f)
        assertEquals(0f, frame.top, .001f)
    }
    @Test fun `landscape preview centers its actual video frame vertically`() {
        val frame = fittedEditorVideoCanvas(360f, 600f, 16f / 9f)
        assertEquals(360f, frame.width, .001f)
        assertEquals(202.5f, frame.height, .001f)
        assertEquals(198.75f, frame.top, .001f)
    }
    @Test fun `same saved position exports to the same normalized location on every screen`() {
        for (width in listOf(240f, 360f, 1080f)) {
            val previewX = 250f / EDITOR_OVERLAY_SPACE * width
            assertEquals(.25f, previewX / width, .001f)
            assertEquals(-.5f to .5f, editorOverlayAnchor(250f, 250f))
        }
    }
    @Test fun `top left and bottom right anchors preserve the UI coordinate origin`() {
        assertEquals(-1f to 1f, editorOverlayAnchor(0f, 0f))
        assertEquals(1f to -1f, editorOverlayAnchor(1000f, 1000f))
    }
    @Test fun `font occupies the same video proportion at preview and export sizes`() {
        assertEquals(editorOverlayFontPixels(32f, 360f) / 360f,
            editorOverlayFontPixels(32f, 1920f) / 1920f, .0001f)
    }
    @Test fun `invalid and off canvas coordinates cannot become invalid GL anchors`() {
        assertEquals(-1f to 1f, editorOverlayAnchor(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(0f, fittedEditorVideoCanvas(0f, 300f, 1f).width, 0f)
    }
}
