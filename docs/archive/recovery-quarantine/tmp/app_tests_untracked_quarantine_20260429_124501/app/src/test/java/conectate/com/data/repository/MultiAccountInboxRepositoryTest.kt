package conectate.com.data.repository

import conectate.com.data.model.MultiAccountPendingDestination
import conectate.com.data.model.StoredAccountSession
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiAccountInboxRepositoryTest {

    @Test
    fun mergeInactiveUnreadSnapshots_combinesConversationKeysAcrossSections() {
        val merged = mergeInactiveUnreadSnapshots(
            listOf(
                InactiveAccountUnreadSnapshot(
                    unreadConversationCount = 1,
                    pendingConversationKeys = setOf("chat:chat-a"),
                    latestPendingDestination = MultiAccountPendingDestination(
                        targetType = "chat",
                        conversationId = "chat-a",
                        receivedAt = 100L
                    )
                ),
                InactiveAccountUnreadSnapshot(
                    unreadConversationCount = 2,
                    pendingConversationKeys = setOf("group:group-a", "community:community-a"),
                    latestPendingDestination = MultiAccountPendingDestination(
                        targetType = "community",
                        conversationId = "community-a",
                        receivedAt = 300L
                    )
                )
            )
        )

        assertEquals(3, merged.unreadConversationCount)
        assertEquals(
            setOf("chat:chat-a", "group:group-a", "community:community-a"),
            merged.pendingConversationKeys
        )
        assertEquals("community", merged.latestPendingDestination?.normalizedTargetType)
        assertEquals("community-a", merged.latestPendingDestination?.normalizedConversationId)
    }

    @Test
    fun mergeInactiveUnreadSnapshots_keepsDistinctConversationKeysOnly() {
        val merged = mergeInactiveUnreadSnapshots(
            listOf(
                InactiveAccountUnreadSnapshot(
                    unreadConversationCount = 1,
                    pendingConversationKeys = setOf("group:group-a"),
                    latestPendingDestination = null
                ),
                InactiveAccountUnreadSnapshot(
                    unreadConversationCount = 1,
                    pendingConversationKeys = setOf("group:group-a"),
                    latestPendingDestination = null
                )
            )
        )

        assertEquals(1, merged.unreadConversationCount)
        assertEquals(setOf("group:group-a"), merged.pendingConversationKeys)
        assertNull(merged.latestPendingDestination)
    }

    @Test
    fun resolveStoredAccountUserId_fallsBackToJwtSubWhenUserIdMissing() {
        val account = StoredAccountSession(
            email = "conectate@conectate.test",
            access_token = fakeJwt(sub = "User-From-Token"),
            refresh_token = "refresh-token",
            user_id = null
        )

        assertEquals("user-from-token", resolveStoredAccountUserId(account))
    }

    @Test
    fun resolveStoredAccountUserId_returnsNullWhenNoIdentityAvailable() {
        val account = StoredAccountSession(
            email = "conectate@conectate.test",
            access_token = "not-a-jwt",
            refresh_token = "refresh-token",
            user_id = null
        )

        assertNull(resolveStoredAccountUserId(account))
    }

    private fun fakeJwt(sub: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = encoder.encodeToString("""{"sub":"$sub"}""".toByteArray())
        return "$header.$payload.signature"
    }
}
