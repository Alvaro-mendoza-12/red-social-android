package conectate.com.presentation.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SharedContentDestinationTest {
    @Test fun copyAndExternalShareResolveToTheSamePost() {
        val link = postShareLink("post-123")
        assertEquals("https://novvinapp.com/post/post-123", link)
        assertEquals(Screen.PostDetail.createRoute("post-123"), sharedContentDestination(link)?.route)
        assertEquals("post-123", sharedContentDestination("conectate://post/post-123")?.id)
    }
    @Test fun loginProgressSurvivesStateRestorationAndIsConsumedOnce() {
        val state = SavedStateHandle()
        PendingSharedContentDestination.remember("post", "original-post", state)
        val restored = SavedStateHandle(state.keys().associateWith { state.get<Any>(it) })
        assertEquals(Screen.PostDetail.createRoute("original-post"), PendingSharedContentDestination.consume(restored))
        assertNull(PendingSharedContentDestination.consume(restored))
    }
    @Test fun profileInvitationsKeepTheirOwnDestination() {
        val state = SavedStateHandle()
        PendingSharedContentDestination.remember("profile", "person-123", state)
        assertEquals(Screen.UserProfile.createRoute("person-123"), PendingSharedContentDestination.consume(state))
        assertEquals("person-123", sharedContentDestination(profileShareLink("person-123"))?.id)
        val previewDestination = sharedContentDestination("https://novvinapp.com/p/mirella_vr07?preview=4")
        assertEquals("profile", previewDestination?.kind)
        assertEquals("mirella_vr07", previewDestination?.id)
    }
    @Test fun anotherSharedLinkReplacesTheOlderPendingDestination() {
        val state = SavedStateHandle()
        PendingSharedContentDestination.remember("post", "first", state)
        PendingSharedContentDestination.remember("post", "second", state)
        assertEquals(Screen.PostDetail.createRoute("second"), PendingSharedContentDestination.consume(state))
    }
    @Test fun malformedAndForeignLinksCannotBecomeApplicationDestinations() {
        for (url in listOf("https://evil.test/post/id", "https://conectate.com.evil.test/post/id",
            "https://novvinapp.com/post/a%2Fb", "https://novvinapp.com/p/a%2Fb", "https://conectate.com/post/../profile",
            "https://u:pass@conectate.com/post/id", "javascript://post/id")) assertNull(url, sharedContentDestination(url))
    }
}
