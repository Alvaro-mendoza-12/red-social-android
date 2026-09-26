package conectate.com.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackDataSourcesTest {
    @Test
    fun `timeouts leave enough room for mobile startup and range seeks`() {
        assertTrue(MediaPlaybackDataSources.CONNECT_TIMEOUT_MS >= 10_000)
        assertTrue(
            MediaPlaybackDataSources.READ_TIMEOUT_MS >=
                MediaPlaybackDataSources.CONNECT_TIMEOUT_MS,
        )
    }

    @Test
    fun `user agent identifies product version and platform`() {
        val value = MediaPlaybackDataSources.userAgent("3.3.4")

        assertEquals("NOVVIN/3.3.4 (Android)", value)
        assertFalse(value.contains("null", ignoreCase = true))
    }

    @Test
    fun `media playback uses one modern pooled HTTP stack`() {
        assertEquals("okhttp-http2", MediaPlaybackDataSources.NETWORK_STACK)
        assertTrue(
            MediaPlaybackDataSources.httpFactory() is
                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory,
        )
    }
}
