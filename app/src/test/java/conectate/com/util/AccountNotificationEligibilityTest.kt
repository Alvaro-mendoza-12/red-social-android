package conectate.com.util

import conectate.com.data.model.StoredAccountSession
import org.junit.Assert.*
import org.junit.Test

class AccountNotificationEligibilityTest {
    private val a = StoredAccountSession(user_id="a",email="a@example.invalid",access_token="access",refresh_token="refresh")
    @Test fun removedAccountIsIgnoredEvenWhenAnotherAccountIsOpen() {
        assertFalse(canReceiveAccountNotification(NotificationAccountTarget(userId="a"),"b","b@example.invalid",true,emptyList()))
    }
    @Test fun clearingAllDataSuppressesPreviouslyQueuedNotifications() {
        assertFalse(canReceiveAccountNotification(NotificationAccountTarget(userId="a"),null,null,false,emptyList()))
        assertFalse(canReceiveAccountNotification(null,null,null,false,emptyList()))
    }
    @Test fun activeAndRetainedAccountsStillReceiveNotifications() {
        assertTrue(canReceiveAccountNotification(NotificationAccountTarget(userId="b"),"b","b@example.invalid",true,listOf(a)))
        assertTrue(canReceiveAccountNotification(NotificationAccountTarget(userId="a"),"b","b@example.invalid",true,listOf(a)))
    }
    @Test fun incompleteSavedSessionsAndAmbiguousLegacyMessagesAreIgnored() {
        assertFalse(canReceiveAccountNotification(NotificationAccountTarget(userId="a"),null,null,false,listOf(a.copy(refresh_token=""))))
        assertFalse(canReceiveAccountNotification(null,"b","b@example.invalid",true,listOf(a)))
        assertTrue(canReceiveAccountNotification(null,"a","a@example.invalid",true,listOf(a)))
    }
}
