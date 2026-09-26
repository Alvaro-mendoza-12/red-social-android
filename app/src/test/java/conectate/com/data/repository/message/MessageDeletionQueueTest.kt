package conectate.com.data.repository.message

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.model.Chat
import conectate.com.domain.sync.PendingSyncRetryException
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class MessageDeletionQueueTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var db: AppDatabase
    private val coordinator = MessageSendCoordinator()
    private var tokens = 0
    private var requests = 0
    private var current = true
    private var tokenResult: suspend () -> Result<String> = { Result.success("actor-token") }
    private var response: suspend (conectate.com.data.local.entity.MessageDeletionEntity) -> MessageDeleteResult = { MessageDeleteResult.Applied() }
    private val queue get() = MessageDeletionQueue(db, coordinator,
        { tokens++; tokenResult() }, { item, token ->
            assertEquals("actor-token", token)
            requests++
            response(item)
        })
    private fun message(id: String = "m", sender: String = "a", pending: Boolean = false) = MessageEntity(
        id = id, chat_id = "chat", sender_id = sender, content = "hello", content_type = "text",
        created_at = "2026-09-09T00:00:00Z", status = if (pending) "sending" else "sent",
        is_synced = !pending, pending_action = if (pending) "send" else null
    )
    private suspend fun seed(id: String = "m", sender: String = "a", pending: Boolean = false) {
        db.chatDao().insertChat(ChatEntity.fromDomain(Chat(id = "chat", participants = listOf("a", "b")), null, null, 0, null, null))
        db.messageDao().insertMessage(message(id, sender, pending))
    }
    private suspend fun enqueue(id: String = "m", actor: String = "a", all: Boolean = false) =
        queue.enqueue(id, actor, all) { current }
    private suspend fun retry(actor: String = "a", limit: Int = 30) = queue.retry(actor, limit) { current }
    private suspend fun visible(actor: String) = applyMessageDeletions(
        db.messageDao().getMessagesSync("chat").map { it.toDomain() }, db.messageDeletionDao().forChat("chat"), actor)

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun `recipient deletion belongs to recipient and does not wait for network`() = runBlocking {
        seed()
        assertTrue(enqueue(actor = " B ").isSuccess)
        assertEquals(0, tokens)
        assertEquals(0, requests)
        assertEquals("b", db.messageDeletionDao().pending("b", 1).single().actor_id)
        assertTrue(visible("b").isEmpty())
        assertEquals(1, visible("a").size)
        assertEquals(0, retry("a").getOrThrow())
        assertEquals(0, tokens)
        assertEquals(1, retry("b").getOrThrow())
    }

    @Test fun `nonmember and recipient cannot delete for everyone`() = runBlocking {
        seed()
        assertTrue(enqueue(actor = "c").isFailure)
        assertTrue(enqueue(actor = "b", all = true).isFailure)
        assertTrue(db.messageDeletionDao().forChat("chat").isEmpty())
        assertFalse(db.messageDao().getMessageById("m")!!.deleted_for_all)
    }

    @Test fun `delete for me preserves an outgoing send but everyone cancels it`() = runBlocking {
        seed(pending = true)
        enqueue().getOrThrow()
        assertEquals("send", db.messageDao().getMessageById("m")!!.pending_action)
        assertEquals(1, db.messageDao().getPendingSendMessages().size)
        enqueue(all = true).getOrThrow()
        assertTrue(db.messageDao().getPendingSendMessages().isEmpty())
        assertTrue(visible("b").single().deleted_for_all)
        assertTrue(visible("a").isEmpty())
    }

    @Test fun `inactive account rows cannot consume active batch slots and remainder keeps worker alive`() = runBlocking {
        seed("old", "b"); enqueue("old", "b").getOrThrow()
        seed("one"); enqueue("one").getOrThrow()
        seed("two"); enqueue("two").getOrThrow()
        assertTrue(retry(limit = 1).exceptionOrNull() is PendingSyncRetryException)
        assertEquals(1, requests)
        assertEquals(1, db.messageDeletionDao().pending("b", 10).size)
        assertEquals(1, retry(limit = 1).getOrThrow())
        assertEquals(2, requests)
    }

    @Test fun `token renewal failure retains durable deletion`() = runBlocking {
        seed(); enqueue().getOrThrow()
        tokenResult = { Result.failure(java.io.IOException("offline")) }
        assertTrue(retry().exceptionOrNull() is PendingSyncRetryException)
        assertEquals(1, db.messageDeletionDao().pending("a", 1).size)
        assertEquals(0, requests)
    }

    @Test fun `account change during token renewal prevents transport`() = runBlocking {
        seed(); enqueue().getOrThrow()
        tokenResult = { current = false; Result.success("actor-token") }
        assertTrue(retry().isFailure)
        assertEquals(0, requests)
        assertEquals("pending", db.messageDeletionDao().get("m", "a", false)!!.state)
    }

    @Test fun `account change in flight cannot acknowledge old account state`() = runBlocking {
        seed(); enqueue(all = true).getOrThrow()
        response = { current = false; MessageDeleteResult.Applied(message().toDomain().copy(deleted_for_all = true)) }
        assertTrue(retry().isFailure)
        assertEquals("pending", db.messageDeletionDao().get("m", "a", true)!!.state)
        assertFalse(db.messageDao().getMessageById("m")!!.deleted_for_all)
    }

    @Test fun `late delete acknowledgement preserves concurrent read receipt and reaction`() = runBlocking {
        seed(); enqueue(all = true).getOrThrow()
        response = {
            db.messageDao().insertMessage(message().copy(status = "read", read_at = "2026-09-09T01:00:00Z", reactions_json = "{\"heart\":[\"b\"]}"))
            MessageDeleteResult.Applied(message().toDomain().copy(deleted_for_all = true))
        }
        assertEquals(1, retry().getOrThrow())
        val persisted = db.messageDao().getMessageById("m")!!
        assertEquals("read", persisted.status)
        assertEquals("2026-09-09T01:00:00Z", persisted.read_at)
        assertEquals(listOf("b"), persisted.toDomain().reactions["heart"])
        assertTrue(persisted.deleted_for_all)
    }

    @Test fun `history cannot resurrect pending or confirmed deletion`() = runBlocking {
        seed(); enqueue(all = true).getOrThrow()
        db.messageDao().insertMessage(message())
        assertTrue(visible("b").single().deleted_for_all)
        retry().getOrThrow()
        db.messageDao().insertMessage(message())
        assertTrue(visible("b").single().deleted_for_all)
    }

    @Test fun `late optimistic insert cannot requeue a cancelled send`() = runBlocking {
        seed(pending = true); enqueue(all = true).getOrThrow()
        db.messageDao().insertMessage(message(pending = true))
        assertTrue(db.messageDao().getPendingSendMessages().isEmpty())
    }

    @Test fun `server rejection removes optimistic all tombstone and records actionable failure`() = runBlocking {
        seed(); enqueue(all = true).getOrThrow()
        response = { MessageDeleteResult.Rejected("denied") }
        assertTrue(retry().isSuccess)
        assertFalse(visible("a").single().deleted_for_all)
        val failed = db.messageDeletionDao().get("m", "a", true)!!
        assertEquals("failed", failed.state)
        assertEquals("denied", failed.error)
        enqueue(all = true).getOrThrow()
        assertNotEquals(failed.revision, db.messageDeletionDao().get("m", "a", true)!!.revision)
    }

    @Test fun `transient errors remain pending without a fixed attempt cap`() = runBlocking {
        seed(); enqueue().getOrThrow()
        response = { MessageDeleteResult.Retry }
        repeat(5) { assertTrue(retry().exceptionOrNull() is PendingSyncRetryException) }
        assertTrue(visible("a").isEmpty())
        assertEquals(1, db.messageDeletionDao().pending("a", 1).size)
    }

    @Test fun `cancellation propagates and preserves intent`() = runBlocking {
        seed(); enqueue().getOrThrow()
        response = { throw CancellationException("stop") }
        try { retry(); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
        assertEquals(1, db.messageDeletionDao().pending("a", 1).size)
    }

    @Test fun `membership removal prevents remote mutation`() = runBlocking {
        seed(); enqueue(all = true).getOrThrow()
        db.chatDao().insertChat(ChatEntity.fromDomain(Chat(id = "chat", participants = listOf("b", "c")), null, null, 0, null, null))
        retry().getOrThrow()
        assertEquals(0, requests)
        assertEquals("failed", db.messageDeletionDao().get("m", "a", true)!!.state)
    }

    @Test fun `two workers serialize one remote deletion`() = runBlocking {
        seed(); enqueue().getOrThrow()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        response = { started.complete(Unit); release.await(); MessageDeleteResult.Applied() }
        val first = async { retry() }
        withTimeout(10_000) { started.await() }
        val second = async { retry() }
        release.complete(Unit)
        first.await().getOrThrow(); second.await().getOrThrow()
        assertEquals(1, requests)
    }

    @Test fun `intent is persisted during in flight send and replay waits for its acknowledgement`() = runBlocking {
        seed(pending = true)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val send = async {
            coordinator.withMessage("m") {
                started.complete(Unit); release.await()
                db.messageDao().insertMessage(message().copy(status = "delivered", delivered_at = "now"))
            }
        }
        withTimeout(10_000) { started.await() }
        withTimeout(10_000) { enqueue(all = true).getOrThrow() }
        assertTrue(visible("b").single().deleted_for_all)
        assertEquals(1, db.messageDeletionDao().pending("a", 1).size)
        val delete = async { retry() }
        // Even though replay must wait, a process kill here would not lose the intent.
        assertFalse(send.isCompleted)
        assertEquals(0, requests)
        release.complete(Unit)
        send.await(); delete.await().getOrThrow()
        assertEquals("delivered", db.messageDao().getMessageById("m")!!.status)
        assertEquals(1, requests)
        assertTrue(visible("b").single().deleted_for_all)
    }

    @Test fun `pending deletion and message survive reopening actual database file`() = runBlocking {
        db.close()
        val path = java.io.File(files.root, "delete.db").absolutePath
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, path).allowMainThreadQueries().build()
        db = open()
        seed(); enqueue(actor = "b").getOrThrow()
        db.close(); db = open()
        assertEquals("hello", db.messageDao().getMessageById("m")!!.content)
        assertTrue(visible("b").isEmpty())
        assertEquals(1, retry("b").getOrThrow())
    }
}
