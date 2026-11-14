package conectate.com.data.repository

import conectate.com.data.supabase.AuthSessionMutationGuard
import org.junit.Assert.*
import org.junit.Test

class CallNotificationVisitTest {
    private class Fixture {
        val guard = AuthSessionMutationGuard()
        var account: String? = "account-a"
        fun visit(callId: String = "call-a") = requireNotNull(CallNotificationVisit.capture(
            callId, guard.captureAccountLease { account },
        ))
        fun switchTo(id: String?) = guard.invalidate { account = id }
    }

    @Test fun aCurrentVisitAcceptsOnlyItsOwnTarget() {
        val visit = Fixture().visit(" call-a ")
        assertEquals("call-a", visit.target.callId)
        assertTrue(visit.matches(visit.target, "CALL-A"))
        assertFalse(visit.matches(visit.target.copy(callId = "call-b"), "call-a"))
        assertFalse(visit.matches(visit.target.copy(visitId = "another-visit"), "call-a"))
        assertFalse(visit.matches(null, "call-a"))
    }

    @Test fun aReplacedOrEndedCallRejectsItsRetainedAction() {
        val visit = Fixture().visit()
        assertFalse(visit.matches(visit.target, "call-b"))
        assertFalse(visit.matches(visit.target, null))
    }

    @Test fun aNewEntryToTheSameCallInvalidatesTheOldTarget() {
        val fixture = Fixture()
        val old = fixture.visit()
        val current = fixture.visit()
        assertNotEquals(old.target, current.target)
        assertFalse(current.matches(old.target, "call-a"))
        assertTrue(current.matches(current.target, "call-a"))
    }

    @Test fun switchingAccountsRejectsThePreviousAccountsAction() {
        val fixture = Fixture()
        val visit = fixture.visit()
        fixture.switchTo("account-b")
        assertFalse(visit.matches(visit.target, "call-a"))
    }

    @Test fun returningToTheSameAccountCannotReviveItsPreviousVisit() {
        val fixture = Fixture()
        val visit = fixture.visit()
        fixture.switchTo("account-b")
        fixture.switchTo("account-a")
        assertFalse(visit.matches(visit.target, "call-a"))
        val current = fixture.visit()
        assertTrue(current.matches(current.target, "call-a"))
    }

    @Test fun tokenRenewalWithoutAccountTransitionPreservesTheVisit() {
        val fixture = Fixture()
        val visit = fixture.visit()
        assertTrue(fixture.guard.commitIfCurrent(fixture.guard.snapshot()) {})
        assertTrue(visit.matches(visit.target, "call-a"))
    }

    @Test fun aLeaseInvalidatedBeforeCaptureCannotCreateAnOwner() {
        val fixture = Fixture()
        val lease = fixture.guard.captureAccountLease { fixture.account }
        fixture.switchTo("account-b")
        assertNull(CallNotificationVisit.capture("call-a", lease))
    }

    @Test fun signedOutAndBlankCallCannotCreateNotificationActions() {
        val fixture = Fixture()
        assertNull(CallNotificationVisit.capture(" ", fixture.guard.captureAccountLease { fixture.account }))
        fixture.switchTo(null)
        assertNull(CallNotificationVisit.capture("call-a", fixture.guard.captureAccountLease { fixture.account }))
    }
}
