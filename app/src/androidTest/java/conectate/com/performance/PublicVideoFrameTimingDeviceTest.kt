package conectate.com.performance

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.media.StorageReadPolicy
import conectate.com.core.testing.MediaAuditEntryPoint
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.util.UbuntuMedia
import conectate.com.util.VideoCache
import conectate.com.util.VideoPerformanceProfiles
import dagger.hilt.android.EntryPointAccessors
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in connected timing of a public guest clip on the real decoder. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PublicVideoFrameTimingDeviceTest {
    @Test fun guestFeedAndFirstVideoFrame(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowLiveGuestRead") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val guest = EntryPointAccessors.fromApplication(context, MediaAuditEntryPoint::class.java).guestModeApi()
        val hadGuest = guest.hasActiveGuest()
        val preview = guest.beginMediaPreview()
        try {
            guest.ensureIdentity()
            val started = SystemClock.elapsedRealtime()
            val posts = guest.posts()
            val socialMillis = SystemClock.elapsedRealtime() - started
            val videoStart = SystemClock.elapsedRealtime()
            val videos = guest.videos()
            val videoFeedMillis = SystemClock.elapsedRealtime() - videoStart
            assertTrue("Expected public feed fixtures", posts.isNotEmpty() && videos.isNotEmpty())
            val target = arguments.getString("expectedPublicVideoDigest")
            val source = videos.asSequence().mapNotNull { UbuntuStorageUrl.rewrite(it.videoUrl) }
                .filter { StorageReadPolicy.accepts(it, UbuntuStorageUrl.baseUrl) &&
                    it.contains("/storage/v1/object/public/") }
                .firstOrNull { target == null || digest(it) == target }
                ?: error("Expected public clip unavailable")
            val samples = JSONArray()
            repeat(3) { samples.put(renderFirstFrame(context, source)) }
            val evidence = JSONObject().put("publicObjectDigest", digest(source))
                .put("guestSocialHttpMs", socialMillis).put("guestSocialRows", posts.size)
                .put("guestVideoHttpMs", videoFeedMillis).put("guestVideoRows", videos.size)
                .put("samples", samples)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPUBLIC_FEED_TIMING $evidence\n") })
        } finally {
            guest.endMediaPreview(preview)
            if (!hadGuest) guest.clear()
        }
    }

    private fun digest(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(android.net.Uri.parse(url).encodedPath.orEmpty().toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun renderFirstFrame(context: Context, url: String): JSONObject {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val profile = VideoPerformanceProfiles.detect(context)
        val completed = CountDownLatch(1)
        val firstByte = AtomicLong(-1)
        val firstFrame = AtomicLong(-1)
        val bytes = AtomicLong()
        val receivedR2 = AtomicReference(false)
        val failure = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        val started = AtomicLong()
        val upstream = VideoCache.feedDataSourceFactory(context)
        val factory = DataSource.Factory {
            upstream.createDataSource().apply {
                addTransferListener(object : TransferListener {
                    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                        if (isNetwork && source.responseHeaders.any { (name, values) ->
                                name.equals("x-novvin-media-source", true) && "r2" in values
                            }) receivedR2.set(true)
                    }
                    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
                    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                        if (isNetwork) {
                            firstByte.compareAndSet(-1, SystemClock.elapsedRealtime() - started.get())
                            bytes.addAndGet(bytesTransferred.toLong())
                        }
                    }
                })
            }
        }
        try {
            instrumentation.runOnMainSync {
                texture = SurfaceTexture(false)
                surface = Surface(texture)
                val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(
                    profile.minBufferMs, profile.maxBufferMs, profile.playBufferMs, profile.rebufferMs,
                ).setPrioritizeTimeOverSizeThresholds(true).build()
                player = ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                    .build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onRenderedFirstFrame() {
                                firstFrame.compareAndSet(-1, SystemClock.elapsedRealtime() - started.get())
                                completed.countDown()
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                failure.set(error)
                                completed.countDown()
                            }
                        })
                        setVideoSurface(surface)
                        setMediaItem(UbuntuMedia.mediaItem(url))
                        started.set(SystemClock.elapsedRealtime())
                        playWhenReady = true
                        prepare()
                    }
            }
            assertTrue("Public clip never rendered", completed.await(25, TimeUnit.SECONDS))
            assertNull("Public video failed: ${failure.get()?.errorCodeName}", failure.get())
            assertTrue("Expected network bytes and a decoded frame", firstByte.get() >= 0 && firstFrame.get() >= 0)
            return JSONObject().put("firstByteMs", firstByte.get()).put("firstFrameMs", firstFrame.get())
                .put("networkBytesObserved", bytes.get()).put("r2", receivedR2.get()).put("deviceTier", profile.tier.name)
        } finally {
            instrumentation.runOnMainSync { player?.release(); surface?.release(); texture?.release() }
        }
    }
}
