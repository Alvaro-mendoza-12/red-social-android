package conectate.com.presentation.navigation

import org.junit.Assert.*
import org.junit.Test

class ExternalNavigationRejectionTest {
    @Test fun IDsRejectSeparatorsEscapesControlsAndLongValues() {
        assertEquals("video-123", safeExternalRouteId(" video-123 "))
        for (bad in listOf("a/b","a?b","a#b","a%b","a%2Fb","a\n123","a b","", "x".repeat(161)))
            assertNull(bad,safeExternalRouteId(bad))
        assertNotNull(safeExternalRouteId("f772befd-8e0e-6bd3-fb6c-43fbc8785cab"))
    }
    @Test fun rejectedNavigationIsConsumedAndSignalsOnlyOnce() {
        var clears=0;var errors=0
        assertTrue(consumeExternalNavigation({clears++},{errors++}) { throw IllegalArgumentException("missing destination") })
        assertEquals(1,clears);assertEquals(1,errors)
    }
    @Test fun unrelatedIntentIsNotConsumed() {
        assertFalse(consumeExternalNavigation({fail()},{fail()}) { false })
    }
}
