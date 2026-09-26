package conectate.com.util

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoCacheLocalPlaybackTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After fun release() { VideoCache.release() }

    @Test fun `active playback cache reads complete local offline video instead of sending file URI to HTTP`() {
        // This is the URI shape returned by ChatMediaOfflineCache after a sent video is retained.
        val video = File(context.filesDir, "retained-chat-video.media")
        val bytes = ByteArray(8192) { (it % 251).toByte() }
        video.writeBytes(bytes)
        try {
            assertArrayEquals(bytes, read(video, 0, bytes.size.toLong()))
            // A second opening must also work when the Media3 cache already holds its first span.
            assertArrayEquals(bytes.copyOfRange(1024, 4096), read(video, 1024, 3072))
        } finally { video.delete() }
    }

    @Test fun `pending local upload is playable before server URL is available`() {
        val video = File(context.filesDir, "pending-chat-video.mp4")
        val bytes = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 109, 112, 52, 50)
        video.writeBytes(bytes)
        try { assertArrayEquals(bytes, read(video, 0, bytes.size.toLong())) }
        finally { video.delete() }
    }

    private fun read(file: File, position: Long, length: Long): ByteArray {
        val source = VideoCache.getCacheDataSourceFactory(context).createDataSource()
        // Do not allow a cache-init failure to conceal the original cached-upstream regression.
        assertTrue(source is CacheDataSource)
        val spec = DataSpec.Builder().setUri(Uri.fromFile(file)).setPosition(position).setLength(length)
            .setKey(UbuntuMedia.mediaItem(file.toURI().toString()).localConfiguration?.customCacheKey).build()
        return try {
            source.open(spec)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally { source.close() }
    }
}
