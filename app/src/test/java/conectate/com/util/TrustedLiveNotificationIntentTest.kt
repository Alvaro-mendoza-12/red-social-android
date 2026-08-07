package conectate.com.util

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class TrustedLiveNotificationIntentTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun liveStarted_requiresAnAppIssuedIntentAndBindsTheLiveId() {
        val intent = Intent().apply {
            putExtra("type", "live_started")
            putExtra("live_id", "live-123")
            putExtra("target_id", "live-123")
            putExtra("host_id", "host-1")
        }

        assertFalse(TrustedNavigationIntents.isTrusted(context, intent))

        TrustedNavigationIntents.markTrusted(context, intent)
        assertTrue(TrustedNavigationIntents.isTrusted(context, intent))

        intent.putExtra("live_id", "different-live")
        assertFalse(TrustedNavigationIntents.isTrusted(context, intent))
    }
}
