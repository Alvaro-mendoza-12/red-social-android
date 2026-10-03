package conectate.com.data.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 30, 34], application = UnitTestApplication::class)
class SyncForegroundNotificationTest {
    @Test fun expeditedSyncHasAnOngoingQuietNotificationWithoutPrivateData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = syncForegroundInfo(context)
        val notification = info.notification
        assertEquals(9_104, info.notificationId)
        assertEquals("Novvin", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Sincronizando mensajes pendientes", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
    }

    @Test @Config(sdk = [30, 34]) fun notificationChannelHasLowImportance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        syncForegroundInfo(context)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel("novvin_pending_sync")
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }
}
