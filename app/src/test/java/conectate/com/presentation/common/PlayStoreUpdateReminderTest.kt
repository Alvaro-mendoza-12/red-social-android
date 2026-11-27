package conectate.com.presentation.common

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayStoreUpdateReminderTest {
    @Test fun dismissingHidesTheReminderUntilTheNextForegroundVisit() = runTest {
        var checks = 0
        val reminder = PlayStoreUpdateReminder(backgroundScope, 189) { checks++; 190 }

        reminder.enterForeground()
        runCurrent()
        assertEquals(190, reminder.availableVersion.value)
        reminder.dismiss()
        reminder.enterForeground()
        runCurrent()
        assertNull(reminder.availableVersion.value)
        assertEquals(1, checks)

        reminder.leaveForeground()
        reminder.enterForeground()
        runCurrent()
        assertEquals(190, reminder.availableVersion.value)
        assertEquals(2, checks)
    }

    @Test fun onlyANewerVersionCanShowTheReminder() = runTest {
        var available: Int? = null
        val reminder = PlayStoreUpdateReminder(backgroundScope, 189) { available }

        for (version in listOf(null, 0, 188, 189, 190)) {
            available = version
            reminder.enterForeground()
            runCurrent()
            assertEquals(version?.takeIf { it > 189 }, reminder.availableVersion.value)
            reminder.leaveForeground()
            assertNull(reminder.availableVersion.value)
        }
    }

    @Test fun aFailedCheckStaysQuietAndIsRetriedOnNextEntry() = runTest {
        var offline = true
        val reminder = PlayStoreUpdateReminder(backgroundScope, 189) {
            if (offline) throw IOException("No connection")
            190
        }
        reminder.enterForeground()
        runCurrent()
        assertNull(reminder.availableVersion.value)

        offline = false
        reminder.leaveForeground()
        reminder.enterForeground()
        runCurrent()
        assertEquals(190, reminder.availableVersion.value)
    }

    @Test fun aTimedOutCheckDoesNotShowAnUpdate() = runTest {
        var cancelled = false
        val reminder = PlayStoreUpdateReminder(backgroundScope, 189) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        reminder.enterForeground()
        runCurrent()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(true, cancelled)
        assertNull(reminder.availableVersion.value)
    }

    @Test fun aLateResponseCannotRestoreAnOfferFromThePreviousVisit() = runTest {
        val previous = CompletableDeferred<Int>()
        var checks = 0
        val reminder = PlayStoreUpdateReminder(backgroundScope, 189) {
            if (checks++ == 0) withContext(NonCancellable) { previous.await() } else null
        }
        reminder.enterForeground()
        runCurrent()
        reminder.leaveForeground()
        reminder.enterForeground()
        runCurrent()
        previous.complete(190)
        runCurrent()

        assertEquals(2, checks)
        assertNull(reminder.availableVersion.value)
    }
}
