package conectate.com.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackDataSourcesTest {
    @Test
    fun `a stalled route has a short budget while slow reads keep their allowance`() {
        assertTrue(MediaPlaybackDataSources.CONNECT_TIMEOUT_MS in 3_000..5_000)
        assertTrue(
            MediaPlaybackDataSources.READ_TIMEOUT_MS >=
                30_000,
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
        val guarded = MediaPlaybackDataSources.httpFactory()
        assertTrue(guarded is StorageReadHttpDataSource.Factory)
        assertTrue((guarded as StorageReadHttpDataSource.Factory).upstream is
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory)
        assertTrue(guarded.createDataSource() is StorageReadHttpDataSource)
    }
}
