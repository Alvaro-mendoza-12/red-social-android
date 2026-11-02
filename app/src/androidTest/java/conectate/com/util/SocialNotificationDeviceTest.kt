package conectate.com.util

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated local alerts only. No account is registered and no server/user receives anything. */
@RunWith(AndroidJUnit4::class)
class SocialNotificationDeviceTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun owner() = NotificationAccountTarget(userId = "fixture-${UUID.randomUUID()}")
    private fun alerts(owner: NotificationAccountTarget) = manager.activeNotifications.filter {
        it.notification.extras.getString(MessageNotificationFactory.EXTRA_ACCOUNT) == owner.userId
    }
    private fun awaitState(description: String, expected: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (!expected() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50L)
        assertTrue(description, expected())
    }
    private fun show(owner: NotificationAccountTarget, id: String, type: String = "like_post") {
        LocalNotificationHelper(context).showSocialNotification(
            account = owner, title = "Comprobación local", body = "Aviso de prueba del emulador",
            type = type, targetId = "fixture-target", notificationId = id,
        )
    }

    @Before fun notificationsArePermittedOnTheIsolatedInstallation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS))
        }
        assertTrue(manager.areNotificationsEnabled())
    }

    @Test(timeout = 15_000L) fun cancellingAQueuedAccountCannotRemoveOrReplaceAnotherAccountsAlert() {
        val removed = owner()
        val retained = owner()
        try {
            show(retained, "same-fixture-record")
            show(removed, "same-fixture-record")
            MessageNotificationFactory.cancelAccount(context, removed.userId)
            awaitState("The retained account's alert was not published") { alerts(retained).size == 1 }
            Thread.sleep(650L) // Allow the queued publication deadline to pass too.
            assertTrue("A removed account was published after cleanup", alerts(removed).isEmpty())
            assertEquals(1, alerts(retained).size)
            LocalNotificationHelper(context).clearAllSocialNotifications(retained.userId)
            awaitState("The retained account's explicit clear did not reach Android") { alerts(retained).isEmpty() }
        } finally {
            MessageNotificationFactory.cancelAccount(context, removed.userId)
            MessageNotificationFactory.cancelAccount(context, retained.userId)
        }
    }

    @Test(timeout = 15_000L) fun liveGroupsAndSummariesAreClearedOnlyForTheirReceivingAccount() {
        val first = owner()
        val second = owner()
        try {
            show(first, "live-fixture", "new_live")
            show(second, "live-fixture", "live_started")
            awaitState("Both LIVE groups must be present") { alerts(first).size == 2 && alerts(second).size == 2 }
            LocalNotificationHelper(context).clearAllSocialNotifications(first.userId)
            awaitState("Clearing the first LIVE group affected the other account") {
                alerts(first).isEmpty() && alerts(second).size == 2
            }
        } finally {
            MessageNotificationFactory.cancelAccount(context, first.userId)
            MessageNotificationFactory.cancelAccount(context, second.userId)
        }
    }
}
