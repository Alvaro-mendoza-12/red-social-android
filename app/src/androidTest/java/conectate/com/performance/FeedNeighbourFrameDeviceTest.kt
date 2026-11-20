package conectate.com.performance

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.presentation.video.createFeedVideoPlayer
import conectate.com.util.VideoCache
import conectate.com.util.VideoPerformanceProfiles
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Decoder timing for a settled selection, using the feed cache and local H.264 fixtures. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FeedNeighbourFrameDeviceTest {
    @Test fun selectedNeighbourRendersOnTheSamePlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = mutableListOf<File>()
        var player: ExoPlayer? = null
        var surface: Surface? = null
        var texture: SurfaceTexture? = null
        try {
            repeat(3) {
                val file = File.createTempFile("feed-frame-", ".mp4", context.cacheDir)
                files += file
                instrumentation.context.assets.open("video-cache-smoke.mp4").use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }
            val items = files.mapIndexed { index, file ->
                val url = Uri.fromFile(file).toString()
                MediaItem.Builder().setMediaId(index.toString()).setUri(url)
                    .setCustomCacheKey(UbuntuStorageUrl.stableCacheKey(url)).build()
            }
            instrumentation.runOnMainSync {
                val outputTexture = SurfaceTexture(false).also { texture = it }
                val output = Surface(outputTexture).also { surface = it }
                player = createFeedVideoPlayer(context, VideoPerformanceProfiles.detect(context)).apply {
                    volume = 0f
                    setVideoSurface(output)
                    setMediaItems(items)
                }
            }
            for (index in files.indices) {
                if (index > 0) runBlocking {
                    VideoCache.preloadStart(context, Uri.fromFile(files[index]).toString(), files[index].length())
                }
                val frame = CountDownLatch(1)
                val failure = AtomicReference<PlaybackException?>()
                var started = 0L
                var elapsed = 0L
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (player?.currentMediaItem?.mediaId == index.toString() && frame.count > 0) {
                            elapsed = SystemClock.elapsedRealtime() - started
                            frame.countDown()
                        }
                    }
                    override fun onPlayerError(error: PlaybackException) { failure.set(error); frame.countDown() }
                }
                instrumentation.runOnMainSync {
                    val playback = checkNotNull(player)
                    playback.addListener(listener)
                    started = SystemClock.elapsedRealtime()
                    if (index == 0) playback.prepare() else playback.seekTo(index, 0L)
                    playback.play()
                }
                try {
                    assertTrue("First frame for selection $index", frame.await(20, TimeUnit.SECONDS))
                    assertNull(failure.get())
                    instrumentation.sendStatus(0, Bundle().apply {
                        putString("stream", "\nFEED_LOCAL_FIRST_FRAME index=$index elapsedMs=$elapsed\n")
                    })
                } finally { instrumentation.runOnMainSync { player?.removeListener(listener) } }
            }
        } finally {
            instrumentation.runOnMainSync { player?.release(); surface?.release(); texture?.release() }
            files.forEach { file ->
                VideoCache.removeResource(context, Uri.fromFile(file).toString())
                file.delete()
            }
        }
    }
}
