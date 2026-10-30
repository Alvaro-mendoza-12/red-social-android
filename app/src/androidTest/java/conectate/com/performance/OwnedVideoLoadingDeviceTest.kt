package conectate.com.performance

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.testing.RuntimeSessionOverride
import conectate.com.core.testing.TestRuntimeOverrides
import conectate.com.core.testing.MediaAuditEntryPoint
import conectate.com.util.VideoCache
import conectate.com.util.UbuntuMedia
import dagger.hilt.android.EntryPointAccessors
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in read-only sample. Fixture is private, generated for our existing synthetic account. */
class OwnedVideoLoadingDeviceTest {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun publishedVideoFirstFrameAndWarmReopen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(InstrumentationRegistry.getArguments().getString("ownedReadPerformance") == "true")
        check(context.packageName.endsWith(".qa"))
        val file = File(context.cacheDir, "mobile96-read-fixture.json")
        val fixture = JSONObject(file.readText())
        val previous = TestRuntimeOverrides.currentSession(context)
        try {
            TestRuntimeOverrides.setCurrentSession(context, RuntimeSessionOverride(
                fixture.getString("userId"), fixture.getString("email"), fixture.getString("accessToken"), fixture.getString("refreshToken"),
            ))
            EntryPointAccessors.fromApplication(context, MediaAuditEntryPoint::class.java).authService()
            val client = OkHttpClient.Builder().dns(conectate.com.core.network.PublicBackendDns).callTimeout(20, TimeUnit.SECONDS).build()
            val started = System.nanoTime()
            val feed = client.newCall(Request.Builder().url(fixture.getString("apiBase") + "/v1/data/videos?limit=5")
                .header("Authorization", "Bearer " + fixture.getString("accessToken")).build()).execute().use {
                assertEquals(200, it.code)
                JSONObject(it.body!!.string())
            }
            val feedMs = (System.nanoTime() - started) / 1_000_000
            val videos = feed.optJSONArray("videos") ?: feed.getJSONArray("items")
            val video = videos.getJSONObject(0)
            val url = listOf("video_url", "videoUrl", "url").firstNotNullOf { key -> video.optString(key).takeIf { it.startsWith("https://") } }
            val samples = JSONArray()
            repeat(2) { index ->
                var player: ExoPlayer? = null
                var texture: SurfaceTexture? = null
                var surface: Surface? = null
                val ready = CountDownLatch(1)
                val error = AtomicReference<PlaybackException?>(null)
                val start = System.nanoTime()
                try {
                    instrumentation.runOnMainSync {
                        texture = SurfaceTexture(false)
                        surface = Surface(texture)
                        player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(VideoCache.getCacheDataSourceFactory(context))).build().apply {
                            volume = 0f
                            addListener(object : Player.Listener {
                                override fun onRenderedFirstFrame() { ready.countDown() }
                                override fun onPlayerError(failure: PlaybackException) { error.set(failure); ready.countDown() }
                            })
                            setVideoSurface(surface)
                            setMediaItem(UbuntuMedia.mediaItem(url))
                            prepare(); play()
                        }
                    }
                    assertTrue("Published video did not render", ready.await(30, TimeUnit.SECONDS))
                    assertNull("Published video failed", error.get())
                    samples.put(JSONObject().put("attempt", index + 1).put("firstFrameMs", (System.nanoTime() - start) / 1_000_000))
                } finally { instrumentation.runOnMainSync { player?.release(); surface?.release(); texture?.release() } }
            }
            File(context.getExternalFilesDir("mobile96"), "read-performance.json").writeText(
                JSONObject().put("feedHttpMs", feedMs).put("samples", samples)
                    .put("scope", "One published video, first and repeated open on emulator; not user percentiles or a before/after benchmark").toString(2),
            )
        } finally {
            TestRuntimeOverrides.setCurrentSession(context, previous)
            file.delete()
        }
    }
}
