package conectate.com.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.model.Chat
import conectate.com.data.repository.message.selectCachedDirectChat
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class OfflineMessageStorageTest {
    @get:Rule val storage = TemporaryFolder()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun pending(id: String) = MessageEntity(
        id = id, chat_id = "chat", sender_id = "active", content = "offline", content_type = "text",
        created_at = "2026-09-04T00:00:00Z", status = "sending", is_synced = false, pending_action = "send"
    )
    private fun chat(id: String, owner: String) = ChatEntity.fromDomain(
        Chat(id = id, participants = listOf(owner, "person"), is_group = false),
        displayName = "Person", displayImage = "file:///saved/avatar.webp", unreadCount = 0,
        lastMsg = "offline", lastTime = "2026-09-04T00:00:00Z", otherUserId = " PERSON "
    )

    @Test fun `Room lookup retains all account candidates instead of the first row`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.chatDao().insertChat(chat("old", "other"))
            db.chatDao().insertChat(chat("mine", "active"))
            val candidates = db.chatDao().getChatsByOtherUserId("person")
            assertEquals(2, candidates.size)
            assertEquals("mine", selectCachedDirectChat(candidates, "active", "person")?.id)
        } finally { db.close() }
    }

    @Test fun `a late failure cannot undo a persisted acknowledgement`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.messageDao().insertMessage(pending("one"))
            db.messageDao().updateSyncStatus("one", "sent", true, null)
            db.messageDao().markPendingSendFailed("one")
            assertTrue(db.messageDao().getMessageById("one")!!.is_synced)
            assertEquals("sent", db.messageDao().getMessageById("one")!!.status)
            db.messageDao().insertMessage(pending("two"))
            db.messageDao().markPendingSendFailed("two")
            assertEquals("failed", db.messageDao().getMessageById("two")!!.status)
            assertTrue(db.messageDao().getPendingSendMessages().isEmpty())
        } finally { db.close() }
    }

    @Test fun `history avatar identity and pending message survive database reopen`() = runTest {
        // A short, dedicated path also leaves room for SQLite's -wal/-shm files
        // under Windows; Robolectric's per-method paths can exceed MAX_PATH.
        val name = java.io.File(storage.root, "outbox.db").absolutePath
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            db.chatDao().insertChat(chat("chat", "active"))
            db.messageDao().insertMessage(pending("one"))
            db.close()
            db = open()
            assertEquals("file:///saved/avatar.webp", db.chatDao().getChatsByOtherUserId("person").single().display_image)
            assertEquals("offline", db.messageDao().getMessageById("one")?.content)
            assertEquals(listOf("one"), db.messageDao().getPendingSendMessages().map { it.id })
        } finally {
            db.close()
        }
    }
}
