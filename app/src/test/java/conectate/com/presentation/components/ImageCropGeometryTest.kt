package conectate.com.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCropGeometryTest {
    @Test
    fun `right edge can be resized without moving other edges`() {
        val source = NormalizedImageCrop(0.1f, 0.2f, 0.8f, 0.9f)
        val result = adjustImageCrop(
            crop = source,
            target = ImageCropDragTarget.RIGHT,
            dx = 0.1f,
            dy = 0.4f
        )

        assertEquals(0.1f, result.left, 0.0001f)
        assertEquals(0.2f, result.top, 0.0001f)
        assertEquals(0.9f, result.right, 0.0001f)
        assertEquals(0.9f, result.bottom, 0.0001f)
    }

    @Test
    fun `moving frame keeps its size and clamps it inside image`() {
        val source = NormalizedImageCrop(0.2f, 0.2f, 0.7f, 0.8f)
        val result = adjustImageCrop(
            crop = source,
            target = ImageCropDragTarget.MOVE,
            dx = 0.9f,
            dy = 0.9f
        )

        assertEquals(0.5f, result.left, 0.0001f)
        assertEquals(0.4f, result.top, 0.0001f)
        assertEquals(1f, result.right, 0.0001f)
        assertEquals(1f, result.bottom, 0.0001f)
        assertTrue(result.right - result.left >= 0.5f)
        assertTrue(result.bottom - result.top >= 0.6f)
    }

    @Test
    fun `edge resizing preserves the minimum crop span`() {
        val source = NormalizedImageCrop(0.1f, 0.1f, 0.9f, 0.9f)
        val result = adjustImageCrop(
            crop = source,
            target = ImageCropDragTarget.LEFT,
            dx = 1f,
            dy = 0f,
            minimumSpan = 0.08f
        )

        assertTrue(result.right - result.left >= 0.079f)
    }
}
