package conectate.com.data.repository

import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthSessionMutationGuard
import org.junit.Assert.*
import org.junit.Test

class NotificationUnreadCounterTest {
    private val lease = AuthSessionLease("account-a") { true }

    @Test fun slowReadCannotReplaceANewerCompletedRead() {
        val counter = NotificationUnreadCounter { "account-a" }
        val slow = counter.begin(lease)
        val fast = counter.begin(lease)
        assertTrue(counter.publish(fast, 2))
        assertFalse(counter.publish(slow, 9))
        assertEquals(2, counter.unreadCount.value)
    }

    @Test fun aNewerFailedRequestDoesNotDiscardAUsableResponse() {
        val counter = NotificationUnreadCounter { "account-a" }
        val usable = counter.begin(lease)
        counter.begin(lease) // The later HTTP request fails, without publishing a value.
        assertTrue(counter.publish(usable, 4))
        assertEquals(4, counter.unreadCount.value)
    }

    @Test fun accountChangeRejectsBothOldCountsAndMutationRefreshes() {
        var account = "account-a"
        val counter = NotificationUnreadCounter { account }
        val oldRead = counter.begin(lease)
        val oldMarkAll = counter.begin(lease)
        account = "account-b"
        assertTrue(counter.publish(counter.begin(AuthSessionLease("account-b") { true }), 7))
        assertFalse(counter.publish(oldRead, 20))
        assertFalse(counter.isCurrent(oldMarkAll))
        assertEquals(7, counter.unreadCount.value)
    }

    @Test fun logoutAndReturnToTheSameAccountRejectsTheOldSession() {
        val counter = NotificationUnreadCounter { "account-a" }
        val old = counter.begin(lease)
        counter.clear()
        assertTrue(counter.publish(counter.begin(lease), 3))
        assertFalse(counter.publish(old, 11))
        assertEquals(3, counter.unreadCount.value)
    }

    @Test fun clearingTheSessionResetsTheBadgeAndRejectsPendingReads() {
        val counter = NotificationUnreadCounter { "account-a" }
        val request = counter.begin(lease)
        counter.publish(request, 5)
        counter.clear()
        assertEquals(0, counter.unreadCount.value)
        assertFalse(counter.publish(request, 5))
    }

    @Test fun noSessionCannotStartARead() {
        var account: String? = null
        val counter = NotificationUnreadCounter { account }
        val anonymous = AuthSessionLease(null) { true }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { counter.begin(anonymous) }
        account = "account-a"
        assertFalse(counter.invalidatePending(anonymous))
        assertEquals(0, counter.unreadCount.value)
    }

    @Test fun accountNormalizationAndNonnegativeCountsArePreserved() {
        var account = " Account-A "
        val counter = NotificationUnreadCounter { account }
        val request = counter.begin(lease)
        account = "account-a"
        assertTrue(counter.publish(request, -1))
        assertEquals(0, counter.unreadCount.value)
    }

    @Test fun confirmedChangeRejectsOlderReadsBeforeTheRefreshCompletes() {
        val counter = NotificationUnreadCounter { "account-a" }
        assertTrue(counter.publish(counter.begin(lease), 3))
        val obsoleteRead = counter.begin(lease)
        assertTrue(counter.invalidatePending(lease))
        assertFalse(counter.publish(obsoleteRead, 8))
        assertEquals(3, counter.unreadCount.value)
        assertTrue(counter.publish(counter.begin(lease), 2))
        assertEquals(2, counter.unreadCount.value)
    }

    @Test fun anOldSignInCannotInvalidateAReadFromTheSameAccountAfterSigningInAgain() {
        val visits = AuthSessionMutationGuard()
        val counter = NotificationUnreadCounter { "account-a" }
        val firstVisit = visits.captureAccountLease { "account-a" }
        val oldRead = counter.begin(firstVisit)
        visits.invalidate {}
        val fresh = counter.begin(visits.captureAccountLease { "account-a" })
        assertFalse(counter.invalidatePending(firstVisit))
        assertFalse(counter.publish(oldRead, 12))
        assertTrue(counter.publish(fresh, 2))
    }
}
