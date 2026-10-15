package conectate.com.util

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

/** Real compatibility code path on Android before and after notification channels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 34], application = UnitTestApplication::class)
class DeletedMessageNotificationCompatibilityTest {
    @Test fun recallRedactsPublishedTextBeforeAndAfterChannelSupport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val suffix = UUID.randomUUID().toString()
        val account = NotificationAccountTarget(userId = "owned-$suffix")
        val payload = MessageNotificationPayload(
            channelId = "owned_compatibility", channelName = "Owned fixture", channelDescription = "No network",
            conversationId = "room-$suffix", targetType = "chat", conversationTitle = "Owned conversation",
            senderName = "Owned sender", messageBody = "original private fixture", notificationId = 740042,
            messageId = "message-$suffix", notificationAccountTarget = account, allowInlineActions = false)
        try {
            assertTrue(MessageNotificationFactory.post(context, payload))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1, manager.activeNotifications.count { it.id == payload.notificationId })
            val identity = DeletedMessageIdentity.create(account.userId, payload.targetType, payload.conversationId, payload.messageId)!!
            MessageNotificationFactory.redactMessage(context, identity)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            val notification = manager.activeNotifications.single { it.id == payload.notificationId }.notification
            val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!.messages
            assertEquals(listOf("Mensaje eliminado"), messages.map { it.text.toString() })
            assertEquals("Mensaje eliminado", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertNull(notification.sound)
            assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        } finally {
            MessageNotificationFactory.cancelConversation(context, account, payload.targetType, payload.conversationId)
        }
    }
}
