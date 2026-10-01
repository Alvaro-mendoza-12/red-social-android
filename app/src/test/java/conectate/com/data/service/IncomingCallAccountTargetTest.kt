package conectate.com.data.service

import android.app.Application
import conectate.com.data.model.CallInvite
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.notificationAccountTargetFromIntent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IncomingCallAccountTargetTest {
    @Test fun everyCallActionKeepsTheRecipientAccount() {
        val context = RuntimeEnvironment.getApplication()
        IncomingCallNotificationFactory.ensureChannel(context)
        val invite = CallInvite("call-qa", "Llamada", "caller-qa", "Persona", null, "dm", null, false)
        val target = NotificationAccountTarget("recipient-qa", "qa@example.invalid", "Cuenta secundaria")
        val notification = IncomingCallNotificationFactory.build(context, invite, null, null, target)
        assertEquals(target, notificationAccountTargetFromIntent(shadowOf(notification.contentIntent).savedIntent))
        assertEquals(target, notificationAccountTargetFromIntent(shadowOf(notification.fullScreenIntent).savedIntent))
        assertEquals(target, notificationAccountTargetFromIntent(shadowOf(notification.deleteIntent).savedIntent))
        notification.actions.forEach { action ->
            assertEquals(target, notificationAccountTargetFromIntent(shadowOf(action.actionIntent).savedIntent))
        }
    }
}
