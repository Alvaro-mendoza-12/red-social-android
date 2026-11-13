package conectate.com.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWriteGuardTest {

    @Test
    fun snapshot_becomesInvalid_afterGuardInvalidation() {
        var activeAccount = "alvaro"
        val guard = SessionWriteGuard { activeAccount }

        val snapshot = guard.capture()

        assertTrue(guard.isValid(snapshot))

        guard.invalidate()

        assertFalse(guard.isValid(snapshot))
    }

    @Test
    fun snapshot_becomesInvalid_whenActiveAccountChanges() {
        var activeAccount = "alvaro"
        val guard = SessionWriteGuard { activeAccount }

        val snapshot = guard.capture()
        activeAccount = "conectate"

        assertFalse(guard.isValid(snapshot))
    }

    @Test
    fun snapshot_respectsExplicitExpectedAccount() {
        var activeAccount = "conectate"
        val guard = SessionWriteGuard { activeAccount }

        val snapshot = guard.capture("alvaro")

        assertFalse(guard.isValid(snapshot))
    }

    @Test
    fun snapshot_fromFirstSession_remainsInvalid_onSwitchAtoBtoA() {
        var activeAccount = "alvaro"
        val guard = SessionWriteGuard { activeAccount }

        val firstSnapshot = guard.capture()
        activeAccount = "conectate"
        guard.invalidate()
        activeAccount = "alvaro"

        assertFalse(guard.isValid(firstSnapshot))
        val secondSnapshot = guard.capture()
        assertTrue(guard.isValid(secondSnapshot))
    }
}
