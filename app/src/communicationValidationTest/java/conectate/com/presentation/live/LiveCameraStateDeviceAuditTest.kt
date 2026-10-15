package conectate.com.presentation.live

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.LiveGuestParticipant
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import java.io.File

/** A real retained native track must not cover the avatar after guest camera-off. */
class LiveCameraStateDeviceAuditTest {
    @get:Rule val compose = createComposeRule()
    @Test fun disabledGuestShowsPhotoEvenWhenRemoteVideoTrackStillExists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "owned-avatar-red.png")
        Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
            photo.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
        val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val source = factory.createVideoSource(false)
        val retainedTrack = factory.createVideoTrack("owned-retained-guest", source)
        try {
            compose.setContent { MaterialTheme {
                LiveVideoLayout(null, "Anfitrión", "owned-host", null, false,
                    mapOf("owned-guest-socket" to retainedTrack), emptyList(), { _, _ -> false }, { _, _ -> },
                    listOf(LiveGuestParticipant("owned-guest-socket", "owned-guest", "Invitado", Uri.fromFile(photo).toString(), videoEnabled = false)),
                    null, RendererCommon.ScalingType.SCALE_ASPECT_FILL, Modifier.fillMaxSize())
            } }
            compose.onNodeWithTag("live_participant_1").assertIsDisplayed()
            compose.waitUntil(10_000) {
                val pixels = compose.onNodeWithTag("live_participant_1").captureToImage().toPixelMap()
                var red = 0
                for (y in 0 until pixels.height step 4) for (x in 0 until pixels.width step 4) {
                    val pixel = pixels[x, y]
                    if (pixel.red > .8f && pixel.green < .2f && pixel.blue < .2f) red++
                }
                red > 30
            }
            val folder = context.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
            File(folder, "live-camera-off-avatar.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
        } finally {
            compose.waitForIdle()
            retainedTrack.dispose(); source.dispose(); factory.dispose(); photo.delete()
        }
    }
}
