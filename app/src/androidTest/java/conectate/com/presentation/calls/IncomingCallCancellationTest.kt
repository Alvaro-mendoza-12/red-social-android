package conectate.com.presentation.calls

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.service.CallCancellationGuard
import conectate.com.data.model.CallInvite
import conectate.com.services.IncomingCallForegroundService
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local invitations only: no real account, push, ringtone or network call. */
@RunWith(AndroidJUnit4::class)
class IncomingCallCancellationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun cancellingTheVisibleInvitationClosesItsAnswerScreen() {
        val id = newId()
        try {
            ActivityScenario.launch<IncomingCallActivity>(invite(id)).use { scenario ->
                val destroyed = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY) destroyed.countDown()
                    })
                }
                CallCancellationGuard(context).mark(id.uppercase())
                assertTrue("Cancelled call left the answer screen open", destroyed.await(3, TimeUnit.SECONDS))
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally { removeMark(id) }
    }

    @Test fun anAlreadyCancelledInvitationCannotReopenOrAutoAccept() {
        val id = newId()
        try {
            CallCancellationGuard(context).mark(id)
            ActivityScenario.launch<IncomingCallActivity>(invite(id).putExtra(IncomingCallActivity.EXTRA_AUTO_ACCEPT, true)).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally { removeMark(id) }
    }

    @Test fun cancellingAnotherCallDoesNotCloseThisInvitation() {
        val id = newId()
        val other = newId()
        try {
            ActivityScenario.launch<IncomingCallActivity>(invite(id)).use { scenario ->
                CallCancellationGuard(context).mark(other)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { assertFalse(it.isFinishing) }
            }
        } finally { removeMark(id); removeMark(other) }
    }

    @Test fun cancellationStillClosesTheScreenWhenItIsInBackground() {
        val id = newId()
        try {
            ActivityScenario.launch<IncomingCallActivity>(invite(id)).use { scenario ->
                val destroyed = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY) destroyed.countDown()
                    })
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                CallCancellationGuard(context).mark(id)
                assertTrue("Background answer screen survived cancellation", destroyed.await(3, TimeUnit.SECONDS))
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally { removeMark(id) }
    }

    @Test fun aDelayedIntentForAnOldCancelledCallKeepsTheNewInvitation() {
        val id = newId()
        val old = newId()
        try {
            CallCancellationGuard(context).mark(old)
            ActivityScenario.launch<IncomingCallActivity>(invite(id)).use { scenario ->
                context.startActivity(invite(old).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    assertEquals(id, activity.intent.getStringExtra("call_id"))
                }
            }
        } finally { removeMark(id); removeMark(old) }
    }

    @Test fun theRingTimeoutAlsoClosesTheAnswerScreen() {
        val id = newId()
        try {
            ActivityScenario.launch<IncomingCallActivity>(invite(id)).use { scenario ->
                val destroyed = CountDownLatch(1)
                scenario.onActivity { activity ->
                    activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_DESTROY) destroyed.countDown()
                    })
                    IncomingCallForegroundService.start(activity,
                        CallInvite(id, "QA llamada local", UUID.randomUUID().toString(),
                            "QA llamada local", null, "dm", null, false), null)
                }
                assertTrue("Ringing timeout left an answerable screen", destroyed.await(55, TimeUnit.SECONDS))
                assertTrue("Timeout did not mark the expired invitation", CallCancellationGuard(context).contains(id))
                assertEquals(Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally { IncomingCallForegroundService.stop(context, id); removeMark(id) }
    }

    private fun newId() = "qa-screen-" + UUID.randomUUID()

    private fun invite(id: String) = Intent(context, IncomingCallActivity::class.java).apply {
        putExtra("call_id", id)
        putExtra("caller_user_id", UUID.randomUUID().toString())
        putExtra("caller_name", "QA llamada local")
        putExtra("call_title", "QA llamada local")
        putExtra("call_context_type", "dm")
    }

    private fun removeMark(id: String) {
        context.getSharedPreferences("call_notification_guard", Context.MODE_PRIVATE)
            .edit().remove("cancelled_call_$id").commit()
    }
}
