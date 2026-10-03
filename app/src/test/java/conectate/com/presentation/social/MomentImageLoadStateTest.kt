package conectate.com.presentation.social

import org.junit.Assert.assertEquals
import org.junit.Test

class MomentImageLoadStateTest {
    @Test fun `ordinary images decode to viewport size`() {
        assertEquals(1080 to 1920, momentImageDecodeSize(1080, 1920))
    }
    @Test fun `large tablet viewports keep aspect ratio with bounded decoded pixels`() {
        assertEquals(1620 to 2160, momentImageDecodeSize(2400, 3200))
        assertEquals(2160 to 1080, momentImageDecodeSize(4000, 2000))
    }
    @Test fun `empty or unbounded measurements remain valid`() {
        assertEquals(1 to 1, momentImageDecodeSize(0, 0))
        assertEquals(2160 to 2160, momentImageDecodeSize(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun `network image request is bounded and does not retain an old tiny preview`() {
        val raw = "https://external.example/storage/v1/object/public/moment-media/qa/photo.jpg?width=96&quality=50&format=webp"
        val result = momentImagePreviewUrl(raw, 1080)
        org.junit.Assert.assertTrue(result.contains("width=1080"))
        org.junit.Assert.assertTrue(result.contains("quality=86"))
        assertEquals(1, result.split("width=").size - 1)
        org.junit.Assert.assertTrue(momentImagePreviewUrl(raw, 10_000).contains("width=1440"))
    }

    @Test fun `external image servers keep their existing query contract`() {
        val url = "https://external.example/photo.jpg?resize=original"
        assertEquals(url, momentImagePreviewUrl(url, 1080))
    }
}
