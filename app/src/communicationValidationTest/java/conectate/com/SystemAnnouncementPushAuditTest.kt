package conectate.com

import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.service.MessageNotificationReceiverEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Receives one authorized handset notice from an isolated server outbox. */
@RunWith(AndroidJUnit4::class)
class SystemAnnouncementPushAuditTest {
    @Test(timeout = 150_000) fun isolatedAnnouncementOutboxReachesTheActualHandset() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("auditAnnouncementPush") == "true")
        val nonce = UUID.fromString(arguments.getString("noticeAuditNonce")).toString()
        val context = instrumentation.targetContext
        val auth = EntryPointAccessors.fromApplication(context, MessageNotificationReceiverEntryPoint::class.java).authService()
        val owner = checkNotNull(auth.getCurrentUserId())
        val title = "Prueba autorizada de avisos ${nonce.take(8)}"
        val readyFile = File(context.cacheDir, "announcement-qa-ready-private.json")
        val notifications = context.getSystemService(NotificationManager::class.java)
        var ownNotificationId: Int? = null
        try {
            val tokenReady = CountDownLatch(1)
            var tabletToken: String? = null
            FirebaseMessaging.getInstance().token.addOnCompleteListener {
                if (it.isSuccessful) tabletToken = it.result
                tokenReady.countDown()
            }
            assertTrue("Actual handset Firebase token missing", tokenReady.await(30, TimeUnit.SECONDS))
            assertFalse(tabletToken.isNullOrBlank())
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            readyFile.writeText(JSONObject().put("nonce", nonce).put("recipientId", owner).put("title", title)
                .put("fcmToken", tabletToken).toString())
            val start = System.nanoTime()
            val deadline = start + java.util.concurrent.TimeUnit.SECONDS.toNanos(115)
            while (System.nanoTime() < deadline && ownNotificationId == null) {
                ownNotificationId = notifications.activeNotifications.firstOrNull {
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
                }?.id
                if (ownNotificationId == null) Thread.sleep(100)
            }
            assertNotNull("The isolated notice did not arrive through real FCM", ownNotificationId)
            val notification = notifications.activeNotifications.first { it.id == ownNotificationId }.notification
            val body = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            assertTrue("The notice body must reach the handset", body.contains("Comprobación temporal autorizada"))
            assertEquals(owner, auth.getCurrentUserId())
            File(context.getExternalFilesDir("communication-qa")!!, "announcement-real-push.json").writeText(JSONObject()
                .put("actualFcmNotificationReceived", true).put("titleAndBodyVerified", true)
                .put("ownerSessionPreserved", true).put("onlyAuthorizedHandset", true).toString(2))
        } finally {
            ownNotificationId?.let(notifications::cancel)
            notifications.activeNotifications.filter {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == title
            }.forEach { notifications.cancel(it.id) }
            readyFile.delete()
        }
    }
}
