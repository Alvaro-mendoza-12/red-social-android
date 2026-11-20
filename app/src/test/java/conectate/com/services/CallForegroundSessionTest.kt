package conectate.com.services

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import conectate.com.domain.calls.CallForegroundSession
import conectate.com.domain.calls.CallForegroundSessionController
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.util.ReflectionHelpers

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class CallForegroundSessionTest {
    private class Sessions : CallForegroundSessionController {
        var current: CallForegroundSession? = CallForegroundSession("visit-one", "same-call", "Call", false, false)
        var release: CompletableDeferred<Unit>? = null
        var fail = false
        val closed = mutableListOf<String>()
        val muted = mutableListOf<Pair<String, Boolean>>()
        override fun currentForegroundSession() = current
        override suspend fun hangup(expectedSessionId: String): Result<Unit> {
            if (current?.sessionId != expectedSessionId) return Result.failure(IllegalStateException("Stale"))
            closed += expectedSessionId
            release?.await()
            if (fail) return Result.failure(IllegalStateException("Failed"))
            if (current?.sessionId == expectedSessionId) current = null
            return Result.success(Unit)
        }
        override fun setMuted(expectedSessionId: String, muted: Boolean): Boolean {
            val session = current?.takeIf { it.sessionId == expectedSessionId } ?: return false
            this.muted += expectedSessionId to muted
            current = session.copy(isMuted = muted)
            return true
        }
        fun replace() { current = current!!.copy(sessionId = "visit-two", title = "Next visit") }
    }

    private fun withService(block: suspend TestScope.(CallForegroundService, Sessions) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val service = Robolectric.buildService(CallForegroundService::class.java).get()
        val sessions = Sessions()
        service.callRepository = sessions
        // This fixture supplies its own dependencies instead of running the Hilt onCreate.
        ReflectionHelpers.setField(service, "mediaWakeLocks", ActiveMediaWakeLocks(service, "Call"))
        shadowOf(service.application).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CallForegroundService.CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_LOW))
        try { block(service, sessions) }
        finally { service.onDestroy(); runCurrent(); Dispatchers.resetMain() }
    }

    private fun command(service: CallForegroundService, action: String, session: CallForegroundSession) =
        Intent(service, CallForegroundService::class.java).setAction(action)
            .putExtra(CallForegroundService.EXTRA_CALL_ID, session.callId)
            .putExtra(CallForegroundService.EXTRA_SESSION_ID, session.sessionId)

    private fun start(service: CallForegroundService, sessions: Sessions, id: Int = 1) {
        service.onStartCommand(command(service, CallForegroundService.ACTION_START, sessions.current!!), 0, id)
        assertNotNull(shadowOf(service).lastForegroundNotification)
    }

    private fun action(service: CallForegroundService, index: Int): Intent =
        shadowOf(shadowOf(service).lastForegroundNotification.actions[index].actionIntent).savedIntent

    @Test fun retainedHangupCannotEndANewVisitToTheSameCall() = withService { service, sessions ->
        start(service, sessions)
        val old = action(service, 1)
        sessions.replace(); start(service, sessions, 2)
        service.onStartCommand(old, 0, 3); runCurrent()
        assertTrue(sessions.closed.isEmpty())
        assertEquals("visit-two", sessions.current?.sessionId)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun retainedMuteCannotChangeAnotherCall() = withService { service, sessions ->
        start(service, sessions)
        val old = action(service, 0)
        sessions.current = sessions.current!!.copy(sessionId = "visit-two", callId = "different-call")
        start(service, sessions, 2)
        service.onStartCommand(old, 0, 3)
        assertTrue(sessions.muted.isEmpty())
        assertFalse(sessions.current!!.isMuted)
    }

    @Test fun suspendedHangupCannotRemoveTheNextVisitsNotification() = withService { service, sessions ->
        start(service, sessions)
        sessions.release = CompletableDeferred()
        service.onStartCommand(action(service, 1), 0, 2); runCurrent()
        assertEquals(listOf("visit-one"), sessions.closed)
        sessions.replace(); start(service, sessions, 3)
        sessions.release!!.complete(Unit); runCurrent()
        assertEquals("visit-two", action(service, 1).getStringExtra(CallForegroundService.EXTRA_SESSION_ID))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun queuedHangupRechecksTheOwnerWhenItActuallyRuns() = withService { service, sessions ->
        start(service, sessions)
        service.onStartCommand(action(service, 1), 0, 2)
        sessions.replace(); start(service, sessions, 3); runCurrent()
        assertTrue(sessions.closed.isEmpty())
        assertEquals("visit-two", sessions.current?.sessionId)
    }

    @Test fun staleStopCannotRemoveTheNewVisitsNotification() = withService { service, sessions ->
        start(service, sessions)
        val old = sessions.current!!
        sessions.replace(); start(service, sessions, 2)
        service.onStartCommand(command(service, CallForegroundService.ACTION_STOP, old), 0, 3)
        assertEquals("visit-two", action(service, 1).getStringExtra(CallForegroundService.EXTRA_SESSION_ID))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun lateStartAndUpdateCannotReplaceTheNewVisitsNotification() = withService { service, sessions ->
        start(service, sessions)
        val old = sessions.current!!
        sessions.replace(); start(service, sessions, 2)
        service.onStartCommand(command(service, CallForegroundService.ACTION_START, old), 0, 3)
        service.onStartCommand(command(service, CallForegroundService.ACTION_UPDATE, old), 0, 4)
        assertEquals("visit-two", action(service, 1).getStringExtra(CallForegroundService.EXTRA_SESSION_ID))
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun failedHangupKeepsTheActiveForeground() = withService { service, sessions ->
        start(service, sessions)
        sessions.fail = true
        service.onStartCommand(action(service, 1), 0, 2); runCurrent()
        assertEquals("visit-one", sessions.current?.sessionId)
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertFalse(shadowOf(service).isForegroundStopped)
    }

    @Test fun currentHangupEndsItsCallAndRemovesForeground() = withService { service, sessions ->
        start(service, sessions)
        val wakeLock = requireNotNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(wakeLock.isHeld)
        service.onStartCommand(action(service, 1), 0, 2); runCurrent()
        assertNull(sessions.current)
        assertEquals(listOf("visit-one"), sessions.closed)
        assertFalse(wakeLock.isHeld)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun retainedMuteHasAnExplicitIdempotentMeaning() = withService { service, sessions ->
        start(service, sessions)
        val mute = action(service, 0)
        service.onStartCommand(mute, 0, 2)
        val unmute = action(service, 0)
        service.onStartCommand(mute, 0, 3)
        assertTrue(sessions.current!!.isMuted)
        assertEquals(listOf("visit-one" to true, "visit-one" to true), sessions.muted)
        service.onStartCommand(unmute, 0, 4)
        assertFalse(sessions.current!!.isMuted)
    }

    @Test fun legacyCommandsWithoutOwnerCannotAffectAnActiveCall() = withService { service, sessions ->
        start(service, sessions)
        listOf(CallForegroundService.ACTION_HANGUP, CallForegroundService.ACTION_MUTE, CallForegroundService.ACTION_STOP)
            .forEachIndexed { index, action -> service.onStartCommand(Intent(service, CallForegroundService::class.java).setAction(action), 0, index + 2) }
        runCurrent()
        assertTrue(sessions.closed.isEmpty()); assertTrue(sessions.muted.isEmpty())
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun legacyCommandOnAnIdleServiceStopsOnlyThatService() = withService { service, sessions ->
        sessions.current = null
        service.onStartCommand(Intent(service, CallForegroundService::class.java).setAction(CallForegroundService.ACTION_HANGUP), 0, 1)
        runCurrent()
        assertTrue(sessions.closed.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun currentStopAfterCallEndRemovesItsForeground() = withService { service, sessions ->
        start(service, sessions)
        val wakeLock = requireNotNull(ShadowPowerManager.getLatestWakeLock())
        assertTrue(wakeLock.isHeld)
        val owner = sessions.current!!
        sessions.current = null
        service.onStartCommand(command(service, CallForegroundService.ACTION_STOP, owner), 0, 2)
        assertFalse(wakeLock.isHeld)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun muteWithoutExplicitMeaningIsRejected() = withService { service, sessions ->
        start(service, sessions)
        service.onStartCommand(command(service, CallForegroundService.ACTION_MUTE, sessions.current!!), 0, 2)
        assertTrue(sessions.muted.isEmpty())
        assertFalse(sessions.current!!.isMuted)
    }

    @Test fun currentActionCannotAdoptAnUnstartedService() = withService { service, sessions ->
        service.onStartCommand(command(service, CallForegroundService.ACTION_HANGUP, sessions.current!!), 0, 1)
        runCurrent()
        assertTrue(sessions.closed.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun destroyingTheServiceCancelsItsQueuedHangup() = withService { service, sessions ->
        start(service, sessions)
        service.onStartCommand(action(service, 1), 0, 2)
        service.onDestroy(); runCurrent()
        assertTrue(sessions.closed.isEmpty())
        assertEquals("visit-one", sessions.current?.sessionId)
    }
}
