package conectate.com.data.repository

import conectate.com.data.supabase.AuthSessionMutationGuard
import org.junit.Assert.*
import org.junit.Test

class LiveMediaVisitTest {
    private class Fixture {
        val guard = AuthSessionMutationGuard()
        var account: String? = "account-a"
        var token = "token-a-first"
        var reads = 0
        val lease = guard.captureAccountLease { account }
        val visit = LiveMediaVisit(lease, token, guard::commitForLease) { reads++; token }
        fun switchTo(id: String, credential: String) = guard.invalidate { account = id; token = credential }
    }

    @Test fun tokenRenewalWithinTheSameVisitUpdatesItsDepartureCredential() {
        val fixture = Fixture()
        assertEquals("token-a-first", fixture.visit.accessToken())
        assertTrue(fixture.guard.commitIfCurrent(fixture.guard.snapshot()) { fixture.token = "token-a-renewed" })
        assertEquals("token-a-renewed", fixture.visit.accessToken())
        fixture.switchTo("account-b", "token-b")
        assertNull(fixture.visit.accessToken())
        assertEquals("token-a-renewed", fixture.visit.outgoingAccessToken())
    }

    @Test fun returningToTheSameAccountDoesNotReviveItsOldProviderOrMembership() {
        val fixture = Fixture()
        val oldProvider = fixture.visit::accessToken
        fixture.switchTo("account-b", "token-b")
        fixture.switchTo("account-a", "token-a-second")
        val current = fixture.guard.captureAccountLease { fixture.account }
        assertNull(oldProvider())
        assertFalse(fixture.visit.belongsTo(current))
        assertEquals("token-a-first", fixture.visit.outgoingAccessToken())
        assertEquals(0, fixture.reads)
    }

    @Test fun closingCannotReadAReplacementCredentialEvenWhenTheAccountIdStillMatches() {
        val fixture = Fixture()
        fixture.visit.closing = true
        fixture.token = "credential-not-captured-by-this-visit"
        assertNull(fixture.visit.accessToken())
        assertEquals("token-a-first", fixture.visit.outgoingAccessToken())
        assertFalse(fixture.visit.isCurrent())
        assertEquals(0, fixture.reads)
    }

    @Test fun membershipRequiresTheSameAuthGuardAndGeneration() {
        val fixture = Fixture()
        assertTrue(fixture.visit.belongsTo(fixture.guard.captureAccountLease { fixture.account }))
        val unrelated = AuthSessionMutationGuard().captureAccountLease { fixture.account }
        assertFalse(fixture.visit.belongsTo(unrelated))
        fixture.switchTo("account-b", "token-b")
        assertFalse(fixture.visit.belongsTo(fixture.lease))
    }

    @Test fun distinctMediaLifetimesCannotShareTheirLocalIdentity() {
        val fixture = Fixture()
        val another = LiveMediaVisit(fixture.lease, fixture.token, fixture.guard::commitForLease) { fixture.token }
        assertNotEquals(fixture.visit.id, another.id)
        assertTrue(fixture.visit.belongsTo(another.account))
    }
}
