package conectate.com.data.repository

import org.junit.Assert.*
import org.junit.Test

class NotificationUnreadCounterTest {
    @Test fun slowReadCannotReplaceANewerCompletedRead() {
        val counter = NotificationUnreadCounter { "account-a" }
        val slow = counter.begin()
        val fast = counter.begin()
        assertTrue(counter.publish(fast, 2))
        assertFalse(counter.publish(slow, 9))
        assertEquals(2, counter.unreadCount.value)
    }

    @Test fun aNewerFailedRequestDoesNotDiscardAUsableResponse() {
        val counter = NotificationUnreadCounter { "account-a" }
        val usable = counter.begin()
        counter.begin() // The later HTTP request fails, without publishing a value.
        assertTrue(counter.publish(usable, 4))
        assertEquals(4, counter.unreadCount.value)
    }

    @Test fun accountChangeRejectsBothOldCountsAndMutationRefreshes() {
        var account = "account-a"
        val counter = NotificationUnreadCounter { account }
        val oldRead = counter.begin()
        val oldMarkAll = counter.begin()
        account = "account-b"
        assertTrue(counter.publish(counter.begin(), 7))
        assertFalse(counter.publish(oldRead, 20))
        assertFalse(counter.isCurrent(oldMarkAll))
        assertEquals(7, counter.unreadCount.value)
    }

    @Test fun logoutAndReturnToTheSameAccountRejectsTheOldSession() {
        val counter = NotificationUnreadCounter { "account-a" }
        val old = counter.begin()
        counter.clear()
        assertTrue(counter.publish(counter.begin(), 3))
        assertFalse(counter.publish(old, 11))
        assertEquals(3, counter.unreadCount.value)
    }

    @Test fun clearingTheSessionResetsTheBadgeAndRejectsPendingReads() {
        val counter = NotificationUnreadCounter { "account-a" }
        val request = counter.begin()
        counter.publish(request, 5)
        counter.clear()
        assertEquals(0, counter.unreadCount.value)
        assertFalse(counter.publish(request, 5))
    }

    @Test fun absentAccountCannotPublishEvenAfterLogin() {
        var account: String? = null
        val counter = NotificationUnreadCounter { account }
        val anonymous = counter.begin()
        account = "account-a"
        assertFalse(counter.publish(anonymous, 12))
        assertEquals(0, counter.unreadCount.value)
    }

    @Test fun accountNormalizationAndNonnegativeCountsArePreserved() {
        var account = " Account-A "
        val counter = NotificationUnreadCounter { account }
        val request = counter.begin()
        account = "account-a"
        assertTrue(counter.publish(request, -1))
        assertEquals(0, counter.unreadCount.value)
    }
}
