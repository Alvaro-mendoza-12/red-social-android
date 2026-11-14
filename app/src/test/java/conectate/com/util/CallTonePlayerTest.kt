package conectate.com.util

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowToneGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class, shadows = [CallTonePlayerTest.RecordingRingtone::class])
@OptIn(ExperimentalCoroutinesApi::class)
class CallTonePlayerTest {
    @Implements(Ringtone::class)
    class RecordingRingtone {
        @Volatile var playing = false
        val plays = AtomicInteger()
        @Implementation fun isPlaying(): Boolean = playing
        @Implementation fun play() { plays.incrementAndGet(); playing = true }
        @Implementation fun stop() { playing = false }
    }

    private fun setField(player: CallTonePlayer, name: String, value: Any) {
        CallTonePlayer::class.java.getDeclaredField(name).apply { isAccessible = true }.set(player, value)
    }

    private fun withScheduler(test: (CallTonePlayer, TestCoroutineScheduler, RecordingRingtone, AudioManager) -> Unit) {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val player = CallTonePlayer(context)
        setField(player, "scope", scope)
        val ringtone = Shadow.newInstanceOf(Ringtone::class.java)
        setField(player, "incomingRingtone", ringtone)
        try {
            test(player, scheduler, Shadow.extract(ringtone), audio)
        } finally {
            player.stopAll()
            scope.cancel()
            scheduler.runCurrent()
        }
    }

    /** Holds the playback monitor after the coroutine's first cancellation check. */
    private fun stopWhilePlaybackIsWaiting(stop: (CallTonePlayer) -> Unit) {
        val worker = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor { action ->
            Thread(action, "call-tone-race").also(worker::set)
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
        val player = CallTonePlayer(context)
        // Inject only a controlled executor; the baseline production behavior is unchanged.
        setField(player, "scope", scope)
        val monitor = requireNotNull(CallTonePlayer::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(player))
        try {
            synchronized(monitor) {
                player.startRingback()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                }
                assertEquals("Playback must reach the monitor before cancellation", Thread.State.BLOCKED, worker.get()?.state)
                stop(player)
            }
            executor.submit {}.get(5, TimeUnit.SECONDS)
            assertTrue("A stopped call must not play a late ringback", ShadowToneGenerator.getPlayedTones().isEmpty())
        } finally {
            player.stopAll()
            scope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test fun stoppingRingbackRejectsPlaybackAlreadyWaitingOnTheMonitor() {
        stopWhilePlaybackIsWaiting(CallTonePlayer::stopRingback)
    }

    @Test fun endingTheCallRejectsPlaybackAlreadyWaitingOnTheMonitor() {
        stopWhilePlaybackIsWaiting(CallTonePlayer::stopAll)
    }

    @Test fun stoppingQueuedTonesPreventsTheirFirstPlayback() = withScheduler { player, scheduler, ringtone, _ ->
        player.startRingback()
        player.stopRingback()
        player.startIncomingRingtone()
        player.stopAll()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertTrue(ShadowToneGenerator.getPlayedTones().isEmpty())
        assertEquals(0, ringtone.plays.get())
    }

    @Test fun repeatedStartsKeepOnlyOneOutgoingLoop() = withScheduler { player, scheduler, _, _ ->
        repeat(10) { player.startRingback() }
        scheduler.runCurrent()
        assertEquals(1, ShadowToneGenerator.getPlayedTones().size)
        scheduler.advanceTimeBy(1_700)
        scheduler.runCurrent()
        assertEquals(2, ShadowToneGenerator.getPlayedTones().size)
        player.stopAll()
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertEquals(2, ShadowToneGenerator.getPlayedTones().size)
    }

    @Test fun incomingToneStopsAndCannotRestartFromItsOldTimer() = withScheduler { player, scheduler, ringtone, _ ->
        repeat(10) { player.startIncomingRingtone() }
        scheduler.runCurrent()
        assertTrue(ringtone.playing)
        assertEquals(1, ringtone.plays.get())
        repeat(3) { player.stopIncomingRingtone() }
        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        assertTrue(!ringtone.playing)
        assertEquals(1, ringtone.plays.get())
    }

    @Test fun switchingToneDirectionCancelsThePreviousLoop() = withScheduler { player, scheduler, ringtone, _ ->
        player.startIncomingRingtone()
        scheduler.runCurrent()
        player.startRingback()
        scheduler.runCurrent()
        assertTrue(!ringtone.playing)
        scheduler.advanceTimeBy(3_500)
        scheduler.runCurrent()
        assertEquals(1, ringtone.plays.get())
        val outgoingCount = ShadowToneGenerator.getPlayedTones().size
        player.startIncomingRingtone()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()
        assertTrue(ringtone.playing)
        assertEquals(2, ringtone.plays.get())
        assertEquals(outgoingCount, ShadowToneGenerator.getPlayedTones().size)
    }

    @Test fun aNewCallCanRingAfterThePreviousCallWasStopped() = withScheduler { player, scheduler, ringtone, _ ->
        player.startRingback()
        scheduler.runCurrent()
        player.stopAll()
        player.startRingback()
        scheduler.runCurrent()
        assertEquals(2, ShadowToneGenerator.getPlayedTones().size)
        player.startIncomingRingtone()
        scheduler.runCurrent()
        player.stopAll()
        player.startIncomingRingtone()
        scheduler.runCurrent()
        assertEquals(2, ringtone.plays.get())
        assertTrue(ringtone.playing)
    }

    @Test fun silentAndVibrateModesDoNotStartCallAudio() = withScheduler { player, scheduler, ringtone, audio ->
        for (mode in listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE)) {
            audio.ringerMode = mode
            player.startRingback()
            player.startIncomingRingtone()
            scheduler.runCurrent()
        }
        assertTrue(ShadowToneGenerator.getPlayedTones().isEmpty())
        assertEquals(0, ringtone.plays.get())
    }
}
