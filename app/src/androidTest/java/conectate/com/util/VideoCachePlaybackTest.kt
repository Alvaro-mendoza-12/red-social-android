package conectate.com.util

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real extractor/decoder regression for retained chat videos named .media instead of .mp4. */
@RunWith(AndroidJUnit4::class)
@MediumTest
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoCachePlaybackTest {
    @Test
    fun cachedPlayerReachesReadyAndRendersFirstFrameFromRetainedMediaFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val retained = File.createTempFile("retained-chat-video-", ".media", context.cacheDir)
        var player: ExoPlayer? = null
        var surfaceTexture: SurfaceTexture? = null
        var surface: Surface? = null
        val finished = CountDownLatch(1)
        val ready = AtomicBoolean(false)
        val rendered = AtomicBoolean(false)
        val failure = AtomicReference<PlaybackException?>(null)
        var finalState = Player.STATE_IDLE

        try {
            instrumentation.context.assets.open("video-cache-smoke.mp4").use { input ->
                retained.outputStream().use { output -> input.copyTo(output) }
            }
            assertTrue("The fixture must contain an actual MP4, not placeholder bytes", retained.length() > 1_024L)
            instrumentation.runOnMainSync {
                val factory = VideoCache.getCacheDataSourceFactory(context)
                val source = factory.createDataSource()
                try {
                    // Production guards choose the cache on the loader's first open.
                    assertTrue("Playback source must preserve account admission", source is StorageReadDataSource)
                } finally {
                    source.close()
                }
                val texture = SurfaceTexture(false).also { surfaceTexture = it }
                val output = Surface(texture).also { surface = it }
                val playback = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                    .build()
                player = playback
                playback.apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) {
                                ready.set(true)
                                if (rendered.get()) finished.countDown()
                            }
                        }

                        override fun onRenderedFirstFrame() {
                            rendered.set(true)
                            if (ready.get()) finished.countDown()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            failure.set(error)
                            finished.countDown()
                        }
                    })
                    setVideoSurface(output)
                    // Keep the exact production media-item/cache-key path; don't force a MIME type.
                    setMediaItem(UbuntuMedia.mediaItem(Uri.fromFile(retained).toString()))
                    volume = 0f
                    prepare()
                    play()
                }
            }
            val completed = finished.await(20L, TimeUnit.SECONDS)
            instrumentation.runOnMainSync { finalState = player?.playbackState ?: Player.STATE_IDLE }
            assertNull("Local .media playback failed: ${failure.get()?.errorCodeName}", failure.get())
            assertTrue("Timed out waiting for real playback, state=$finalState", completed)
            assertTrue("ExoPlayer never reached STATE_READY", ready.get())
            assertTrue("The H.264 decoder never rendered its first frame", rendered.get())
        } finally {
            instrumentation.runOnMainSync {
                player?.release()
                surface?.release()
                surfaceTexture?.release()
                VideoCache.release()
            }
            retained.delete()
        }
    }
}
