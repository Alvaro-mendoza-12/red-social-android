package conectate.com.presentation.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminPanelNavigationTest {

    @Test
    fun successfulNavigationReturnsTrue() {
        var navigated = false
        val success = attemptAdminPanelNavigation(
            navigate = { navigated = true },
            onFailure = { throw AssertionError("No failure expected", it) }
        )

        assertTrue(success)
        assertTrue(navigated)
    }

    @Test
    fun missingDestinationIsContainedInsteadOfCrashingApp() {
        val failure = IllegalArgumentException("destination admin_panel cannot be found")
        var captured: Throwable? = null

        val success = attemptAdminPanelNavigation(
            navigate = { throw failure },
            onFailure = { captured = it }
        )

        assertFalse(success)
        assertSame(failure, captured)
    }
}
