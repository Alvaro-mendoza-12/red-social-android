package conectate.com.presentation.messages

import conectate.com.data.model.Chat
import conectate.com.data.model.ChatFolder
import conectate.com.data.model.ChatWithLastMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingInboxFocusFilterTest {

    @Test
    fun `buildDistinctConversationList collapses duplicated direct chats by other user`() {
        val duplicatedOlder = directConversation(
            chatId = "chat-a-old",
            otherUserId = "user-b",
            lastMessageAt = "2026-04-19T10:00:00Z",
            unreadCount = 1
        )
        val duplicatedNewer = directConversation(
            chatId = "chat-a-new",
            otherUserId = "user-b",
            lastMessageAt = "2026-04-19T11:00:00Z",
            unreadCount = 3
        )
        val anotherDirect = directConversation(
            chatId = "chat-c",
            otherUserId = "user-c",
            lastMessageAt = "2026-04-19T09:00:00Z"
        )

        val distinct = buildDistinctConversationList(
            listOf(duplicatedOlder, duplicatedNewer, anotherDirect)
        )

        assertEquals(2, distinct.size)
        assertEquals("chat-a-new", distinct.first().chat.id)
        assertEquals(3, distinct.first().unreadCount)
    }

    @Test
    fun `focus counts reflect only direct messages inside selected folder`() {
        val folder = ChatFolder(
            id = "prioridad",
            name = "Prioridad",
            chat_ids = listOf("chat-a", "chat-b", "group-z")
        )
        val scoped = filterConversationsByFolder(
            conversations = listOf(
                directConversation(chatId = "chat-a", otherUserId = "user-a", unreadCount = 2),
                directConversation(chatId = "chat-b", otherUserId = "user-b", isPinned = true),
                directConversation(chatId = "chat-c", otherUserId = "user-c", unreadCount = 5, isPinned = true),
                groupConversation(chatId = "group-z")
            ),
            selectedFolderId = folder.id,
            folders = listOf(folder)
        )

        val counts = calculateDirectInboxFocusCounts(scoped)

        assertEquals(2, counts.all)
        assertEquals(1, counts.unread)
        assertEquals(1, counts.pinned)
    }

    @Test
    fun `unread focus keeps unread direct messages while leaving groups untouched`() {
        val unreadDirect = directConversation(chatId = "chat-a", otherUserId = "user-a", unreadCount = 1)
        val readDirect = directConversation(chatId = "chat-b", otherUserId = "user-b", unreadCount = 0)
        val group = groupConversation(chatId = "group-z")

        val filtered = filterDirectInboxByFocus(
            conversations = listOf(unreadDirect, readDirect, group),
            filter = DirectInboxFocusFilter.UNREAD
        )

        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.chat.id == "chat-a" })
        assertTrue(filtered.any { it.chat.id == "group-z" })
    }

    @Test
    fun `buildDistinctConversationList keeps chats with valid participant identity even without preview metadata`() {
        val chatWithoutPreview = ChatWithLastMessage(
            chat = Chat(
                id = "chat-sin-preview",
                participants = listOf("me", "user-b"),
                created_at = "2026-04-19T07:00:00Z",
                last_message = null,
                last_message_at = null,
                is_group = false
            ),
            unreadCount = 0,
            otherUserName = "Bonny",
            otherUserId = "user-b"
        )

        val distinct = buildDistinctConversationList(listOf(chatWithoutPreview))

        assertEquals(1, distinct.size)
        assertEquals("chat-sin-preview", distinct.first().chat.id)
    }

    private fun directConversation(
        chatId: String,
        otherUserId: String,
        lastMessageAt: String = "2026-04-19T08:00:00Z",
        unreadCount: Int = 0,
        isPinned: Boolean = false
    ): ChatWithLastMessage {
        return ChatWithLastMessage(
            chat = Chat(
                id = chatId,
                participants = listOf("me", otherUserId),
                created_at = "2026-04-19T07:00:00Z",
                last_message = "Hola",
                last_message_at = lastMessageAt,
                is_group = false,
                is_pinned = isPinned
            ),
            unreadCount = unreadCount,
            otherUserName = "Usuario ${otherUserId.takeLast(1)}",
            otherUserId = otherUserId
        )
    }

    private fun groupConversation(chatId: String): ChatWithLastMessage {
        return ChatWithLastMessage(
            chat = Chat(
                id = chatId,
                participants = listOf("me", "user-a", "user-b"),
                created_at = "2026-04-19T07:00:00Z",
                last_message = "Mensaje de grupo",
                last_message_at = "2026-04-19T07:30:00Z",
                is_group = true
            )
        )
    }
}
