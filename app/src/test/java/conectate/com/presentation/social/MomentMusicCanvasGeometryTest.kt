package conectate.com.presentation.social

import org.junit.Assert.*
import org.junit.Test

class MomentMusicCanvasGeometryTest {
    @Test fun squareMediaIsFittedBetweenControls() {
        val rect = momentFittedMediaRect(720f, 900f, 1f)
        assertEquals(MomentMediaRect(0f, 90f, 720f, 720f), rect)
    }
    @Test fun landscapeAndPortraitKeepTheirAspectRatio() {
        val wide = momentFittedMediaRect(720f, 900f, 2f)
        assertEquals(360f, wide.height, .01f)
        val tall = momentFittedMediaRect(720f, 900f, .5f)
        assertEquals(450f, tall.width, .01f)
        assertEquals(135f, tall.left, .01f)
    }
    @Test fun invalidPersistedValuesCannotEscapeOrCrashCanvas() {
        assertEquals(MomentMusicTransform(.5f, .5f, 1f, 0f), normalizeMomentMusicTransform(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN))
        assertEquals(MomentMusicTransform(0f, 1f, 2f, -170f), normalizeMomentMusicTransform(-4f, 9f, 9f, 190f))
    }
    @Test fun rotatedBoundsRemainInsideMedia() {
        val rect = momentFittedMediaRect(300f, 500f, 1f)
        val result = fitMomentMusicTransform(MomentMusicTransform(0f, 1f, 2f, 90f), rect, 240f, 200f)
        assertEquals(1.25f, result.scale, .001f)
        assertEquals(250f / 600f, result.x, .001f)
        assertEquals(.5f, result.y, .001f)
    }
    @Test fun normalizedPositionRoundTripsAcrossEditorAndViewerSizes() {
        val selection = MomentMusicTransform(.62f, .35f, .8f, 18f)
        val editor = momentFittedMediaRect(720f, 900f, 1f)
        val viewer = momentFittedMediaRect(1080f, 1900f, 1f)
        val a = fitMomentMusicTransform(selection, editor, editor.width * .78f, editor.width * .3f)
        val b = fitMomentMusicTransform(selection, viewer, viewer.width * .78f, viewer.width * .3f)
        assertEquals(a.x, b.x, .001f); assertEquals(a.y, b.y, .001f)
        assertEquals(a.scale, b.scale, .001f); assertEquals(a.rotation, b.rotation, .001f)
    }
    @Test fun excerptScrubbingUsesRealMillisecondsAndPreservesLength() {
        assertEquals(12_000L, momentExcerptStartAfterDrag(10_000, -40f, 360f, 18_000, 180_000, 6_000))
        assertEquals(0L, momentExcerptStartAfterDrag(1_000, 100f, 360f, 18_000, 180_000, 6_000))
        assertEquals(174_000L, momentExcerptStartAfterDrag(170_000, -200f, 360f, 18_000, 180_000, 6_000))
        assertEquals(1_000L, momentExcerptStartAfterDrag(1_000, Float.NaN, 360f, 18_000, 180_000, 6_000))
    }
}
