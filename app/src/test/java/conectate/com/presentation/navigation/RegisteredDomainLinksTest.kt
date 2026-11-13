package conectate.com.presentation.navigation

import android.net.Uri
import conectate.com.data.model.videoIdFromSharedLink
import conectate.com.presentation.messages.CollectionQrParser
import conectate.com.presentation.profile.profileUserIdFromQr
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegisteredDomainLinksTest {
    @Test fun registeredAndLegacyHttpsHostsResolveTheSameContent() {
        for (host in listOf("novvinapp.com", "www.novvinapp.com", "red-social-android.vercel.app")) {
            assertEquals("post-123", sharedContentDestination("https://$host/post/post-123")?.id)
            assertEquals("person-123", sharedContentDestination("https://$host/u/person-123")?.id)
            assertEquals("mirella_vr07", sharedContentDestination("https://$host/p/mirella_vr07?preview=4")?.id)
            assertEquals("profile", sharedContentDestination("https://$host/p/mirella_vr07?preview=4")?.kind)
            assertEquals("room-123", voiceRoomFromLink(Uri.parse("https://$host/voice/room-123")))
            assertEquals("video-123", videoIdFromSharedLink("https://$host/app/reels/video-123"))
            assertEquals("video-123", videoIdFromSharedLink("https://$host/video/video-123"))
            assertEquals("person-123", profileUserIdFromQr("https://$host/u/person-123"))
            assertEquals("mirella_vr07", profileUserIdFromQr("https://$host/p/mirella_vr07?preview=4"))
            assertEquals(Screen.GroupDetail.createRoute("group-123", "tok-99"), resolveHttpsAppRoute(Uri.parse("https://$host/group/group-123?invite=tok-99")))
            assertEquals(Screen.CommunityDetail.createRoute("community-123"), resolveHttpsAppRoute(Uri.parse("https://$host/community/community-123")))
            assertEquals(Screen.VideoPlayer.createRoute("video-123", resumeMs = 5000L), resolveHttpsAppRoute(Uri.parse("https://$host/video/video-123?resumeMs=5000")))
            assertEquals(Screen.WebLoginScanner.createRoute("pair-1", "nonce-2"), resolveHttpsAppRoute(Uri.parse("https://$host/web-login?pairId=pair-1&nonce=nonce-2")))
            assertTrue(isTrustedExternalDeepLinkOrigin("https", host))
            assertTrue(CollectionQrParser.parse("https://$host/community/00000000-0000-4000-8000-000000000071").isSuccess)
        }
        assertEquals("person-123", profileUserIdFromQr("conectate://profile/person-123"))
    }
    @Test fun newDomainTrustDoesNotAcceptImpostorsPortsOrHttp() {
        for (origin in listOf("http://novvinapp.com", "https://novvinapp.com.evil.test", "https://u:p@novvinapp.com", "https://novvinapp.com:444")) {
            assertNull(sharedContentDestination("$origin/post/post-123"))
            assertNull(videoIdFromSharedLink("$origin/app/reels/video-123"))
            assertNull(videoIdFromSharedLink("$origin/video/video-123"))
            assertNull(profileUserIdFromQr("$origin/u/person-123"))
            assertNull(resolveHttpsAppRoute(Uri.parse("$origin/group/group-123?invite=tok-99")))
            assertNull(resolveHttpsAppRoute(Uri.parse("$origin/community/community-123")))
            assertNull(resolveHttpsAppRoute(Uri.parse("$origin/web-login?pairId=pair-1&nonce=nonce-2")))
            assertTrue(CollectionQrParser.parse("$origin/community/00000000-0000-4000-8000-000000000071").isFailure)
        }
    }
}
