package conectate.com.presentation.navigation

import android.net.Uri
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class VoiceRoomLinksTest {
    @Test fun destinationSurvivesTheAuthenticationScreensAndIsConsumedOnce() {
        val state = androidx.lifecycle.SavedStateHandle()
        PendingVoiceRoomDestination.remember("room_123", state)
        PendingVoiceRoomDestination.remember("../invalid", state)
        assertEquals(Screen.RoomDetail.createRoute("room_123"), PendingVoiceRoomDestination.consume(state))
        assertNull(PendingVoiceRoomDestination.consume(state))
    }
    @Test fun webAndAppResolveTheSameRoomWithoutPasswordsOrTokens() {
        val id = "f8ad8bb2-a53b-4d9e-bf6e-bccca8ec157a"
        val web = voiceRoomShareLink(id)!!
        assertEquals("https://novvinapp.com/voice/$id", web)
        assertEquals(id, voiceRoomFromLink(Uri.parse(web)))
        assertEquals(id, voiceRoomFromLink(Uri.parse("conectate://voice/$id")))
        assertTrue(isTrustedExternalDeepLinkOrigin("conectate", "voice"))
        assertTrue(isTrustedExternalDeepLinkOrigin("https", "red-social-android.vercel.app"))
    }

    @Test fun unsafeOrUnrelatedLinksNeverOpenARoom() {
        listOf("https://evil.test/voice/room", "https://red-social-android.vercel.app.evil.test/voice/room",
            "https://user@red-social-android.vercel.app/voice/room", "https://red-social-android.vercel.app:444/voice/room",
            "https://red-social-android.vercel.app/app/voice/room", "conectate://voice/a/b", "conectate://voice/..",
            "https://red-social-android.vercel.app/voice/a%2Fb").forEach {
            assertNull(it, voiceRoomFromLink(Uri.parse(it)))
        }
        assertNull(voiceRoomShareLink("../room"))
    }
}
