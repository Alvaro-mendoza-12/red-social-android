package conectate.com.performance

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.presentation.video.preloadFeedNeighbours
import conectate.com.util.VideoCache
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FeedNeighbourPreloadDeviceTest {
    @Test
    fun resumedPrefixIsReadableFromTheFeedCacheAfterRemovingTheFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("feed-rebuffer-", ".mp4", context.cacheDir)
        val url = Uri.fromFile(file).toString()
        val prefixBytes = 384 * 1024
        try {
            val expected = instrumentation.context.assets.open("video-cache-smoke.mp4").use { it.readBytes() }
            file.writeBytes(expected)
            var attempts = 0
            var resumeAt = 0L
            val budgets = mutableListOf<Long>()
            runBlocking {
                withTimeout(20_000L) {
                    preloadFeedNeighbours(listOf(url), 3_500L, {
                        SystemClock.elapsedRealtime() >= resumeAt
                    }) { neighbour, remaining ->
                        budgets += remaining
                        attempts++
                        if (attempts == 1) {
                            VideoCache.preloadStart(context, neighbour, 64L * 1024L, remaining)
                            resumeAt = SystemClock.elapsedRealtime() + 400L
                            awaitCancellation()
                        }
                        VideoCache.preloadStart(context, neighbour, prefixBytes.toLong(), remaining)
                    }
                }
            }
            assertEquals(2, attempts)
            assertTrue(budgets.last() in 1L until budgets.first())
            assertTrue("Remove the private upstream before testing cached reads", file.delete())
            val source = VideoCache.feedDataSourceFactory(context).createDataSource()
            val actual = ByteArray(prefixBytes)
            try {
                val spec = DataSpec.Builder().setUri(url).setKey(UbuntuStorageUrl.stableCacheKey(url))
                    .setLength(prefixBytes.toLong()).build()
                source.open(spec)
                var pos = 0
                while (pos < actual.size) {
                    val read = source.read(actual, pos, actual.size - pos)
                    assertTrue("Cache must contain the complete resumed prefix", read > 0)
                    pos += read
                }
            } finally { source.close() }
            assertArrayEquals(expected.copyOf(prefixBytes), actual)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "\nFEED_RESUMED_PREFIX bytes=$prefixBytes attempts=$attempts budgetsMs=$budgets\n")
            })
        } finally {
            VideoCache.removeResource(context, url)
            file.delete()
        }
    }
}
