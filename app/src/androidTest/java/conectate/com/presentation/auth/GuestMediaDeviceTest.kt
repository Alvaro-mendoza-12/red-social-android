package conectate.com.presentation.auth

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.Coil
import coil.ImageLoader
import conectate.com.core.media.StorageReadCoilInterceptor
import conectate.com.core.media.StorageReadCredential
import conectate.com.core.media.StorageReadIdentity
import conectate.com.core.media.StorageReadSession
import conectate.com.data.localfallback.UbuntuStorageUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GuestMediaDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun reenteringGuestImageReusesDecodedPixelsButAnotherGuestCannotReuseItsEntry() {
        val visible = mutableStateOf(true)
        var credential = StorageReadCredential(StorageReadIdentity("guest_audit_one", 1), "fixture.token")
        val originalLoader = Coil.imageLoader(context)
        val calls = AtomicInteger()
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()
        val imageLoader = ImageLoader.Builder(context).allowHardware(false)
            .components { add(StorageReadCoilInterceptor()) }
            .okHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
                check(chain.request().url.encodedPath.contains("guest-audit-fixture"))
                calls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("Fixture")
                    .body(bytes.toResponseBody("image/png".toMediaType())).build()
            }.build()).build()
        try {
            StorageReadSession.installGuest(credentials = { credential }, active = { true })
            Coil.setImageLoader(imageLoader)
            compose.setContent {
                MaterialTheme {
                    if (visible.value) GuestContentImage(
                        url = "${UbuntuStorageUrl.baseUrl}/storage/v1/object/public/post-media/guest-audit-fixture/photo.png",
                        contentDescription = "Imagen de invitado",
                        modifier = Modifier.size(120.dp).testTag("guest_photo"),
                    )
                }
            }
            fun awaitPixels() {
                compose.waitUntil(10_000) {
                    val pixels = runCatching { compose.onNodeWithTag("guest_photo").captureToImage().toPixelMap() }.getOrNull()
                    pixels?.let { val center = it[it.width / 2, it.height / 2]
                        center.red > .8f && center.blue > .8f && center.green < .2f } == true
                }
            }
            awaitPixels()
            repeat(3) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                compose.runOnIdle { visible.value = true }
                awaitPixels()
            }
            assertEquals("Reentering downloaded the same guest photo again", 1, calls.get())
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle {
                credential = StorageReadCredential(StorageReadIdentity("guest_audit_two", 2), "second.token")
                visible.value = true
            }
            awaitPixels()
            assertEquals("Another guest reused the first session's decoded image", 2, calls.get())
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            Coil.setImageLoader(originalLoader)
            imageLoader.shutdown()
            StorageReadSession.installGuest(credentials = { null })
        }
    }

    @Test fun guestVideoResumesAfterBackgroundButKeepsAManualPause() {
        val video = File.createTempFile("guest-video-audit-", ".mp4", context.cacheDir)
        instrumentation.context.assets.open("video-cache-smoke.mp4").use { input ->
            video.outputStream().use { output -> input.copyTo(output) }
        }
        val visible = mutableStateOf(true)
        lateinit var player: Player
        fun playerView(view: View): PlayerView? {
            if (view is PlayerView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                playerView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        fun playing(): Boolean {
            var result = false
            instrumentation.runOnMainSync { result = player.isPlaying }
            return result
        }
        try {
            compose.setContent { MaterialTheme { if (visible.value) GuestVideoPlayer(android.net.Uri.fromFile(video).toString()) } }
            compose.runOnIdle {
                player = requireNotNull(playerView(compose.activity.window.decorView)?.player)
                // The generated sample is short; avoid ending while testing lifecycle.
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            compose.waitUntil(10_000, ::playing)
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            instrumentation.runOnMainSync { assertFalse(player.playWhenReady) }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000, ::playing)
            compose.runOnIdle { player.pause() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.runOnMainSync { assertFalse("A manual pause must remain paused", player.playWhenReady) }
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            video.delete()
        }
    }
}
