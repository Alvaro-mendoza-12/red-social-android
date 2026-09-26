package conectate.com.presentation.messages

import conectate.com.data.model.Chat
import conectate.com.data.model.Message
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatWorkspaceTest {
    private fun entry(kind: ChatWorkspaceKind, text: String = "Confirmar la hora", source: String? = null) =
        ChatWorkspaceEntry(kind = kind, text = text, sourceMessageId = source)

    @Test fun `tasks context and drafts survive serialization with their source and completion`() {
        val task = entry(ChatWorkspaceKind.TASK, source = "message-1")
        val workspace = ChatWorkspace().add(task)
            .add(entry(ChatWorkspaceKind.CONTEXT, "La reunión es en la sede norte", "message-2"))
            .add(entry(ChatWorkspaceKind.DRAFT, "Llegaré a las tres"))
            .toggleTask(task.id)
        val restored = Json.decodeFromString<ChatWorkspace>(Json.encodeToString(workspace))
        assertEquals(workspace, restored)
        assertTrue(restored.entries.first { it.id == task.id }.completed)
        assertEquals("message-1", restored.entries.first { it.id == task.id }.sourceMessageId)
        assertFalse(restored.toggleTask(task.id).entries.first { it.id == task.id }.completed)
    }

    @Test fun `duplicate taps do not exhaust capacity but different source context is retained`() {
        val note = entry(ChatWorkspaceKind.CONTEXT, source = "one")
        val workspace = ChatWorkspace().add(note).add(note.copy(id = "duplicate"))
        assertEquals(1, workspace.entries.size)
        assertEquals(2, workspace.add(note.copy(id = "different", sourceMessageId = "two")).entries.size)
    }

    @Test fun `capacity error preserves existing drafts without silently deleting oldest`() {
        val workspace = (1..ChatWorkspace.MAX_PER_KIND).fold(ChatWorkspace()) { state, index ->
            state.add(entry(ChatWorkspaceKind.DRAFT, "Respuesta $index"))
        }
        assertThrows(IllegalArgumentException::class.java) { workspace.add(entry(ChatWorkspaceKind.DRAFT, "Una más")) }
        assertTrue(workspace.entries.any { it.text == "Respuesta 1" })
        assertEquals(51, workspace.add(entry(ChatWorkspaceKind.TASK)).entries.size)
    }

    @Test fun `blank and oversized entries are rejected instead of appearing saved`() {
        assertThrows(IllegalArgumentException::class.java) { ChatWorkspace().add(entry(ChatWorkspaceKind.TASK, "  ")) }
        assertThrows(IllegalArgumentException::class.java) { ChatWorkspace().add(entry(ChatWorkspaceKind.DRAFT, "x".repeat(4001))) }
    }

    @Test fun `deleting one item does not remove other kinds or its original message`() {
        val task = entry(ChatWorkspaceKind.TASK, source = "original")
        val context = entry(ChatWorkspaceKind.CONTEXT, source = "original")
        val workspace = ChatWorkspace().add(task).add(context).remove(task.id)
        assertEquals(listOf(context), workspace.entries)
        assertEquals("original", workspace.entries.single().sourceMessageId)
    }

    @Test fun `direct conversation scope survives placeholder creation and participant order`() {
        val pending = Chat(id = "placeholder_person", participants = listOf(" ME ", "PERSON"))
        val created = Chat(id = "real-chat", participants = listOf("person", "me"))
        assertEquals(chatWorkspaceScope("me", pending), chatWorkspaceScope("me", created))
        assertNull(chatWorkspaceScope("someone-else", created))
        assertNull(chatWorkspaceScope("", created))
    }

    @Test fun `encrypted storage keys isolate accounts and conversations without leaking IDs`() {
        val key = chatWorkspaceStorageKey("me", "dm:person")
        assertEquals(key, chatWorkspaceStorageKey(" ME ", "dm:person"))
        assertNotEquals(key, chatWorkspaceStorageKey("other", "dm:person"))
        assertNotEquals(key, chatWorkspaceStorageKey("me", "dm:another-person"))
        assertEquals(64, key.length)
        assertFalse(key.contains("person"))
    }

    @Test fun `group scope stays distinct from direct participant pair`() {
        val direct = Chat(id = "one", participants = listOf("me", "other"))
        assertNotEquals(chatWorkspaceScope("me", direct), chatWorkspaceScope("me", direct.copy(is_group = true)))
    }

    @Test fun `deleted and view once content cannot become a private copy`() {
        val text = Message(content = "Idea", content_type = "text")
        assertTrue(canSaveMessageInWorkspace(text, "me"))
        assertFalse(canSaveMessageInWorkspace(text.copy(deleted_for_all = true), "me"))
        assertFalse(canSaveMessageInWorkspace(text.copy(deleted_by = listOf("ME")), "me"))
        assertFalse(canSaveMessageInWorkspace(text.copy(content_type = "view_once_image"), "me"))
    }

    @Test fun `incoming messages and receipt updates preserve historical scroll position`() {
        val latest = Message(id = "new", sender_id = "other")
        assertFalse(shouldScrollChatToLatest("previous", latest, wasNearBottom = false, "me"))
        assertTrue(shouldScrollChatToLatest("previous", latest, wasNearBottom = true, "me"))
        assertFalse(shouldScrollChatToLatest("new", latest.copy(status = "read"), wasNearBottom = true, "me"))
        assertTrue(shouldScrollChatToLatest("previous", latest.copy(sender_id = "me"), wasNearBottom = false, "me"))
        assertTrue(shouldScrollChatToLatest(null, latest, wasNearBottom = false, "me"))
    }

    @Test fun `planned tasks survive reopening and sort due work before unscheduled and completed`() {
        val unscheduled = entry(ChatWorkspaceKind.TASK, "Sin fecha")
        val later = entry(ChatWorkspaceKind.TASK, "Después")
        val earlier = entry(ChatWorkspaceKind.TASK, "Primero")
        val done = entry(ChatWorkspaceKind.TASK, "Hecho")
        val workspace = ChatWorkspace().add(unscheduled).add(later).add(earlier).add(done)
            .scheduleTask(later.id, 2_000).scheduleTask(earlier.id, 1_000)
            .scheduleTask(done.id, 500).toggleTask(done.id)
        val restored = Json.decodeFromString<ChatWorkspace>(Json.encodeToString(workspace))
        assertEquals(listOf(earlier.id, later.id, unscheduled.id, done.id), orderedWorkspaceEntries(restored.entries).map { it.id })
        assertNull(restored.scheduleTask(earlier.id, null).entries.first { it.id == earlier.id }.dueAt)
        assertThrows(IllegalArgumentException::class.java) { restored.scheduleTask(later.id, -1) }
        val draft = entry(ChatWorkspaceKind.DRAFT)
        assertNull(ChatWorkspace().add(draft).scheduleTask(draft.id, 1000).entries.single().dueAt)
    }

    @Test fun `old private workspace data can still be read without scheduled dates`() {
        val oldJson = """{"entries":[{"id":"old","kind":"TASK","text":"Conservar","createdAt":123}]}"""
        assertNull(Json.decodeFromString<ChatWorkspace>(oldJson).entries.single().dueAt)
    }

    @Test fun `group community and individual channels keep separate private workspaces`() {
        val group = conversationWorkspaceScope("group", "same")
        val community = conversationWorkspaceScope("community", "same")
        assertNotEquals(group, community)
        assertNotEquals(conversationWorkspaceScope("community", "same", "channel-a"), conversationWorkspaceScope("community", "same", "channel-b"))
        assertNotEquals(conversationWorkspaceScope("community", "a:b", "c"), conversationWorkspaceScope("community", "a", "b:c"))
        assertEquals(group, conversationWorkspaceScope("group", " SAME "))
        assertNull(conversationWorkspaceScope("unknown", "same"))
        assertNull(conversationWorkspaceScope("group", " "))
    }
}
