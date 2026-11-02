package conectate.com.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.local.entity.CommunityEntity
import conectate.com.data.local.entity.CommunityMessageEntity
import conectate.com.data.local.entity.GroupEntity
import conectate.com.data.local.entity.GroupMessageEntity
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.model.Message
import conectate.com.data.model.GroupMessage
import conectate.com.test.UnitTestApplication
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class MessageReceiptPersistenceTest {
    private lateinit var db: AppDatabase
    private val queries = ConcurrentLinkedQueue<String>()
    private val time = "2026-11-01T12:00:00Z"
    private val delivered = "2026-11-01T12:00:01Z"
    private val read = "2026-11-01T12:00:02Z"
    private fun direct(id: String = "message", status: String = "sent") = MessageEntity.fromDomain(
        Message(id = id, chat_id = "chat", sender_id = "a", content = "original", created_at = time, status = status),
    )
    private fun group(id: String = "message", status: String = "sent") = GroupMessageEntity(
        id = id, group_id = "group", sender_id = "a", content = "original", content_type = "text", created_at = time, status = status,
    )
    private fun community(id: String = "message", status: String = "sent") = CommunityMessageEntity(
        id = id, community_id = "community", sender_id = "a", content = "original", content_type = "text", created_at = time, status = status,
    )

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryCallback(RoomDatabase.QueryCallback { sql, _ -> queries.add(sql) }, Executor { it.run() })
            .build()
        db.groupDao().upsertGroup(GroupEntity("group", "Group", null, null, time, "a", null, null))
        db.communityDao().insertCommunities(listOf(CommunityEntity("community", "Community", null, null, null, false, time, "a", null, null)))
    }

    @After fun close() { db.close() }

    @Test fun `direct acknowledgement cannot overwrite a newer read receipt`() = runBlocking {
        db.messageDao().insertMessage(direct(status = "read").copy(delivered_at = delivered, read_at = read))
        db.messageDao().insertMessageOwned(direct()) {}
        val stored = db.messageDao().getMessageById("message")!!
        assertEquals("read", stored.status)
        assertEquals(delivered, stored.delivered_at)
        assertEquals(read, stored.read_at)
    }

    @Test fun `group acknowledgement cannot overwrite a newer read receipt`() = runBlocking {
        db.groupDao().insertMessages(listOf(group(status = "read").copy(delivered_at = delivered, read_at = read)))
        db.groupDao().insertMessagesOwned(listOf(group())) {}
        val stored = db.groupDao().getMessageById("message")!!
        assertEquals("read", stored.status)
        assertEquals(delivered, stored.delivered_at)
        assertEquals(read, stored.read_at)
    }

    @Test fun `community acknowledgement cannot overwrite a newer read receipt`() = runBlocking {
        db.communityDao().insertMessages(listOf(community(status = "read").copy(delivered_at = delivered, read_at = read)))
        db.communityDao().insertMessagesOwned(listOf(community())) {}
        val stored = db.communityDao().getMessageById("message")!!
        assertEquals("read", stored.status)
        assertEquals(delivered, stored.delivered_at)
        assertEquals(read, stored.read_at)
    }

    @Test fun `history batches and duplicate ids retain the strongest receipt in every table`() = runBlocking {
        db.messageDao().insertMessages(listOf(direct(status = "read"), direct()))
        db.groupDao().insertMessages(listOf(group(status = "delivered"), group()))
        db.communityDao().insertMessages(listOf(community(status = "read"), community()))
        assertEquals("read", db.messageDao().getMessageById("message")!!.status)
        assertEquals("delivered", db.groupDao().getMessageById("message")!!.status)
        assertEquals("read", db.communityDao().getMessageById("message")!!.status)
    }

    @Test fun `late local preparation cannot requeue or replace an acknowledged payload`() = runBlocking {
        db.messageDao().insertMessage(direct())
        db.groupDao().insertMessages(listOf(group()))
        db.communityDao().insertMessages(listOf(community()))
        db.messageDao().insertMessage(direct(status = "sending").copy(content = "stale", is_synced = false, pending_action = "send"))
        db.groupDao().insertMessages(listOf(group(status = "failed").copy(content = "stale", is_synced = false, pending_action = "send")))
        db.communityDao().insertMessages(listOf(community(status = "sending").copy(content = "stale", is_synced = false, pending_action = "send")))
        assertEquals("original", db.messageDao().getMessageById("message")!!.content)
        assertEquals("original", db.groupDao().getMessageById("message")!!.content)
        assertEquals("original", db.communityDao().getMessageById("message")!!.content)
        assertTrue(db.messageDao().getPendingSendMessages().isEmpty())
        assertTrue(db.groupDao().getPendingOutgoingMessages("a", 10).isEmpty())
        assertTrue(db.communityDao().getPendingOutgoingMessages("a", 10).isEmpty())
    }

    @Test fun `late failure flags cannot requeue an acknowledged message`() = runBlocking {
        db.messageDao().insertMessage(direct(status = "read").copy(read_at = read))
        db.groupDao().insertMessages(listOf(group(status = "read").copy(read_at = read)))
        db.communityDao().insertMessages(listOf(community(status = "read").copy(read_at = read)))
        db.messageDao().updateSyncStatus("message", "sending", false, "send")
        db.groupDao().markRetryAttempt("message", time)
        db.groupDao().updateMessageSyncStatus("message", "failed", false, "send")
        db.communityDao().updateMessageSyncStatus("message", false, "send")
        assertEquals("read", db.messageDao().getMessageById("message")!!.status)
        assertEquals("read", db.groupDao().getMessageById("message")!!.status)
        assertTrue(db.messageDao().getPendingSendMessages().isEmpty())
        assertTrue(db.groupDao().getPendingOutgoingMessages("a", 10).isEmpty())
        assertTrue(db.communityDao().getPendingOutgoingMessages("a", 10).isEmpty())
    }

    @Test fun `failed unacknowledged messages still transition back to sending`() = runBlocking {
        db.messageDao().insertMessage(direct(status = "failed").copy(is_synced = false, pending_action = "send"))
        db.groupDao().insertMessages(listOf(group(status = "failed").copy(is_synced = false, pending_action = "send")))
        db.communityDao().insertMessages(listOf(community(status = "failed").copy(is_synced = false, pending_action = "send")))
        db.messageDao().insertMessage(direct(status = "sending").copy(is_synced = false, pending_action = "send"))
        db.groupDao().insertMessages(listOf(group(status = "sending").copy(is_synced = false, pending_action = "send")))
        db.communityDao().insertMessages(listOf(community(status = "sending").copy(is_synced = false, pending_action = "send")))
        assertEquals("sending", db.messageDao().getMessageById("message")!!.status)
        assertEquals("sending", db.groupDao().getMessageById("message")!!.status)
        assertEquals("sending", db.communityDao().getMessageById("message")!!.status)
    }

    @Test fun `same id with another sender never inherits their receipt`() = runBlocking {
        db.messageDao().insertMessage(direct(status = "read").copy(read_at = read))
        db.messageDao().insertMessage(direct().copy(sender_id = "b"))
        val stored = db.messageDao().getMessageById("message")!!
        assertEquals("sent", stored.status)
        assertNull(stored.read_at)
    }

    @Test fun `delivered and read group history rows are already synchronized`() {
        for (status in listOf("sent", "delivered", "received", "read")) {
            val stored = GroupMessageEntity.fromDomain(GroupMessage(
                id = "message", group_id = "group", sender_id = "a", content = "original", created_at = time, status = status,
            ))
            assertTrue("$status must not become an unsynchronized history row", stored.is_synced)
        }
    }

    @Test fun `ack persistence returns the merged receipt used by the caller`() = runBlocking {
        db.messageDao().insertMessage(direct(status = "read").copy(read_at = read))
        db.groupDao().insertMessages(listOf(group(status = "read").copy(read_at = read)))
        db.communityDao().insertMessages(listOf(community(status = "read").copy(read_at = read)))
        assertEquals("read", db.messageDao().persistMessageOwned(direct()) {}.status)
        assertEquals("read", db.groupDao().persistMessagesOwned(listOf(group())) {}.single().status)
        assertEquals("read", db.communityDao().persistMessagesOwned(listOf(community())) {}.single().status)
    }

    @Test fun `cancelled owner transaction preserves the original receipt and payload`() = runBlocking {
        db.messageDao().insertMessage(direct(status = "read").copy(read_at = read))
        var checks = 0
        try {
            db.messageDao().insertMessageOwned(direct().copy(content = "new")) {
                if (++checks == 2) throw CancellationException("Account changed")
            }
            fail("Expected account cancellation")
        } catch (_: CancellationException) { }
        assertEquals(2, checks)
        val stored = db.messageDao().getMessageById("message")!!
        assertEquals("read", stored.status)
        assertEquals("original", stored.content)
    }

    @Test fun `large history merge uses bounded batch reads rather than one lookup per message`() = runBlocking {
        val ids = (0 until 1300).map { "message-$it" }
        db.messageDao().insertMessages(ids.map { direct(it, "read") })
        db.groupDao().insertMessages(ids.map { group(it, "read") })
        db.communityDao().insertMessages(ids.map { community(it, "read") })
        queries.clear()
        db.messageDao().insertMessages(ids.map { direct(it) })
        db.groupDao().insertMessages(ids.map { group(it) })
        db.communityDao().insertMessages(ids.map { community(it) })
        val lookups = queries.filter { it.startsWith("SELECT * FROM") && it.contains("WHERE id IN") }
        assertTrue("Too many history lookups: ${lookups.size}", lookups.size <= 6)
        assertEquals("read", db.messageDao().getMessageById(ids.last())!!.status)
        assertEquals("read", db.groupDao().getMessageById(ids.last())!!.status)
        assertEquals("read", db.communityDao().getMessageById(ids.last())!!.status)
    }
}
