package conectate.com.util

import androidx.media3.common.MediaItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SoundPreviewPlaybackTest {
    @Test fun previewKeepsSourceAndStableVideoCacheKey() {
        val url = "https://conectate.tail9b267c.ts.net/storage/v1/object/public/videos/test.mp4"
        val item: MediaItem = SoundPreviewPlayback.mediaItem(url)
        assertEquals(url, item.localConfiguration!!.uri.toString())
        assertEquals(conectate.com.data.localfallback.UbuntuStorageUrl.stableCacheKey(url),
            item.localConfiguration!!.customCacheKey)
    }
    @Test fun shortStartBufferKeepsLargerRecoveryBuffer() {
        assertEquals(250, SoundPreviewPlayback.START_BUFFER_MS)
        assertTrue(SoundPreviewPlayback.REBUFFER_MS > SoundPreviewPlayback.START_BUFFER_MS)
    }
}
