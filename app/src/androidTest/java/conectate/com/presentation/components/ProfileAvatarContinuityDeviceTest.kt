package conectate.com.presentation.components

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.media.ProfileIdentityImageStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAvatarContinuityDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedPhotoIsPresentAcrossNavigationWithoutRenderingAnInitial() = checkAvatar(false)
    @Test fun savedVideoPosterIsPresentBeforeTheRemoteFirstFrameWithoutRenderingAnInitial() = checkAvatar(true)

    private fun checkAvatar(video: Boolean) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "avatar-qa-${java.util.UUID.randomUUID()}"
        val source = "https://avatar.invalid/${java.util.UUID.randomUUID()}.${if (video) "mp4" else "png"}"
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        val visible = mutableStateOf(true)
        try {
            ProfileIdentityImageStore.save(context, source, bitmap, account)
            compose.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalProfileIdentityAccount provides account) {
                        if (visible.value) CircularProfileImage(
                            imageUrl = source, mediaType = if (video) "video" else "image",
                            fallbackName = "Novvin", size = 64.dp,
                            showMomentRing = false, openMomentOnClick = false,
                            modifier = Modifier.testTag("retained_avatar"),
                        )
                    }
                }
            }
            repeat(4) {
                compose.waitForIdle()
                compose.onAllNodesWithText("N").assertCountEquals(0)
                val pixels = compose.onNodeWithTag("retained_avatar").captureToImage().toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2]
                assertTrue("Saved identity pixels must remain visible while refresh is unavailable", center.red > .8f && center.blue > .8f && center.green < .2f)
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                compose.runOnIdle { visible.value = true }
            }
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
        } finally {
            bitmap.recycle()
            ProfileIdentityImageStore.usage(context, account)?.let { ProfileIdentityImageStore.clear(context, account, it.accountKey) }
        }
    }
}
