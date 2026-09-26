package conectate.com.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewRepositoryTest {

    @Test
    fun `trusted preview hosts require https and an exact registrable suffix`() {
        assertTrue(isTrustedPreviewUrl("https://youtube.com/watch?v=123"))
        assertTrue(isTrustedPreviewUrl("https://m.youtube.com/watch?v=123"))
        assertTrue(isTrustedPreviewUrl("https://open.spotify.com/track/123"))

        assertFalse(isTrustedPreviewUrl("http://youtube.com/watch?v=123"))
        assertFalse(isTrustedPreviewUrl("https://youtube.com.evil.test/watch?v=123"))
        assertFalse(isTrustedPreviewUrl("https://evil-youtube.com/watch?v=123"))
        assertFalse(isTrustedPreviewUrl("https://user:password@youtube.com/watch?v=123"))
        assertFalse(isTrustedPreviewUrl("https://localhost/youtube.com"))
        assertFalse(isTrustedPreviewUrl("https://127.0.0.1/youtube.com"))
    }
}
