package conectate.com.util

import conectate.com.data.model.StoredAccountSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAccountTargetTest {

    @Test
    fun remoteData_parsesAccountIdentityAndMatchesStoredAccount() {
        val target = notificationAccountTargetFromRemoteData(
            mapOf(
                "recipient_id" to "USER-123",
                "recipient_email" to "Cuenta@Conectate.com",
                "recipient_display_name" to "Conectate"
            )
        )

        assertNotNull(target)
        assertEquals("user-123", target?.userId)
        assertEquals("cuenta@conectate.com", target?.email)
        assertEquals("Conectate", target?.displayName)
        assertTrue(target?.matches("user-123", "otra@cuenta.com") == true)
    }

    @Test
    fun resolveStoredAccountForTarget_findsMatchingSavedAccount() {
        val accounts = listOf(
            StoredAccountSession(
                id = "account-a",
                user_id = "alvaro-id",
                email = "alvaro@conectate.com",
                access_token = "token-a",
                refresh_token = "refresh-a",
                display_name = "Alvaro"
            ),
            StoredAccountSession(
                id = "account-b",
                user_id = "conectate-id",
                email = "conectate@conectate.com",
                access_token = "token-b",
                refresh_token = "refresh-b",
                display_name = "Conectate"
            )
        )

        val resolved = resolveStoredAccountForTarget(
            target = NotificationAccountTarget(
                userId = "conectate-id",
                email = "conectate@conectate.com"
            ),
            accounts = accounts
        )

        assertEquals("account-b", resolved?.id)
        assertEquals("Conectate", resolved?.let(::storedAccountDisplayLabel))
    }
}
