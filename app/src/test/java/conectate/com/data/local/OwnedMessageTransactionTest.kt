package conectate.com.data.local

import android.app.Application
import androidx.room.Room
import conectate.com.data.local.entity.*
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.Message
import conectate.com.data.supabase.AuthSessionMutationGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercise actual Room transactions, including rollback after SQL has already run. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OwnedMessageTransactionTest {
    private lateinit var db: AppDatabase
    private val time = "2026-11-01T12:00:00Z"
    private fun direct() = MessageEntity.fromDomain(Message("message", "chat", "a", "original", created_at = time), false, "send")
    private fun group() = GroupMessageEntity.fromDomain(GroupMessage("message", "group", "a", "original", created_at = time), false, "send")
    private fun community() = CommunityMessageEntity.fromDomain(CommunityMessage(id = "message", community_id = "community", sender_id = "a", content = "original", created_at = time), false, "send")

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).allowMainThreadQueries().build()
        db.groupDao().upsertGroup(GroupEntity("group", "Group", null, null, time, "a", "original", time))
        db.communityDao().insertCommunities(listOf(CommunityEntity("community", "Community", null, null, null, false, time, "a", "original", time)))
        db.chatDao().insertChat(ChatEntity("chat", null, false, "a", "[\"a\",\"b\"]", time, "Chat", null,
            last_message_preview = "original", last_message_id = "original"))
    }
    @After fun close() { db.close() }

    private fun switchDuringWrite(): () -> Unit {
        val auth = AuthSessionMutationGuard()
        var owner = "a"
        val lease = auth.captureAccountLease { owner }
        var checks = 0
        return {
            if (++checks == 2) auth.invalidate { owner = "b" }
            lease.requireCurrent()
        }
    }

    private suspend fun expectRollback(write: suspend () -> Unit) {
        assertTrue(runCatching { write() }.exceptionOrNull() is CancellationException)
    }

    @Test fun cancelledDirectAcceptanceDoesNotLeaveAPendingMessage() = runBlocking {
        expectRollback { db.messageDao().insertMessageOwned(direct(), switchDuringWrite()) }
        assertNull(db.messageDao().getMessageById("message"))
    }

    @Test fun cancelledGroupAcceptanceDoesNotLeaveAPendingMessage() = runBlocking {
        expectRollback { db.groupDao().insertMessagesOwned(listOf(group()), switchDuringWrite()) }
        assertTrue(db.groupDao().getMessagesSync("group").isEmpty())
        assertEquals("original", db.groupDao().getGroupById("group")?.last_message)
    }

    @Test fun cancelledCommunityAcceptanceDoesNotLeaveAPendingMessage() = runBlocking {
        expectRollback { db.communityDao().insertMessagesOwned(listOf(community()), switchDuringWrite()) }
        assertTrue(db.communityDao().getMessagesSync("community").isEmpty())
        assertEquals("original", db.communityDao().getCommunityById("community")?.last_message)
    }

    @Test fun lateAcknowledgementsCannotReplaceMessagesAfterTheAccountChanges() = runBlocking {
        db.messageDao().insertMessage(direct())
        db.groupDao().insertMessages(listOf(group()))
        db.communityDao().insertMessages(listOf(community()))
        expectRollback { db.messageDao().insertMessageOwned(direct().copy(is_synced = true, pending_action = null), switchDuringWrite()) }
        expectRollback { db.groupDao().insertMessagesOwned(listOf(group().copy(is_synced = true, pending_action = null)), switchDuringWrite()) }
        expectRollback { db.communityDao().insertMessagesOwned(listOf(community().copy(is_synced = true, pending_action = null)), switchDuringWrite()) }
        assertFalse(db.messageDao().getMessageById("message")!!.is_synced)
        assertFalse(db.groupDao().getMessageById("message")!!.is_synced)
        assertFalse(db.communityDao().getMessageById("message")!!.is_synced)
    }

    @Test fun obsoletePreviewsRollBackForAllConversationKinds() = runBlocking {
        val later = "2026-11-01T12:01:00Z"
        expectRollback { db.chatDao().updateLastMessageOwned("chat", "late", later, "late", "a", switchDuringWrite()) }
        expectRollback { db.groupDao().updateMetadataOwned("group", "late", later, switchDuringWrite()) }
        expectRollback { db.communityDao().updateMetadataOwned("community", "late", later, switchDuringWrite()) }
        assertEquals("original", db.chatDao().getChatById("chat")?.last_message_preview)
        assertEquals("original", db.groupDao().getGroupById("group")?.last_message)
        assertEquals("original", db.communityDao().getCommunityById("community")?.last_message)
    }

    @Test fun lateFailuresCannotRevertAnExistingPendingState() = runBlocking {
        db.messageDao().insertMessage(direct())
        db.groupDao().insertMessages(listOf(group()))
        db.communityDao().insertMessages(listOf(community()))
        expectRollback { db.messageDao().markPendingSendFailedOwned("message", switchDuringWrite()) }
        expectRollback { db.groupDao().updateMessageSyncStatusOwned("message", "failed", false, null, switchDuringWrite()) }
        expectRollback { db.communityDao().updateMessageSyncStatusOwned("message", false, null, switchDuringWrite()) }
        assertEquals("send", db.messageDao().getMessageById("message")?.pending_action)
        assertEquals("send", db.groupDao().getMessageById("message")?.pending_action)
        assertEquals("send", db.communityDao().getMessageById("message")?.pending_action)
    }

    @Test fun anObsoleteChatLookupCannotReplaceTheCurrentCache() = runBlocking {
        val original = db.chatDao().getChatById("chat")!!
        expectRollback { db.chatDao().insertChatOwned(original.copy(display_name = "obsolete"), switchDuringWrite()) }
        assertEquals("Chat", db.chatDao().getChatById("chat")?.display_name)
    }

    @Test fun ordinarySendsStillPersistAndAcknowledgementsClearThePendingFlag() = runBlocking {
        val lease = AuthSessionMutationGuard().captureAccountLease { "a" }
        val dao = db.messageDao()
        dao.insertMessageOwned(direct(), lease::requireCurrent)
        assertEquals("send", dao.getMessageById("message")?.pending_action)
        dao.insertMessageOwned(direct().copy(is_synced = true, pending_action = null, status = "sent"), lease::requireCurrent)
        assertTrue(dao.getMessageById("message")!!.is_synced)
        assertNull(dao.getMessageById("message")?.pending_action)
    }
}
