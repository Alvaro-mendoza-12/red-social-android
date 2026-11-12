package conectate.com.presentation.messages

import conectate.com.data.model.Chat
import conectate.com.data.model.ChatFolder
import conectate.com.data.model.ChatWithLastMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxFilterCountsTest {
    @Test fun `incoming favorite and archived chats increment their own badges`() {
        val rows = listOf(row("regular", unread = 4), row("favorite", favorite = true, unread = 2),
            row("archive", archived = true, favorite = true, unread = 8), row("read"),
            row("group", unread = 9, group = true))
        val counts = calculateInboxFilterCounts(rows)
        assertEquals(2, counts.all)
        assertEquals(2, counts.unread)
        assertEquals(1, counts.favorites)
        assertEquals(1, counts.archived)
    }

    @Test fun `reading and archiving update badges without counting message totals`() {
        val message = row("chat", unread = 17, favorite = true)
        assertEquals(1, calculateInboxFilterCounts(listOf(message)).favorites)
        val archived = message.copy(chat = message.chat.copy(is_archived = true))
        assertEquals(InboxFilterCounts(archived = 1), calculateInboxFilterCounts(listOf(archived)))
        assertEquals(InboxFilterCounts(), calculateInboxFilterCounts(listOf(archived.copy(unreadCount = 0))))
    }

    @Test fun `folders count only visible active unread direct conversations once`() {
        val folder = ChatFolder(id = "family", name = "Familia", chat_ids = listOf(" CHAT ", "chat", "archived", "read", "group"))
        val rows = listOf(row("chat", unread = 3), row("archived", unread = 2, archived = true),
            row("read"), row("group", unread = 1, group = true), row("outside", unread = 1))
        assertEquals(mapOf("family" to 1), calculateInboxFilterCounts(rows, listOf(folder)).folders)
    }

    @Test fun `hidden rows stay excluded until a newer incoming message arrives`() {
        val hidden = mapOf("chat" to "2026-11-12T10:01:00Z")
        val message = row("chat", unread = 2, favorite = true, archived = true)
        assertEquals(0, calculateInboxFilterCounts(listOf(message), hiddenSince = hidden).archived)
        val newer = message.copy(chat = message.chat.copy(last_message_at = "2026-11-12T10:02:00Z"))
        assertEquals(1, calculateInboxFilterCounts(listOf(newer), hiddenSince = hidden).archived)
    }

    @Test fun `duplicate snapshots do not inflate badges`() {
        val message = row("chat", unread = 2, favorite = true)
        assertEquals(1, calculateInboxFilterCounts(listOf(message, message)).favorites)
        assertEquals(InboxFilterCounts(), calculateInboxFilterCounts(emptyList()))
    }

    private fun row(id: String, unread: Int = 0, favorite: Boolean = false, archived: Boolean = false, group: Boolean = false) =
        ChatWithLastMessage(
            chat = Chat(id = id, participants = listOf("owner", "other-$id"),
                last_message = "Mensaje ficticio", last_message_at = "2026-11-12T10:00:00Z",
                is_favorite = favorite, is_archived = archived, is_group = group),
            otherUserId = "other-$id", unreadCount = unread,
        )
}
