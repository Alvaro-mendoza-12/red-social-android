package conectate.com.data.repository.message

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.model.Chat
import conectate.com.data.model.Message
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class IncomingInboxRecoveryTest {
    private lateinit var db: AppDatabase
    private var current = true
    private val chat = Chat(id = "chat", participants = listOf("a", "peer"))
    private val message = Message(id = "one", chat_id = "chat", sender_id = "peer", content = "Ejemplo ficticio",
        created_at = "2026-11-12T12:00:02Z", status = "delivered")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }
    private suspend fun recover(msg: Message = message, load: suspend (String) -> Chat? = { chat }) = recoverIncomingInbox(
        db, msg, "a", { if (!current) throw CancellationException("Expired visit") }, load,
        commit = { write -> write() },
    )
    private suspend fun inbox() = InboxPreviewReader(db, { "a" }).read("a")

    @Test fun aStoredRealtimeMessageNeedsMembershipBeforeItCanAppearInTheInbox() = runBlocking {
        db.messageDao().insertMessage(message)
        assertTrue(inbox().isEmpty()) // Reproduces the notification-without-conversation state.
        recover()
        val item = inbox().single()
        assertEquals(message.id, item.lastMessage?.id)
        assertEquals(1, item.unreadCount)
        recover() // A duplicate push must neither skip recovery nor double the badge.
        assertEquals(1, inbox().single().unreadCount)
    }

    @Test fun aBurstAlreadyStoredBeforeDiscoveryKeepsItsNewestMessageAndUnreadCount() = runBlocking {
        db.messageDao().insertMessage(message)
        val later = message.copy(id = "two", created_at = "2026-11-12T12:00:03Z")
        db.messageDao().insertMessage(later)
        recover(later); recover(message)
        assertEquals("two", inbox().single().lastMessage?.id)
        assertEquals(2, inbox().single().unreadCount)
    }

    @Test fun senderDataCannotInventMembershipOrAcceptADifferentChat() = runBlocking {
        db.messageDao().insertMessage(message)
        recover { chat.copy(participants = listOf("other", "peer")) }
        assertTrue(inbox().isEmpty())
        recover { chat.copy(id = "different") }
        assertTrue(inbox().isEmpty())
    }

    @Test fun aSlowMetadataReadCannotCommitAfterTheAccountVisitEnds() = runBlocking {
        db.messageDao().insertMessage(message)
        try {
            recover { current = false; chat }
            fail("An expired visit must cancel")
        } catch (_: CancellationException) { }
        assertNull(db.chatDao().getChatById("chat"))
    }

    @Test fun aHiddenConversationOnlyReturnsForAMessageAfterItsClearCutoff() = runBlocking {
        val entity = ChatEntity.fromDomain(chat, "Persona ficticia", null, 0, null, null,
            clearedAt = "2026-11-12T12:00:02Z").copy(is_deleted = true)
        db.chatDao().insertChat(entity)
        db.messageDao().insertMessage(message)
        recover(); assertTrue(inbox().isEmpty())
        val later = message.copy(id = "two", created_at = "2026-11-12T12:00:03Z")
        db.messageDao().insertMessage(later)
        recover(later)
        assertEquals("two", inbox().single().lastMessage?.id)
        assertEquals(1, inbox().single().unreadCount)
    }

    @Test fun networkFailureLeavesTheMessageAvailableForAnInboxRetry() = runBlocking {
        db.messageDao().insertMessage(message)
        recover { null }
        assertNotNull(db.messageDao().getMessageById(message.id))
        assertTrue(inbox().isEmpty())
        recover()
        assertEquals(message.id, inbox().single().lastMessage?.id)
    }
}
