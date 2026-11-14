package conectate.com.services

import android.app.Notification
import conectate.com.domain.calls.CallForegroundSession
import conectate.com.test.UnitTestApplication
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class CallNotificationIdentityTest {
    private fun notifications(firstId: String = "old-call", secondId: String = "new-call"): Pair<Notification, Notification> {
        val service = Robolectric.buildService(CallForegroundService::class.java).get()
        return notification(service, CallForegroundSession("visit-one", firstId, firstId, false, false)) to
            notification(service, CallForegroundSession("visit-two", secondId, secondId, false, false))
    }

    private fun notification(service: CallForegroundService, session: CallForegroundSession): Notification =
        ReflectionHelpers.callInstanceMethod(service, "buildNotification",
            ClassParameter.from(CallForegroundSession::class.java, session))

    @Test fun aNewCallsHangupActionDoesNotReuseThePreviousCallsPendingIntent() {
        val (old, current) = notifications()
        assertNotEquals("A retained hangup action must stay bound to its original call", old.actions[1].actionIntent, current.actions[1].actionIntent)
    }

    @Test fun aNewCallsMuteActionDoesNotReuseThePreviousCallsPendingIntent() {
        val (old, current) = notifications()
        assertNotEquals("A retained mute action must stay bound to its original call", old.actions[0].actionIntent, current.actions[0].actionIntent)
    }

    @Test fun aRetainedReturnActionKeepsItsOriginalCallId() {
        val (old, _) = notifications()
        assertEquals("old-call", shadowOf(old.contentIntent).savedIntent.getStringExtra("call_id"))
    }

    @Test fun aNewVisitToTheSameCallDoesNotReuseItsOldHangupAction() {
        val (old, current) = notifications("same-call", "same-call")
        assertNotEquals("Re-entering the same call must issue a new action owner", old.actions[1].actionIntent, current.actions[1].actionIntent)
    }

    @Test fun refreshingTheSameVisitKeepsTheHangupIdentity() {
        val service = Robolectric.buildService(CallForegroundService::class.java).get()
        val session = CallForegroundSession("stable-visit", "same-call", "Before", false, false)
        val before = notification(service, session)
        val after = notification(service, session.copy(title = "After", isMuted = true))
        assertEquals(before.actions[1].actionIntent, after.actions[1].actionIntent)
    }

    @Test fun aRetainedMuteActionDoesNotBecomeAnUnmuteAction() {
        val service = Robolectric.buildService(CallForegroundService::class.java).get()
        val session = CallForegroundSession("stable-visit", "same-call", "Call", false, false)
        val before = notification(service, session)
        val after = notification(service, session.copy(isMuted = true))
        assertNotEquals(before.actions[0].actionIntent, after.actions[0].actionIntent)
        assertEquals(false, shadowOf(before.actions[0].actionIntent).savedIntent
            .getBooleanExtra(CallForegroundService.EXTRA_IS_MUTED, true))
    }
}
