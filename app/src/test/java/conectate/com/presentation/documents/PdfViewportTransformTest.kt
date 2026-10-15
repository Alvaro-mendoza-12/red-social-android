package conectate.com.presentation.documents

import org.junit.Assert.*
import org.junit.Test

class PdfViewportTransformTest {
    private fun sample(x: Float = 300f, change: Float = 1f, dx: Float = 0f, width: Int = 600) =
        pdfViewportTransform(2f, 0f, 0f, width, 800, x, 400f, change, dx, 0f)
    @Test fun fingerReleaseWithUnspecifiedCentroidDoesNotErasePage() { assertNull(sample(x = Float.NaN)) }
    @Test fun invalidDeltasAreIgnored() { assertNull(sample(dx = Float.NaN)); assertNull(sample(change = Float.POSITIVE_INFINITY)) }
    @Test fun missingViewportIsIgnored() { assertNull(sample(width = 0)) }
    @Test fun centeredPinchKeepsPageCentered() { assertEquals(PdfViewportTransform(3f, 0f, 0f), sample(change = 1.5f)) }
    @Test fun panIsBoundedToPage() { assertEquals(300f, sample(dx = 10000f)!!.panX, 0f) }
    @Test fun returningToNormalZoomResetsPan() { assertEquals(PdfViewportTransform(1f, 0f, 0f), sample(change = .5f, dx = 200f)) }
    @Test fun zoomCannotExceedFour() { assertEquals(4f, sample(change = 10f)!!.zoom, 0f) }
    @Test fun singleFingerPanAfterPinchStaysFinite() { assertEquals(12f, sample(dx = 12f)!!.panX, 0f) }
}
