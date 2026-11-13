package conectate.com.presentation.navigation

import android.content.Intent
import android.net.Uri
import conectate.com.data.model.VideoClipRange
import conectate.com.data.model.videoClipLink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class IntentRouterResolutionTest {
    @Test fun sharedVoiceLinksRemainPendingDestinationsAndMatchTheAppRoute() {
        val link = voiceRoomShareLink("room-qa-123")!!
        assertTrue(IntentRouter.hasPendingNavigationIntent(Intent(Intent.ACTION_VIEW, Uri.parse(link))))
        assertEquals("room-qa-123", voiceRoomFromLink(Uri.parse(link)))
        assertEquals("room-qa-123", voiceRoomFromLink(Uri.parse("conectate://voice/room-qa-123")))
        val state = androidx.lifecycle.SavedStateHandle()
        PendingVoiceRoomDestination.remember("room-qa-123", state)
        assertEquals(Screen.RoomDetail.createRoute("room-qa-123"), PendingVoiceRoomDestination.consume(state))
        assertEquals(null, PendingVoiceRoomDestination.consume(state))
    }

    @Test
    fun pendingIntentDetection_acceptsDeepLinksNotificationsAndShares() {
        assertTrue(
            IntentRouter.hasPendingNavigationIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://conectate.com/post/post-88")),
            ),
        )
        assertTrue(
            IntentRouter.hasPendingNavigationIntent(
                Intent().putExtra("type", "new_message"),
            ),
        )
        assertTrue(
            IntentRouter.hasPendingNavigationIntent(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Mira esto")
                },
            ),
        )
        assertFalse(IntentRouter.hasPendingNavigationIntent(Intent()))
        assertFalse(IntentRouter.hasPendingNavigationIntent(null))
    }

    @Test
    fun chapterLinksCarryAValidatedPlaybackPosition() {
        assertEquals(12_000L,
            videoResumeMsFromLink(Uri.parse("conectate://video/video-1?resumeMs=12000")))
        assertEquals(12_000L,
            videoResumeMsFromLink(Uri.parse("https://conectate.com/video/video-1?resumeMs=12000")))
        assertEquals(0L,
            videoResumeMsFromLink(Uri.parse("conectate://video/video-1?resumeMs=-1")))
        assertEquals(0L,
            videoResumeMsFromLink(Uri.parse("conectate://video/video-1?resumeMs=999999999999")))
        assertEquals("video_player/video-1?resumeMs=12000",
            Screen.VideoPlayer.createRoute("video-1", resumeMs = 12_000L))
        val clipLink = Uri.parse("https://conectate.com/video/video-1?resumeMs=12000&endMs=20000")
        assertEquals("https://novvinapp.com/app/reels/video-1?resumeMs=12000&endMs=20000",
            videoClipLink("video-1", VideoClipRange(12_000L, 20_000L)))
        assertEquals(20_000L, videoClipEndMsFromLink(clipLink))
        assertEquals(0L, videoClipEndMsFromLink(
            Uri.parse("https://conectate.com/video/video-1?resumeMs=12000&endMs=16000")))
        assertEquals("video_player/video-1?resumeMs=12000&endMs=20000",
            Screen.VideoPlayer.createRoute("video-1", resumeMs = 12_000L, endMs = 20_000L))
    }

    @Test
    fun liveRoutesRequireAnIdAndAnExplicitRole() {
        assertEquals(Screen.LiveHost.createRoute("live-44"), resolveLiveReturnRoute(" live-44 ", "HOST"))
        assertEquals(Screen.LiveViewer.createRoute("live-44"), resolveLiveReturnRoute("live-44", "viewer"))
        assertEquals(null, resolveLiveReturnRoute("live-44", "admin"))
        assertEquals(null, resolveLiveReturnRoute("", "viewer"))
        assertEquals(Screen.LiveViewer.createRoute("live-55"), resolveLiveStartedRoute(" live-55 "))
        assertEquals(null, resolveLiveStartedRoute(" "))
    }

    @Test
    fun stableScreenRoutesEncodeFocusAndAccountBridgeDestination() {
        val destination = Screen.CommunityChat.createRoute("community-1", "message-99")
        assertEquals("community_chat/community-1?focusMessageId=message-99", destination)

        val bridge = Screen.NotificationAccountBridge.createRoute(
            destinationRoute = Screen.Chat.createRoute("user-77", focusMessageId = "message-1"),
            targetAccountId = "saved-account",
            targetAccountLabel = "Cuenta Novvin",
            currentAccountLabel = "Alvaro",
        )
        assertTrue(bridge.startsWith("notification_account_bridge?destination="))
        assertTrue(bridge.contains("targetAccountId=saved-account"))
        assertTrue(bridge.contains("targetAccountLabel=Cuenta%20Novvin"))
    }
}
