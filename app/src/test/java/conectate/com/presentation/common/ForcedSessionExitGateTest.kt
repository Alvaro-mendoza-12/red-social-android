package conectate.com.presentation.common

import org.junit.Assert.*
import org.junit.Test

class ForcedSessionExitGateTest {
    @Test fun oldAccountDeliveriesCannotCloseTheNewAccount() {
        var account: String? = "a"
        val first = ForcedSessionExitGate(account) { account }
        account = "b"
        assertFalse(first.accept("a"))
        val second = ForcedSessionExitGate(account) { account }
        assertTrue(second.accept("b"))
    }
    @Test fun duplicateEventsOnlyProduceOneExitInThisSession() {
        val gate = ForcedSessionExitGate("a") { "a" }
        assertTrue(gate.accept("a"))
        assertFalse(gate.accept("a"))
        assertFalse(gate.accept())
    }
    @Test fun wrongRecipientsAndSignedOutReadsCannotTriggerAnExit() {
        assertFalse(ForcedSessionExitGate("a") { "a" }.accept("b"))
        assertFalse(ForcedSessionExitGate("a") { null }.accept())
        assertFalse(ForcedSessionExitGate(null) { null }.accept())
    }
    @Test fun aFreshSessionDoesNotInheritTheOldHandledLatch() {
        assertTrue(ForcedSessionExitGate("a") { "a" }.accept())
        assertTrue(ForcedSessionExitGate("b") { "b" }.accept())
        assertTrue(ForcedSessionExitGate("a") { "a" }.accept())
    }
}
