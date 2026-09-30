package conectate.com.presentation.voicerooms

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk=[34],application=android.app.Application::class)
class VoiceRoomWebLinkTest {
    @Test fun allowsOnlyWebPagesWithAHost() {
        assertTrue(isVoiceRoomWebLink("https://example.org/path"))
        assertTrue(isVoiceRoomWebLink("http://example.org"))
        for(link in listOf("javascript:alert(1)","file:///sdcard/private","content://private","intent://app","https:///no-host","not a link")) {
            assertFalse(link,isVoiceRoomWebLink(link))
        }
    }
}
