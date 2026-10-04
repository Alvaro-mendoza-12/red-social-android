package conectate.com.presentation.calls

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.CallInvite
import conectate.com.data.service.CallCancellationGuard
import conectate.com.data.service.IncomingCallActionReceiver
import conectate.com.services.IncomingCallForegroundService
import conectate.com.util.NotificationAccountTarget
import conectate.com.util.putNotificationAccountTarget
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local invitations only; the absent account target prevents any real decline request. */
@RunWith(AndroidJUnit4::class)
class IncomingCallRejectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val absentAccount = NotificationAccountTarget(userId = UUID.randomUUID().toString(), displayName = "QA local")

    @Test fun rejectingOnTheAnswerScreenImmediatelyCancelsTheInvitation() {
        val invite = invite()
        try {
            ActivityScenario.launch<IncomingCallActivity>(screenIntent(invite)).use { scenario ->
                val destroyed = watchDestruction(scenario)
                compose.onNodeWithText("Rechazar").assertIsDisplayed().performClick()
                assertTrue("Rejected invitation screen stayed open", destroyed.await(3, TimeUnit.SECONDS))
                assertTrue("Rejected invitation can still be accepted by a late intent", CallCancellationGuard(context).contains(invite.callId))
            }
        } finally { removeMark(invite.callId) }
    }

    @Test fun notificationRejectionClosesTheScreenAndPreventsALateAutoAnswer() {
        val invite = invite()
        try {
            ActivityScenario.launch<IncomingCallActivity>(screenIntent(invite)).use { scenario ->
                val destroyed = watchDestruction(scenario)
                context.sendBroadcast(rejectIntent(invite))
                assertTrue("Notification rejection was not recorded locally", await { CallCancellationGuard(context).contains(invite.callId) })
                assertTrue("Notification rejection left the answer screen open", destroyed.await(3, TimeUnit.SECONDS))
            }
            ActivityScenario.launch<IncomingCallActivity>(screenIntent(invite).putExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, true)).use { late ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(Lifecycle.State.DESTROYED, late.state)
            }
        } finally { removeMark(invite.callId) }
    }

    @Test fun rejectingAnOlderNotificationKeepsTheNewCallRinging() {
        val current = invite()
        val previous = invite()
        try {
            ActivityScenario.launch<IncomingCallActivity>(screenIntent(current)).use { scenario ->
                scenario.onActivity { IncomingCallForegroundService.start(it, current, null, absentAccount) }
                assertTrue("Owned incoming service did not start", await(::incomingServiceIsRunning))
                context.sendBroadcast(rejectIntent(previous))
                assertTrue("Old notification action did not finish", await {
                    CallCancellationGuard(context).contains(previous.callId) || !incomingServiceIsRunning()
                })
                assertTrue("Rejecting an old notification stopped a different incoming call", incomingServiceIsRunning())
                assertTrue(CallCancellationGuard(context).contains(previous.callId))
                assertFalse(CallCancellationGuard(context).contains(current.callId))
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
        } finally {
            IncomingCallForegroundService.stop(context, current.callId)
            removeMark(current.callId)
            removeMark(previous.callId)
        }
    }

    private fun watchDestruction(scenario: ActivityScenario<IncomingCallActivity>) = CountDownLatch(1).also { destroyed ->
        scenario.onActivity { activity ->
            activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) destroyed.countDown()
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun incomingServiceIsRunning(): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == IncomingCallForegroundService::class.java.name }

    private fun await(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(20)
        }
        return condition()
    }

    private fun invite() = CallInvite("qa-rejection-${UUID.randomUUID()}", "QA llamada local", UUID.randomUUID().toString(), "QA llamada local", null, "dm", null, false)

    private fun screenIntent(invite: CallInvite) = Intent(context, IncomingCallActivity::class.java).apply { extras(invite) }

    private fun rejectIntent(invite: CallInvite) = Intent(context, IncomingCallActionReceiver::class.java).apply {
        action = IncomingCallActionReceiver.ACTION_DECLINE
        extras(invite)
    }

    private fun Intent.extras(invite: CallInvite) {
        putExtra("call_id", invite.callId)
        putExtra("caller_user_id", invite.callerUserId)
        putExtra("caller_name", invite.callerName)
        putExtra("call_title", invite.title)
        putExtra("call_context_type", "dm")
        putNotificationAccountTarget(absentAccount)
    }

    private fun removeMark(id: String) {
        context.getSharedPreferences("call_notification_guard", Context.MODE_PRIVATE).edit()
            .remove("cancelled_call_$id").commit()
    }
}
