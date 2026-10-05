package conectate.com

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.signaling.SignalingConfig
import conectate.com.util.SoundPreviewPlayback
import conectate.com.util.VideoCache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real public media, no SMTP, no changes to the handset owner's account. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SoundPreviewLatencyAuditTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val http = OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(20, TimeUnit.SECONDS).build()

    private fun request(path: String, token: String? = null, body: JSONObject? = null, expected: Int = 200): JSONObject {
        val req = Request.Builder().url(SignalingConfig.BASE_URL + path).apply {
            token?.let { header("Authorization", "Bearer $it") }
            body?.let { post(it.toString().toRequestBody("application/json".toMediaType())) }
        }.build()
        return http.newCall(req).execute().use {
            assertEquals("Unexpected HTTP status for audit request", expected, it.code)
            it.body?.string()?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
        }
    }

    private fun measure(url: String, mode: String): JSONObject {
        var player: ExoPlayer? = null
        val ready = CountDownLatch(1)
        val audible = CountDownLatch(1)
        val firstAudioAt = AtomicLong(0)
        val failure = AtomicReference<Int?>(null)
        try {
            instrumentation.runOnMainSync {
                player = if (mode == "baseline") ExoPlayer.Builder(context)
                    .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        conectate.com.util.MediaPlaybackDataSources.streamingFactory(context)))
                    .build() else SoundPreviewPlayback.create(context)
                player!!.apply {
                    volume = 0f
                    trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) ready.countDown() }
                        override fun onPlayerError(error: PlaybackException) { failure.set(error.errorCode); ready.countDown(); audible.countDown() }
                    })
                    addAnalyticsListener(object : AnalyticsListener {
                        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                            firstAudioAt.compareAndSet(0, System.nanoTime())
                            audible.countDown()
                        }
                    })
                    setMediaItem(SoundPreviewPlayback.mediaItem(url))
                    if (mode == "prepared") prepare()
                }
            }
            var preparationMs = 0L
            if (mode == "prepared") {
                val preparationStarted = System.nanoTime()
                assertTrue("Sound sheet did not prepare", ready.await(20, TimeUnit.SECONDS))
                preparationMs = (System.nanoTime() - preparationStarted) / 1_000_000
                assertNull("Sound preparation failed", failure.get())
                assertEquals("Preparing a sheet must not autoplay", 0, firstAudioAt.get())
                instrumentation.runOnMainSync { assertFalse(player!!.playWhenReady) }
            }
            val tappedAt = System.nanoTime()
            instrumentation.runOnMainSync {
                if (mode != "prepared") player!!.prepare()
                player!!.play()
            }
            assertTrue("No audio playout from published source", audible.await(20, TimeUnit.SECONDS))
            assertNull("Published audio source failed", failure.get())
            assertTrue("Audio decoder never advanced", firstAudioAt.get() > 0)
            val latencyMs = (firstAudioAt.get() - tappedAt) / 1_000_000
            instrumentation.runOnMainSync { player!!.pause(); assertFalse(player!!.playWhenReady) }
            if (mode == "prepared") assertTrue("Prepared sound should start within one second; observed $latencyMs ms", latencyMs < 1_000)
            return JSONObject().put("mode", mode).put("tapToAudioMs", latencyMs).put("preparationMs", preparationMs)
                .put("audioPositionAdvanced", true).put("pauseVerified", true)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
    }

    @Test(timeout = 180_000) fun publishedSoundStartsFromSharedCacheWithoutAutoplay() {
        val registered = request("/v1/auth/register", body = JSONObject()
            .put("email", "audio-qa.${UUID.randomUUID()}@example.invalid")
            .put("password", "QA-${UUID.randomUUID()}-only")
            .put("displayName", "Prueba de audio temporal"), expected = 201)
        val token = registered.getString("accessToken")
        val results = JSONArray()
        try {
            val feed = request("/v1/data/videos?limit=10", token)
            val videos = feed.optJSONArray("videos") ?: feed.getJSONArray("items")
            val urls = (0 until videos.length()).map { videos.getJSONObject(it) }.mapNotNull { video ->
                listOf("sound_url", "soundUrl", "video_url", "videoUrl", "url").firstNotNullOfOrNull { key ->
                    video.optString(key).takeIf { it.startsWith("https://") }?.let(UbuntuStorageUrl::rewrite)
                }
            }.distinct().take(2)
            assertEquals("Two published audio/video sources are required", 2, urls.size)
            urls.forEachIndexed { index, url ->
                VideoCache.removeResource(context, url)
                val baseline = measure(url, "baseline")
                val cold = measure(url, "cold")
                val prepared = measure(url, "prepared")
                results.put(JSONObject().put("sourceIndex", index).put("measurements", JSONArray(listOf(baseline, cold, prepared))))
            }
        } finally {
            request("/rest/v1/rpc/delete_own_account", token, JSONObject())
            request("/v1/auth/me", token, expected = 401)
            File(context.filesDir, "sound-preview-audit.json").writeText(JSONObject()
                .put("results", results).put("fixtureDeleted", true).put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                .put("measurement", "ExoPlayer audio position advancing; muted output").toString(2))
        }
    }
}
