package conectate.com.util

import org.junit.Assert.*
import org.junit.Test

class ConversationNotificationIdentityTest {
    @Test fun identitySeparatesAccountsTypesAndChannels() {
        val one = NotificationAccountTarget(userId = "one")
        val base = ConversationNotificationIdentity.scope(one, "community", "shared", "first")
        assertNotEquals(base, ConversationNotificationIdentity.scope(NotificationAccountTarget(userId = "two"), "community", "shared", "first"))
        assertNotEquals(base, ConversationNotificationIdentity.scope(one, "group", "shared", "first"))
        assertNotEquals(base, ConversationNotificationIdentity.scope(one, "community", "shared", "second"))
        assertEquals(base, ConversationNotificationIdentity.scope(one, " COMMUNITY ", " SHARED ", " FIRST "))
    }
    @Test fun titleUsesCacheAndRemovesGenericCombinedLabel() {
        assertEquals("Clase", ConversationNotificationIdentity.title("group", "Grupo/Comunidad", "Clase"))
        assertEquals("Grupo", ConversationNotificationIdentity.title("group", " ", null))
        assertEquals("Comunidad", ConversationNotificationIdentity.title("community", null, null))
        assertEquals("Nuevo nombre", ConversationNotificationIdentity.title("group", " Nuevo nombre ", "Viejo"))
    }
    @Test fun directReplyBoundsTextAndUtf8WorkData() {
        assertFalse(ConversationNotificationIdentity.canReply(" "))
        assertTrue(ConversationNotificationIdentity.canReply("Gracias"))
        assertTrue(ConversationNotificationIdentity.canReply("a".repeat(2000)))
        assertFalse(ConversationNotificationIdentity.canReply("a".repeat(2001)))
    }
}
