package conectate.com.presentation.components

import android.os.Build
import android.util.Base64
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
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.intercept.Interceptor
import coil.request.ImageResult
import conectate.com.core.media.ProfileIdentityImageStore
import conectate.com.data.localfallback.UbuntuStorageUrl
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAnimatedAvatarDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun gifAvatarKeepsAnimatingAfterLeavingAndReturningWithoutAnInitial() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val account = "animated-avatar-qa-${java.util.UUID.randomUUID()}"
        val source = UbuntuStorageUrl.baseUrl.trimEnd('/') +
            "/storage/v1/object/public/avatars/$account/avatar.gif"
        val fixture = File(context.cacheDir, "$account.gif").apply {
            // Two opaque 250 ms frames (magenta/cyan), loop forever. No server,
            // account credentials or external media are involved in this test.
            writeBytes(Base64.decode(
                "R0lGODlhAQABAIAAAP8A/wD//yH/C05FVFNDQVBFMi4wAwEAAAAh+QQAGQAAACwAAAAAAQABAAACAkQBACH5BAAZAAAALAAAAAABAAEAAAICTAEAOw==",
                Base64.DEFAULT,
            ))
        }
        val originalLoader = Coil.imageLoader(context)
        val requestedSources = CopyOnWriteArrayList<String>()
        val testLoader = ImageLoader.Builder(context)
            .components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        val requested = chain.request.data.toString()
                        // The application may finish unrelated inbox image work while
                        // this component runs. Only replace this test's unique object;
                        // still capture thumbnail variants so the assertion detects them.
                        return if (requested.startsWith("http") && requested.contains(account)) {
                            requestedSources.add(requested)
                            chain.proceed(chain.request.newBuilder().data(fixture).build())
                        } else chain.proceed(chain.request)
                    }
                })
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .crossfade(false)
            .build()
        val visible = mutableStateOf(true)
        try {
            Coil.setImageLoader(testLoader)
            compose.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalProfileIdentityAccount provides account) {
                        if (visible.value) CircularProfileImage(
                            imageUrl = source,
                            // Inbox snapshots may not contain avatar_media_type.
                            mediaType = null,
                            fallbackName = "Novvin",
                            size = 64.dp,
                            showMomentRing = false,
                            openMomentOnClick = false,
                            modifier = Modifier.testTag("animated_avatar"),
                        )
                    }
                }
            }
            repeat(2) {
                val colorsSeen = mutableSetOf<String>()
                compose.waitUntil(timeoutMillis = 8_000) {
                    val pixels = compose.onNodeWithTag("animated_avatar").captureToImage().toPixelMap()
                    val center = pixels[pixels.width / 2, pixels.height / 2]
                    if (center.red > .8f && center.blue > .8f && center.green < .2f) colorsSeen.add("magenta")
                    if (center.green > .8f && center.blue > .8f && center.red < .2f) colorsSeen.add("cyan")
                    colorsSeen.size == 2
                }
                compose.onAllNodesWithText("N").assertCountEquals(0)
                assertTrue("GIF must render at least two different decoded frames", colorsSeen.size == 2)
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
                if (it == 0) compose.runOnIdle { visible.value = true }
            }
            assertFalse("The animated avatar must load an original source", requestedSources.isEmpty())
            assertTrue("A thumbnail request would flatten the GIF into one frame",
                requestedSources.all { it == source })
        } finally {
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            Coil.setImageLoader(originalLoader)
            testLoader.shutdown()
            fixture.delete()
            ProfileIdentityImageStore.usage(context, account)?.let {
                ProfileIdentityImageStore.clear(context, account, it.accountKey)
            }
        }
    }
}
