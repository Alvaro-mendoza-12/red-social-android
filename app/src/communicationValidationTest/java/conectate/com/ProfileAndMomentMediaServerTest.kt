package conectate.com

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.network.PublicBackendDns
import conectate.com.data.model.Moment
import conectate.com.data.signaling.SignalingConfig
import conectate.com.presentation.components.CommentAudioPlayer
import conectate.com.presentation.social.MomentContent
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Own media objects and profiles only; the physical owner session remains untouched. */
@RunWith(AndroidJUnit4::class)
class ProfileAndMomentMediaServerTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val client = OkHttpClient.Builder().dns(PublicBackendDns).callTimeout(30, TimeUnit.SECONDS).build()
    private fun request(account: JSONObject, path: String, method: String = "GET", data: ByteArray? = null, mime: String = "application/json"): String {
        val response = client.newCall(Request.Builder().url(SignalingConfig.BASE_URL + path)
            .header("Authorization", "Bearer ${account.getString("token")}").header("Prefer", "return=representation")
            .method(method, if (method == "GET") null else (data ?: "{}".toByteArray()).toRequestBody(mime.toMediaType())).build()).execute()
        return response.use {
            val body = it.body?.string().orEmpty()
            val code = runCatching { JSONObject(body).optString("error") }.getOrDefault("invalid response")
            assertTrue("QA media HTTP $method failed: ${it.code} $code", it.isSuccessful)
            body
        }
    }
    private fun wav(): ByteArray {
        val samples = 22050 * 6
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(22050); putInt(44100); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            // Silent PCM avoids changing the user's system volume during the audit.
        }.array()
    }
    @Test(timeout = 150_000) fun profileMusicPersistsPlaysAndStopsAndPhotoLoadsWithoutWaitingForMusic() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("auditProfileMoment") == "true")
        val accounts = JSONArray(File(context.cacheDir, "media-qa-accounts.json").readText())
        val owner = accounts.getJSONObject(0); val viewer = accounts.getJSONObject(1)
        val identity = owner.getString("id")
        val suffix = UUID.randomUUID().toString()
        val audioPath = "$identity/$suffix.wav"; val imagePath = "$identity/$suffix.jpg"
        var audioUploaded = false; var imageUploaded = false; var profileUpdated = false; var momentId: String? = null
        val report = JSONObject()
        try {
            val audioUrl = JSONObject(request(owner, "/storage/v1/object/profile-audio/$audioPath", "POST", wav(), "audio/wav")).getString("publicUrl")
            audioUploaded = true
            request(owner, "/v1/data/profile/me", "PATCH", JSONObject().put("favorite_music_url", audioUrl).toString().toByteArray())
            profileUpdated = true
            val remoteProfile = JSONObject(request(viewer, "/v1/data/profiles/$identity")).getJSONObject("profile")
            assertEquals(audioUrl, remoteProfile.getString("favorite_music_url"))
            val mode = mutableStateOf("profile")
            val imageReadyAt = AtomicLong()
            var displayedMoment: Moment? = null
            val lifecycleOwner = object : LifecycleOwner {
                val registry = LifecycleRegistry(this)
                override val lifecycle: Lifecycle get() = registry
            }
            instrumentation.runOnMainSync { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                    MaterialTheme {
                        if (mode.value == "profile") CommentAudioPlayer(remoteProfile.getString("favorite_music_url"))
                        else if (mode.value == "photo") Box(Modifier.fillMaxSize()) {
                            MomentContent(requireNotNull(displayedMoment), isVisible = true, paused = false,
                                onVideoDurationKnown = { _, _ -> }, onVideoReady = {}, onImageReady = { imageReadyAt.compareAndSet(0, System.nanoTime()) },
                                onVideoProgress = { _, _ -> }, onVideoEnded = {})
                        }
                    }
                }
            }
            compose.onNodeWithContentDescription("Reproducir").assertIsDisplayed()
            val audioStarted = System.nanoTime()
            compose.onNodeWithContentDescription("Reproducir").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Pausar").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().any { it.config[SemanticsProperties.ProgressBarRangeInfo].current > 0.01f } }
            report.put("profileTapToPlaybackMs", (System.nanoTime() - audioStarted) / 1_000_000)
            instrumentation.runOnMainSync { lifecycleOwner.registry.currentState = Lifecycle.State.CREATED }
            compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Reproducir").fetchSemanticsNodes().isNotEmpty() }
            instrumentation.runOnMainSync { lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED }
            compose.onNodeWithContentDescription("Reproducir").assertIsDisplayed()
            report.put("profilePersistedAndReadableByPeer", true).put("profileAudioPositionAdvanced", true).put("profilePausedOnStop", true)

            val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(50, 199, 110)) }
            val jpeg = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); out.toByteArray() }; bitmap.recycle()
            val imageUrl = JSONObject(request(owner, "/storage/v1/object/moment-media/$imagePath", "POST", jpeg, "image/jpeg")).getString("publicUrl")
            imageUploaded = true
            val row = JSONObject(request(owner, "/v1/data/moments", "POST", JSONObject().put("mediaUrl", imageUrl).put("mediaType", "image")
                .put("visibility", "only_selected").put("visibilityType", "public")
                .put("audienceIds", JSONArray().put(viewer.getString("id"))).put("allowReplies", false).toString().toByteArray())).getJSONObject("moment")
            momentId = row.getString("id")
            val feed = JSONObject(request(viewer, "/v1/data/moments/feed"))
            assertTrue("Second QA account must receive the private image moment", feed.toString().contains(momentId!!))
            // A failed independent soundtrack must never hide a successfully loaded photograph.
            displayedMoment = Moment(id = momentId!!, userId = identity, mediaUrl = row.getString("media_url"), mediaType = "image",
                musicUrl = audioUrl.substringBeforeLast('/') + "/missing-qa-$suffix.wav", musicTitle = "Audio QA no disponible")
            val photoStarted = System.nanoTime()
            compose.runOnIdle { mode.value = "photo" }
            compose.waitUntil(8_000) { imageReadyAt.get() > 0 }
            compose.onNodeWithText("No se pudo cargar la imagen").assertDoesNotExist()
            val pixels = compose.onNodeWithContentDescription("Imagen del momento; pellizca o toca dos veces para ampliar").captureToImage().toPixelMap()
            val pixel = pixels[pixels.width / 2, pixels.height / 4]
            assertTrue("Decoded photo must contain the uploaded green image", pixel.green > .6f && pixel.green > pixel.red * 1.5f)
            val photoMs = (imageReadyAt.get() - photoStarted) / 1_000_000
            assertTrue("Image exceeded the 3 second QA loading budget: $photoMs", photoMs < 3_000)
            report.put("photoFirstImageMs", photoMs).put("photoDecodedPixelsVerified", true).put("photoVisibleWithUnavailableMusic", true)
            compose.runOnIdle { mode.value = "closed" }
        } finally {
            momentId?.let { request(owner, "/v1/data/moments/$it", "DELETE") }
            if (profileUpdated) request(owner, "/v1/data/profile/me", "PATCH", "{\"favorite_music_url\":\"\"}".toByteArray())
            if (imageUploaded) request(owner, "/v1/storage/objects/moment-media/$imagePath", "DELETE")
            if (audioUploaded) request(owner, "/v1/storage/objects/profile-audio/$audioPath", "DELETE")
            File(context.getExternalFilesDir("communication-qa")!!, "profile-moment-media.json").writeText(report.put("ownMediaRemoved", true).toString(2))
        }
    }
}
