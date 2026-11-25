package conectate.com.data.service

import android.app.Application
import android.app.Notification
import conectate.com.data.model.CallInvite
import conectate.com.presentation.calls.IncomingCallActivity
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.notificationAccountTargetFromIntent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun openingOneCallCannotReplaceTheAnswerActionOfAnother() {
        val context = RuntimeEnvironment.getApplication()
        IncomingCallNotificationFactory.ensureChannel(context)
        // The old request code for answering A was A.hashCode() + 1, the same as opening B.
        assertEquals("A".hashCode() + 1, "B".hashCode())
        val first = notification("A")
        val firstAnswer = answerAction(first)
        val second = notification("B")

        assertNotEquals(firstAnswer, second.contentIntent)
        val savedAnswer = shadowOf(firstAnswer).savedIntent
        assertEquals("A", savedAnswer.getStringExtra("call_id"))
        assertTrue(savedAnswer.getBooleanExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, false))
        assertEquals("B", shadowOf(second.contentIntent).savedIntent.getStringExtra("call_id"))
    }

    @Test fun requestCodeHashCollisionsDoNotShareCallActions() {
        val context = RuntimeEnvironment.getApplication()
        IncomingCallNotificationFactory.ensureChannel(context)
        assertEquals("FB".hashCode(), "Ea".hashCode())
        val first = notification("FB")
        val second = notification("Ea")

        assertNotEquals(first.contentIntent, second.contentIntent)
        assertNotEquals(answerAction(first), answerAction(second))
        assertNotEquals(first.deleteIntent, second.deleteIntent)
        assertEquals("FB", shadowOf(first.contentIntent).savedIntent.getStringExtra("call_id"))
        assertEquals("FB", shadowOf(first.deleteIntent).savedIntent.getStringExtra("call_id"))
    }

    @Test fun photoRefreshUpdatesOpenAndAnswerButDoesNotSendBytesToDecline() {
        val context = RuntimeEnvironment.getApplication()
        IncomingCallNotificationFactory.ensureChannel(context)
        val initial = notification("refresh")
        val photo = byteArrayOf(1, 2, 3, 4)
        val refreshed = notification("refresh", photo)

        assertEquals(initial.contentIntent, refreshed.contentIntent)
        assertEquals(answerAction(initial), answerAction(refreshed))
        assertArrayEquals(photo, shadowOf(initial.contentIntent).savedIntent.getByteArrayExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES))
        assertArrayEquals(photo, shadowOf(answerAction(initial)).savedIntent.getByteArrayExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES))
        assertNull(shadowOf(refreshed.deleteIntent).savedIntent.getByteArrayExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES))

        val oversized = notification("oversized", ByteArray(INCOMING_CALL_AVATAR_MAX_BYTES + 1))
        assertNull(shadowOf(oversized.contentIntent).savedIntent.getByteArrayExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES))
        assertNull(shadowOf(answerAction(oversized)).savedIntent.getByteArrayExtra(IncomingCallActivity.EXTRA_CALLER_AVATAR_BYTES))
    }

    private fun answerAction(notification: Notification) =
        notification.actions.single {
            shadowOf(it.actionIntent).savedIntent.getBooleanExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, false)
        }.actionIntent

    private fun notification(id: String, photo: ByteArray? = null): Notification {
        val invite = CallInvite(id, "Llamada", "caller-qa", "Persona", null, "dm", null, false)
        return IncomingCallNotificationFactory.build(RuntimeEnvironment.getApplication(), invite, null, photo)
    }
}
