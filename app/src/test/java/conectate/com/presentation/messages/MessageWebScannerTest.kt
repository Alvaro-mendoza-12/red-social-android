package conectate.com.presentation.messages

import org.junit.Assert.*
import org.junit.Test

class MessageWebScannerTest {
    @Test fun opensTheExistingScannerRoute() {
        var route: String? = null
        assertTrue(openMessageWebScanner({ route = it }) { fail("The valid action must not fail") })
        assertEquals("web_login_scanner", route)
    }

    @Test fun unavailableGraphReportsAnErrorWithoutCrashingTheInbox() {
        var errors = 0
        assertFalse(openMessageWebScanner({ throw IllegalArgumentException("destination unavailable") }) { errors++ })
        assertFalse(openMessageWebScanner({ throw IllegalStateException("graph not ready") }) { errors++ })
        assertEquals(2, errors)
    }
}
