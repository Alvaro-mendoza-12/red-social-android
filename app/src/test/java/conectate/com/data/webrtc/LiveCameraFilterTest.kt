package conectate.com.data.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCameraFilterTest {

    @Test
    fun `wire values are stable and unknown values fall back to natural`() {
        assertEquals(LiveCameraFilter.WARM, LiveCameraFilter.fromWireValue(" WARM "))
        assertEquals(LiveCameraFilter.NONE, LiveCameraFilter.fromWireValue("future_filter"))
        assertEquals(LiveCameraFilter.NONE, LiveCameraFilter.fromWireValue(null))
    }

    @Test
    fun `mono neutralizes both chroma planes`() {
        assertEquals(128, LiveCameraFilterColorMath.chroma(20, LiveCameraFilter.MONO, true))
        assertEquals(128, LiveCameraFilterColorMath.chroma(240, LiveCameraFilter.MONO, false))
    }

    @Test
    fun `warm and cool move chroma in opposite directions`() {
        val warmU = LiveCameraFilterColorMath.chroma(100, LiveCameraFilter.WARM, true)
        val warmV = LiveCameraFilterColorMath.chroma(100, LiveCameraFilter.WARM, false)
        val coolU = LiveCameraFilterColorMath.chroma(100, LiveCameraFilter.COOL, true)
        val coolV = LiveCameraFilterColorMath.chroma(100, LiveCameraFilter.COOL, false)

        assertTrue(warmU < warmV)
        assertTrue(coolU > coolV)
    }

    @Test
    fun `all transforms clamp to an eight bit video channel`() {
        assertEquals(255, LiveCameraFilterColorMath.chroma(250, LiveCameraFilter.WARM, false))
        assertEquals(0, LiveCameraFilterColorMath.chroma(2, LiveCameraFilter.WARM, true))
        assertEquals(255, LiveCameraFilterColorMath.luma(255, LiveCameraFilter.WARM))
    }

    @Test
    fun `enhance sliders change luma and warmth independently`() {
        val bright = LiveCameraAdjustments(enabled = true, smooth = 0f, brightness = 1f, warmth = 0.5f)
        val warm = LiveCameraAdjustments(enabled = true, smooth = 0f, brightness = 0.5f, warmth = 1f)

        assertTrue(LiveCameraFilterColorMath.luma(100, LiveCameraFilter.NONE, bright) > 100)
        assertTrue(
            LiveCameraFilterColorMath.chroma(128, LiveCameraFilter.NONE, true, warm) <
                LiveCameraFilterColorMath.chroma(128, LiveCameraFilter.NONE, false, warm)
        )
    }

    @Test
    fun `disabled enhancement leaves natural channels unchanged`() {
        val disabled = LiveCameraAdjustments(enabled = false, smooth = 1f, brightness = 1f, warmth = 1f)
        assertEquals(110, LiveCameraFilterColorMath.luma(110, LiveCameraFilter.NONE, disabled))
        assertEquals(140, LiveCameraFilterColorMath.chroma(140, LiveCameraFilter.NONE, true, disabled))
    }

    @Test
    fun `processor reports filter performance changes`() {
        val states = mutableListOf<LiveFilterPerformanceState>()
        val processor = LiveCameraFilterProcessor(
            initialFilter = LiveCameraFilter.VIVID,
            onPerformanceStateChanged = states::add
        )

        processor.select(LiveCameraFilter.NONE)
        processor.select(LiveCameraFilter.MONO)

        assertEquals(
            listOf(
                LiveFilterPerformanceState.FULL,
                LiveFilterPerformanceState.NATURAL,
                LiveFilterPerformanceState.FULL
            ),
            states
        )
    }
}
