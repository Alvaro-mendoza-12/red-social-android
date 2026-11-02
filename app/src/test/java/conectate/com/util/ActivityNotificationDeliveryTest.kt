package conectate.com.util

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.local.prefs.AppSettingsPreferences
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 34], application = UnitTestApplication::class)
class ActivityNotificationDeliveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val helper get() = LocalNotificationHelper(context)
    @Before fun permitNotifications() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun owner() = NotificationAccountTarget(userId = "owned-${UUID.randomUUID()}")
    private fun photo() = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    private fun drain() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
    private fun post(account: NotificationAccountTarget, body: String = "Aviso", type: String = "like_post") =
        helper.showSocialNotification(account, "Fixture", body, type, "fixture-target", notificationId = "fixture-record")

    @Test fun disablingSocialAndLiveSwitchesAppliesAtTheCommonPublicationBoundary() {
        val account = owner()
        val preferences = AppSettingsPreferences(context)
        try {
            preferences.setSocialNotificationsEnabled(false)
            assertNull(post(account))
            assertNotNull(post(account, type = "live_started"))
            preferences.setLiveNotificationsEnabled(false)
            assertNull(post(account, type = "new_live"))
            preferences.setSocialNotificationsEnabled(true)
            assertNull(post(account, type = "voice_invite"))
            // A social/message toggle cannot silence an immediate administrative system notice.
            assertNotNull(post(account, type = "system_announcement"))
        } finally {
            preferences.setSocialNotificationsEnabled(true)
            preferences.setLiveNotificationsEnabled(true)
            MessageNotificationFactory.cancelAccount(context, account.userId)
        }
    }

    @Test fun anOptionalPhotoCanEnrichThePendingTextWithoutChangingItsTrustedDestination() {
        val account = owner()
        try {
            val publication = requireNotNull(post(account))
            assertTrue(MessageNotificationFactory.updateAccountNotificationAvatar(context, publication, photo()))
            drain()
            val notification = manager.activeNotifications.single { it.tag == publication.tag }.notification
            assertNotNull(notification.getLargeIcon())
            assertNull(notification.sound)
            val intent = shadowOf(notification.contentIntent).savedIntent
            assertEquals(account.userId, notificationAccountTargetFromIntent(intent)?.userId)
            assertTrue(TrustedNavigationIntents.isTrusted(context, intent))
        } finally { MessageNotificationFactory.cancelAccount(context, account.userId) }
    }

    @Test fun readingBeforeThePhotoCompletesCannotResurrectTheAlert() {
        val account = owner()
        try {
            val publication = requireNotNull(post(account))
            helper.clearSocialNotification(account.userId, "fixture-record")
            assertFalse(MessageNotificationFactory.updateAccountNotificationAvatar(context, publication, photo()))
            drain()
            assertFalse(manager.activeNotifications.any { it.tag == publication.tag })
        } finally { MessageNotificationFactory.cancelAccount(context, account.userId) }
    }

    @Test fun anOldPhotoCannotOverwriteTheSameRecordsMoreRecentPublication() {
        val account = owner()
        try {
            val old = requireNotNull(post(account, "Old"))
            val current = requireNotNull(post(account, "Current"))
            assertFalse(MessageNotificationFactory.updateAccountNotificationAvatar(context, old, photo()))
            assertTrue(MessageNotificationFactory.updateAccountNotificationAvatar(context, current, photo()))
            drain()
            val notification = manager.activeNotifications.single { it.tag == current.tag }.notification
            assertEquals("Current", notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        } finally { MessageNotificationFactory.cancelAccount(context, account.userId) }
    }

    @Test fun swipingAwayTheVisibleAlertWinsOverItsLateOptionalPhoto() {
        val account = owner()
        try {
            val publication = requireNotNull(post(account))
            drain()
            manager.cancel(publication.tag, publication.id)
            assertFalse(MessageNotificationFactory.updateAccountNotificationAvatar(context, publication, photo()))
            drain()
            assertFalse(manager.activeNotifications.any { it.tag == publication.tag })
        } finally { MessageNotificationFactory.cancelAccount(context, account.userId) }
    }

    @Test fun loggingOutCancelsThePendingTextAndThePhotoUpgrade() {
        val account = owner()
        val publication = requireNotNull(post(account))
        MessageNotificationFactory.cancelAccount(context, account.userId)
        assertFalse(MessageNotificationFactory.updateAccountNotificationAvatar(context, publication, photo()))
        drain()
        assertFalse(manager.activeNotifications.any { it.tag == publication.tag })
    }
}
