package conectate.com.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import conectate.com.domain.live.LiveForegroundSession
import conectate.com.domain.live.LiveForegroundSessionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import conectate.com.test.UnitTestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LiveForegroundSessionTest {
    private class Sessions : LiveForegroundSessionController {
        var current: LiveForegroundSession? = LiveForegroundSession("visit-one", "same-live", true)
        var release: CompletableDeferred<Unit>? = null
        var fail = false
        val closed = mutableListOf<String>()
        override fun currentForegroundSession() = current
        override suspend fun endLive(expectedSessionId: String): Result<Unit> {
            closed += expectedSessionId
            release?.await()
            if (fail) return Result.failure(IllegalStateException("test close failure"))
            if (current?.sessionId == expectedSessionId) current = null
            return Result.success(Unit)
        }
    }

    private fun withService(block: suspend TestScope.(LiveForegroundService, Sessions) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val service = Robolectric.buildService(LiveForegroundService::class.java).get()
        val sessions = Sessions()
        service.liveRepository = sessions
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(LiveForegroundService.CHANNEL_ID, "Live", NotificationManager.IMPORTANCE_LOW)
        )
        try { block(service, sessions) } finally { service.onDestroy(); runCurrent(); Dispatchers.resetMain() }
    }

    private fun start(service: LiveForegroundService, session: LiveForegroundSession, startId: Int) {
        service.onStartCommand(Intent(service, LiveForegroundService::class.java).apply {
            action = LiveForegroundService.ACTION_START
            putExtra(LiveForegroundService.EXTRA_LIVE_ID, session.liveId)
            putExtra(LiveForegroundService.EXTRA_SESSION_ID, session.sessionId)
            putExtra(LiveForegroundService.EXTRA_LIVE_ROLE, "host")
            putExtra(LiveForegroundService.EXTRA_TYPE, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        }, 0, startId)
    }

    private fun closeIntent(service: LiveForegroundService): Intent = shadowOf(
        requireNotNull(shadowOf(service).lastForegroundNotification).actions.single().actionIntent
    ).savedIntent

    private fun notification(service: LiveForegroundService, liveId: String): Notification {
        val method = LiveForegroundService::class.java.getDeclaredMethod(
            "buildNotification", String::class.java, String::class.java, String::class.java
        ).apply { isAccessible = true }
        return method.invoke(service, liveId, "Ensayo LIVE", "host") as Notification
    }

    @Test fun differentLiveNotificationsCannotReuseTheSameCloseAction() {
        val service = Robolectric.buildService(LiveForegroundService::class.java).get()
        val first = notification(service, "live-one").actions.single().actionIntent
        val second = notification(service, "live-two").actions.single().actionIntent
        assertNotEquals("An old close action must not alias a newer notification", first, second)
    }

    @Test fun closeActionIdentifiesTheLiveThatCreatedIt() {
        val service = Robolectric.buildService(LiveForegroundService::class.java).get()
        val close = notification(service, "live-one").actions.single().actionIntent
        assertEquals("live-one", shadowOf(close).savedIntent.getStringExtra(LiveForegroundService.EXTRA_LIVE_ID))
    }

    @Test fun aNewVisitToTheSameLiveHasItsOwnImmutableCloseAction() = withService { service, sessions ->
        start(service, sessions.current!!, 1)
        val oldAction = requireNotNull(shadowOf(service).lastForegroundNotification).actions.single().actionIntent
        sessions.current = LiveForegroundSession("visit-two", "same-live", true)
        start(service, sessions.current!!, 2)
        val newAction = requireNotNull(shadowOf(service).lastForegroundNotification).actions.single().actionIntent
        assertNotEquals(oldAction, newAction)
        assertEquals("visit-one", shadowOf(oldAction).savedIntent.getStringExtra(LiveForegroundService.EXTRA_SESSION_ID))
        assertEquals("visit-two", shadowOf(newAction).savedIntent.getStringExtra(LiveForegroundService.EXTRA_SESSION_ID))
    }

    @Test fun anOldCloseActionCannotEndTheNewVisit() = withService { service, sessions ->
        start(service, sessions.current!!, 1)
        val old = closeIntent(service)
        sessions.current = LiveForegroundSession("visit-two", "same-live", true)
        start(service, sessions.current!!, 2)
        service.onStartCommand(old, 0, 3)
        runCurrent()
        assertTrue(sessions.closed.isEmpty())
        assertEquals("visit-two", LiveForegroundService.foregroundState.value.sessionId)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun aSuspendedCloseCannotRemoveTheNewForegroundNotification() = withService { service, sessions ->
        start(service, sessions.current!!, 1)
        sessions.release = CompletableDeferred()
        service.onStartCommand(closeIntent(service), 0, 2)
        runCurrent()
        assertEquals(listOf("visit-one"), sessions.closed)
        sessions.current = LiveForegroundSession("visit-two", "same-live", true)
        start(service, sessions.current!!, 3)
        sessions.release!!.complete(Unit)
        runCurrent()
        assertEquals("visit-two", LiveForegroundService.foregroundState.value.sessionId)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun aStaleStopCannotRemoveTheNewNotification() = withService { service, sessions ->
        start(service, sessions.current!!, 1)
        sessions.current = LiveForegroundSession("visit-two", "same-live", true)
        start(service, sessions.current!!, 2)
        service.onStartCommand(Intent(service, LiveForegroundService::class.java).apply {
            action = LiveForegroundService.ACTION_STOP
            putExtra(LiveForegroundService.EXTRA_SESSION_ID, "visit-one")
        }, 0, 3)
        assertEquals("visit-two", LiveForegroundService.foregroundState.value.sessionId)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun aDelayedStartCannotReplaceOrStopTheCurrentVisit() = withService { service, sessions ->
        val old = sessions.current!!
        sessions.current = LiveForegroundSession("visit-two", "same-live", true)
        start(service, sessions.current!!, 1)
        start(service, old, 2)
        assertEquals("visit-two", LiveForegroundService.foregroundState.value.sessionId)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun aCurrentCloseStopsOnlyItsOwnForegroundAfterTheSessionEnds() = withService { service, sessions ->
        start(service, sessions.current!!, 1)
        service.onStartCommand(closeIntent(service), 0, 2)
        runCurrent()
        assertEquals(listOf("visit-one"), sessions.closed)
        assertEquals("", LiveForegroundService.foregroundState.value.sessionId)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun aFailedCloseRetainsTheNotificationWhileItsSessionRemainsActive() = withService { service, sessions ->
        start(service, sessions.current!!, 1); sessions.fail = true
        service.onStartCommand(closeIntent(service), 0, 2)
        runCurrent()
        assertEquals("visit-one", LiveForegroundService.foregroundState.value.sessionId)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun aLegacyUnscopedEndIntentCannotCloseACurrentSession() = withService { service, sessions ->
        start(service, sessions.current!!, 1)
        service.onStartCommand(Intent(service, LiveForegroundService::class.java).setAction(LiveForegroundService.ACTION_END), 0, 2)
        runCurrent()
        assertTrue(sessions.closed.isEmpty())
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test fun aLegacyEndOnAnIdleServiceStopsTheServiceWithoutClosingAnotherSession() = withService { service, sessions ->
        service.onStartCommand(Intent(service, LiveForegroundService::class.java).setAction(LiveForegroundService.ACTION_END), 0, 1)
        runCurrent()
        assertTrue(sessions.closed.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun aLateStopOnAnIdleServiceDoesNotLeaveItRunning() = withService { service, sessions ->
        service.onStartCommand(Intent(service, LiveForegroundService::class.java).apply {
            action = LiveForegroundService.ACTION_STOP
            putExtra(LiveForegroundService.EXTRA_SESSION_ID, "departed-visit")
        }, 0, 1)
        assertTrue(sessions.closed.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
