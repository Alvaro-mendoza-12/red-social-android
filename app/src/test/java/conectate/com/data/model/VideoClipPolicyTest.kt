package conectate.com.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoClipPolicyTest {
    @Test fun `shared Vercel and app links resolve to the same video`() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(id, videoIdFromSharedLink("https://novvinapp.com/app/reels/$id"))
        assertEquals(id, videoIdFromSharedLink("https://novvinapp.com/video/$id"))
        assertEquals(id, videoIdFromSharedLink("https://red-social-android.vercel.app/app/reels/$id"))
        assertEquals(id, videoIdFromSharedLink("https://conectate.com/video/$id?resumeMs=5000"))
        assertEquals(id, videoIdFromSharedLink("conectate://video/$id"))
        assertNull(videoIdFromSharedLink("http://novvinapp.com/video/$id"))
        assertNull(videoIdFromSharedLink("https://evil.example/app/reels/$id"))
    }

    @Test fun `clip range stays inside source and lasts five to sixty seconds`() {
        assertEquals(VideoClipRange(12_000, 20_000), validVideoClipRange(12_000, 20_000, 45_000))
        assertNull(validVideoClipRange(12_000, 16_999, 45_000))
        assertNull(validVideoClipRange(12_000, 45_001, 45_000))
        assertNull(validVideoClipRange(-1, 10_000, 45_000))
        assertNull(validVideoClipRange(0, 61_000, 120_000))
    }
}
