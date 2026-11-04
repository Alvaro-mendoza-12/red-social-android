package conectate.com.core.media

import conectate.com.presentation.navigation.Screen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ProfileAvatarPlaybackTest {
    @Test fun aTrimmedPreviewHasRealPlaybackBoundariesInsteadOfAPollingSeekLoop() {
        val item = profileAvatarPreviewItem("file:///profile.mp4", 1000, 6000)
        assertEquals("file:///profile.mp4", item.localConfiguration?.uri.toString())
        assertEquals(1000L, item.clippingConfiguration.startPositionMs)
        assertEquals(6000L, item.clippingConfiguration.endPositionMs)
    }
    @Test fun invalidSelectionCannotCreateANegativeOrEmptyPreview() {
        for ((start, end) in listOf(-1L to 1000L, 2000L to 1000L, 0L to 0L)) {
            assertThrows(IllegalArgumentException::class.java) { profileAvatarPreviewItem("file:///profile.mp4", start, end) }
        }
    }
    @Test fun avatarNavigationDisablesLoopingAndPreservesEncodedUrlsAndOlderVideoRoutes() {
        val url = "https://example.test/avatar.mp4?width=320&token=a+b"
        val legacy = Screen.FullScreenVideo.createRoute(url)
        val avatar = Screen.FullScreenVideo.createRoute(url, loop = false)
        assertEquals(legacy + "?loop=false", avatar)
        assertTrue(Screen.FullScreenVideo.route.endsWith("?loop={loop}"))
        assertEquals(url, java.net.URLDecoder.decode(legacy.substringAfter('/'), "UTF-8"))
    }
}
