package conectate.com.presentation.navigation

import android.net.Uri
import conectate.com.data.model.videoIdFromSharedLink
import conectate.com.presentation.messages.CollectionQrParser
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
            assertEquals("room-123", voiceRoomFromLink(Uri.parse("https://$host/voice/room-123")))
            assertEquals("video-123", videoIdFromSharedLink("https://$host/app/reels/video-123"))
            assertTrue(isTrustedExternalDeepLinkOrigin("https", host))
            assertTrue(CollectionQrParser.parse("https://$host/community/00000000-0000-4000-8000-000000000071").isSuccess)
        }
    }
    @Test fun newDomainTrustDoesNotAcceptImpostorsPortsOrHttp() {
        for (origin in listOf("http://novvinapp.com", "https://novvinapp.com.evil.test", "https://u:p@novvinapp.com", "https://novvinapp.com:444")) {
            assertNull(sharedContentDestination("$origin/post/post-123"))
            assertNull(videoIdFromSharedLink("$origin/app/reels/video-123"))
            assertTrue(CollectionQrParser.parse("$origin/community/00000000-0000-4000-8000-000000000071").isFailure)
        }
    }
}
