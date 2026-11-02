package conectate.com.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.TrustedNavigationIntents
import conectate.com.util.notificationAccountTargetFromIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class VoiceRoomReturnIntentTest {
    @Test fun `ongoing room notification uses a supported return type and keeps its receiving account`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = voiceRoomReturnIntent(context, "actual-room", NotificationAccountTarget(userId = "owner"))
        assertEquals("voice_return", intent.getStringExtra("type"))
        assertEquals("actual-room", intent.getStringExtra("room_id"))
        assertEquals("owner", notificationAccountTargetFromIntent(intent)?.userId)
        assertTrue(TrustedNavigationIntents.isTrusted(context, intent))
        intent.putExtra("room_id", "foreign-room")
        assertFalse(TrustedNavigationIntents.isTrusted(context, intent))
    }
}
