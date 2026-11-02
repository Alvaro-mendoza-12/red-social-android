package conectate.com.data.repository.message

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.local.entity.MessageDeletionEntity
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.model.Chat
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.model.Message
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
open class InboxPreviewReaderTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var db: AppDatabase
    private var account: String? = "a"
    private val reader get() = InboxPreviewReader(db, currentAccount = { account })
    private fun message(id: String = "new", second: Int = 2, chat: String = "chat", sender: String = "b") =
        MessageEntity.fromDomain(Message(
            id = id, chat_id = chat, sender_id = sender, content = "private-$id",
            created_at = "2026-09-09T00:00:${second.toString().padStart(2, '0')}Z",
        ))
    private suspend fun chat(id: String = "chat", participants: List<String> = listOf("a", "b"), last: String? = "new", cutoff: String? = null) {
        db.chatDao().insertChat(ChatEntity.fromDomain(
            Chat(id = id, participants = participants, last_message_id = last, last_message_sender_id = "b"),
            "Persona", "https://image.invalid/avatar", 2, "private-${last ?: "unknown"}",
            "2026-09-09T00:00:02Z", "b", clearedAt = cutoff,
        ))
    }
    private suspend fun seed() {
        chat()
        db.messageDao().insertMessages(listOf(message("old", 1), message()))
    }
    private suspend fun deletion(id: String = "new", actor: String = "a", all: Boolean = false, state: String = "pending", target: String = "chat") {
        db.messageDeletionDao().put(MessageDeletionEntity(
            message_id = id, actor_id = actor, for_everyone = all, chat_id = target, state = state,
            revision = java.util.UUID.randomUUID().toString(), created_at = System.currentTimeMillis(),
        ))
    }
    private suspend fun inbox() = reader.read(account!!)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun close() { db.close() }

    @Test fun `an empty local snapshot signals readiness after Room was read`() = runBlocking {
        var loaded: String? = null
        val result = withTimeout(5_000) {
            reader.observe(MutableStateFlow(account), onSnapshotLoaded = { loaded = it }).first()
        }
        assertTrue(result.isEmpty())
        assertEquals("a", loaded)
    }

    @Test fun `missing authentication never signals local readiness`() = runBlocking {
        account = null
        var loaded = false
        assertTrue(reader.observe(MutableStateFlow(account), onSnapshotLoaded = { loaded = true }).first().isEmpty())
        assertFalse(loaded)
    }

    @Test fun `normal inbox uses local latest and preserves cached identity`() = runBlocking {
        seed()
        val item = inbox().single()
        assertEquals("new", item.lastMessage!!.id)
        assertEquals("Persona", item.otherUserName)
        assertEquals("https://image.invalid/avatar", item.otherUserImage)
        assertEquals(2, item.unreadCount)
    }

    @Test fun `latest lookup returns newest row rather than insertion or id order`() = runBlocking {
        seed()
        db.messageDao().insertMessage(message("after", 3))
        assertEquals("after", db.messageDao().getLatestInboxCandidates(listOf("chat")).singleOrNull()?.id)
    }

    @Test fun `candidate seeks bind ids and use the chat timestamp index`() = runBlocking {
        seed()
        assertTrue(db.messageDao().getLatestInboxCandidates(listOf("chat' OR 1=1 --")).isEmpty())
        assertTrue(db.messageDao().getLatestInboxCandidates(emptyList()).isEmpty())
        assertEquals(1, db.messageDao().getLatestInboxCandidates(listOf("chat", "chat")).size)
        val plan = db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN SELECT * FROM messages WHERE chat_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            arrayOf("chat"),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString() }
        assertTrue(plan, plan.contains("index_messages_chat_id_created_at"))
    }

    @Test fun `delete for me selects previous visible row and clears raw chat fallback`() = runBlocking {
        seed()
        deletion()
        val item = inbox().single()
        assertEquals("old", item.lastMessage!!.id)
        assertEquals("private-old", item.chat.last_message)
        assertEquals("old", item.chat.last_message_id)
        assertEquals("2026-09-09T00:00:01Z", item.chat.last_message_at)
        assertEquals("private-new", db.messageDao().getMessageById("new")!!.content)
    }

    @Test fun `delete for me never hides the same conversation from other participant`() = runBlocking {
        seed()
        deletion()
        account = "b"
        assertEquals("new", inbox().single().lastMessage!!.id)
    }

    @Test fun `delete for everyone is a sanitized tombstone in both fields`() = runBlocking {
        seed()
        db.messageDao().insertMessage(message().copy(content_type = "video", media_url = "https://private.invalid/video", thumbnail_url = "https://private.invalid/thumb"))
        deletion(actor = "b", all = true)
        val item = inbox().single()
        assertTrue(item.lastMessage!!.deleted_for_all)
        assertEquals("", item.lastMessage!!.content)
        assertNull(item.lastMessage!!.media_url)
        assertNull(item.lastMessage!!.thumbnail_url)
        assertEquals("Mensaje eliminado", item.chat.last_message)
    }

    @Test fun `forged sender and foreign chat deletion do not hide a message`() = runBlocking {
        seed()
        deletion(actor = "a", all = true)
        deletion(actor = "a", target = "different")
        assertEquals("new", inbox().single().lastMessage!!.id)
        assertFalse(inbox().single().lastMessage!!.deleted_for_all)
    }

    @Test fun `server rejection restores latest preview`() = runBlocking {
        seed()
        deletion(state = "failed")
        assertEquals("new", inbox().single().lastMessage!!.id)
    }

    @Test fun `confirmed local intent defeats late history overwriting server flags`() = runBlocking {
        seed()
        deletion(state = "applied")
        db.messageDao().insertMessage(message())
        assertEquals("old", inbox().single().lastMessage!!.id)
    }

    @Test fun `remote deleted by list is normalized and respected without intent`() = runBlocking {
        seed()
        db.messageDao().insertMessage(message().copy(deleted_by_json = "[\" A \"]"))
        assertEquals("old", inbox().single().lastMessage!!.id)
    }

    @Test fun `invalid deleted by json cannot leak content through summary fallback`() = runBlocking {
        seed()
        db.messageDao().insertMessage(message().copy(deleted_by_json = "{broken"))
        assertEquals("old", inbox().single().lastMessage!!.id)
    }

    @Test fun `all rows hidden leaves empty preview without raw summary resurrection`() = runBlocking {
        seed()
        deletion()
        deletion("old")
        val item = inbox().single()
        assertNull(item.lastMessage)
        assertNull(item.chat.last_message)
        assertNull(item.chat.last_message_id)
        assertNull(item.chat.last_message_sender_id)
    }

    @Test fun `cleared chat excludes old and equal timestamps but admits new messages`() = runBlocking {
        seed()
        chat(cutoff = "2026-09-09T00:00:02Z")
        assertNull(inbox().single().lastMessage)
        db.messageDao().insertMessage(message("after", 3))
        assertEquals(listOf("old", "new", "after"), db.messageDao().getMessagesSync("chat").map { it.id })
        assertNotNull(db.chatDao().getChatById("chat"))
        assertEquals("after", db.messageDao().getLatestInboxCandidates(listOf("chat")).single().id)
        assertNotNull(visibleInboxMessage(message("after", 3).toDomain(), "chat", "2026-09-09T00:00:02Z", emptyList(), "a"))
        assertEquals("after", inbox().single().lastMessage?.id)
    }

    @Test fun `clear cutoff handles equivalent timezones and invalid dates safely`() = runBlocking {
        seed()
        chat(cutoff = "2026-09-08T19:00:02-05:00")
        assertNull(inbox().single().lastMessage)
        chat(cutoff = "invalid")
        assertNull(inbox().single().lastMessage)
    }

    @Test fun `new messages remain visible after clearing with a legacy PostgreSQL offset`() = runBlocking {
        seed()
        for (cutoff in listOf("2026-09-09 00:00:02+00", "2026-09-08T19:00:02-0500")) {
            chat(cutoff = cutoff)
            assertNull(inbox().single().lastMessage)
            db.messageDao().insertMessage(message("after", 3))
            assertEquals("after", inbox().single().lastMessage?.id)
            db.messageDao().deleteMessage("after")
        }
    }

    @Test fun `newer summary with a short timezone offset wins over older history`() = runBlocking {
        chat(last = "server")
        val oldChat = db.chatDao().getChatById("chat")!!
        db.chatDao().updateChat(oldChat.copy(last_message_timestamp = "2026-09-08 19:00:03-05"))
        db.messageDao().insertMessage(message("local", 2))
        assertEquals("server", inbox().single().lastMessage?.id)
    }

    @Test fun `chat summary works before history download but known intent still hides it`() = runBlocking {
        chat()
        assertEquals("new", inbox().single().lastMessage!!.id)
        deletion()
        assertNull(inbox().single().lastMessage)
    }

    @Test fun `anonymous stale summary cannot bypass an intent`() = runBlocking {
        chat(last = null)
        deletion()
        assertNull(inbox().single().lastMessage)
    }

    @Test fun `anonymous summary cannot resurrect remote deleted message without local intent`() = runBlocking {
        chat(last = null)
        db.messageDao().insertMessage(message("a").copy(deleted_for_all = true))
        assertTrue(inbox().single().lastMessage!!.deleted_for_all)
        assertEquals("Mensaje eliminado", inbox().single().chat.last_message)
    }

    @Test fun `anonymous summary cannot bypass remote delete for me or corrupt deletion json`() = runBlocking {
        chat(last = null)
        db.messageDao().insertMessages(listOf(message("old", 1), message().copy(deleted_by_json = "[\"a\"]")))
        assertEquals("old", inbox().single().lastMessage!!.id)
        db.messageDao().insertMessage(message().copy(deleted_by_json = "broken"))
        assertEquals("old", inbox().single().lastMessage!!.id)
    }

    @Test fun `same timestamp metadata cannot replace full media row`() = runBlocking {
        chat(last = null)
        db.messageDao().insertMessage(message("a").copy(content_type = "video"))
        assertEquals("video", inbox().single().lastMessage!!.content_type)
        assertEquals("a", inbox().single().lastMessage!!.id)
    }

    @Test fun `newer server summary and newer local pending send are both supported`() = runBlocking {
        chat(last = "server")
        db.messageDao().insertMessage(message("old", 1))
        assertEquals("server", inbox().single().lastMessage!!.id)
        db.messageDao().insertMessage(message("pending", 3).copy(status = "sending", is_synced = false, pending_action = "send"))
        assertEquals("pending", inbox().single().lastMessage!!.id)
    }

    @Test fun `hidden messages are paged without arbitrary visibility cap`() = runBlocking {
        chat(last = "m90")
        val start = java.time.Instant.parse("2026-09-09T00:00:00Z")
        val rows = (0..90).map { n -> message("m$n").copy(
            created_at = start.plusSeconds(n.toLong()).toString(),
            deleted_by_json = if (n == 0) "[]" else "[\"a\"]",
        ) }
        db.messageDao().insertMessages(rows)
        assertEquals(91, db.messageDao().getMessagesSync("chat").size)
        assertNotNull(db.chatDao().getChatById("chat"))
        assertEquals("m90", db.messageDao().getLatestInboxCandidates(listOf("chat")).single().id)
        val oldest = db.messageDao().getOlderInboxCandidates("chat", start.plusSeconds(27).toString(), "m27", 32).last()
        assertEquals("m0", oldest.id)
        assertNotNull(visibleInboxMessage(oldest.toSafeInboxMessage(), "chat", null, emptyList(), "a"))
        assertEquals("m0", inbox().single().lastMessage?.id)
    }

    @Test fun `deterministic cursor visits equal timestamps`() = runBlocking {
        chat(last = "z")
        db.messageDao().insertMessages(listOf(message("z").copy(deleted_by_json = "[\"a\"]"), message("y")))
        assertEquals("y", inbox().single().lastMessage!!.id)
    }

    @Test fun `only member chats are read and SQL variable batches support large inbox`() = runBlocking {
        db.withTransaction {
            repeat(1002) { n -> chat(id = "c$n", last = "m$n"); db.messageDao().insertMessage(message("m$n", chat = "c$n")) }
            chat(id = "foreign", participants = listOf("x", "y"))
            db.messageDao().insertMessage(message(chat = "foreign"))
        }
        val items = inbox()
        assertEquals(1002, items.size)
        assertFalse(items.any { it.chat.id == "foreign" })
    }

    @Test fun `membership withdrawal and logout never return prior account content`() = runBlocking {
        seed()
        chat(participants = listOf("b", "c"))
        assertTrue(inbox().isEmpty())
        account = null
        try { reader.read("a"); fail("must cancel") } catch (_: CancellationException) { }
    }

    @Test fun `deleted preview remains hidden after database file reopens`() = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(files.root, "inbox.db").absolutePath
        db = Room.databaseBuilder(context, AppDatabase::class.java, file).allowMainThreadQueries().build()
        seed()
        deletion()
        db.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, file).allowMainThreadQueries().build()
        assertEquals("old", inbox().single().lastMessage!!.id)
        assertEquals(2, db.messageDao().getMessagesSync("chat").size)
    }

    @Test fun `observer reacts to deletion alone and account change without chat metadata write`() = runBlocking {
        seed()
        val accounts = MutableStateFlow<String?>("a")
        val values = Channel<List<conectate.com.data.model.ChatWithLastMessage>>(Channel.UNLIMITED)
        val observer = launch { reader.observe(accounts).collect { values.send(it) } }
        suspend fun awaitId(id: String) = withTimeout(10_000) {
            while (true) {
                val value = values.receive()
                if (value.singleOrNull()?.lastMessage?.id == id) break
            }
        }
        try {
            awaitId("new")
            deletion()
            awaitId("old")
            account = "b"
            accounts.value = "b"
            awaitId("new")
            account = null
            accounts.value = null
            withTimeout(10_000) { while (values.receive().isNotEmpty()) { } }
        } finally { observer.cancelAndJoin(); values.close() }
    }

    @Test fun `reopening an already populated inbox never shows a transient empty list`() = runBlocking {
        seed()
        val first = withTimeout(10_000) { reader.observe(MutableStateFlow(account)).first() }
        assertEquals("new", first.single().lastMessage?.id)
        val reopened = withTimeout(10_000) { reader.observe(MutableStateFlow(account)).first() }
        assertEquals("new", reopened.single().lastMessage?.id)
    }
    @Test fun `notification mute updates the actual Room projection without modifying a chat row`() = runBlocking {
        seed()
        val changes=MutableStateFlow(0L)
        val muted=mutableSetOf<Pair<String,String>>()
        val projection=InboxPreviewReader(db, { account }, preferenceChanges=changes,
            isChatMuted={ owner, id, remote -> remote || (owner to id) in muted })
        val values=Channel<List<conectate.com.data.model.ChatWithLastMessage>>(Channel.UNLIMITED)
        val observer=launch { projection.observe(MutableStateFlow(account)).collect { values.send(it) } }
        suspend fun awaitMute(expected: Boolean) = withTimeout(10000) {
            while(values.receive().singleOrNull()?.chat?.is_muted != expected) { }
        }
        try {
            awaitMute(false)
            muted.add("a" to "chat");changes.value=1L;awaitMute(true)
            assertFalse(db.chatDao().getChatByIdSync("chat")!!.is_muted)
            muted.clear();changes.value=2L;awaitMute(false)
        } finally { observer.cancelAndJoin();values.close() }
    }
    @Test fun `timed notification mute does not cross accounts or override a server mute`() = runBlocking {
        seed()
        val projection=InboxPreviewReader(db,{account},isChatMuted={owner,_,remote -> remote || owner=="a"})
        assertTrue(projection.read("a").single().chat.is_muted)
        account="b";assertFalse(projection.read("b").single().chat.is_muted)
        db.chatDao().updateMuteState("chat",true)
        assertTrue(projection.read("b").single().chat.is_muted)
    }

    @Test fun `startup inbox factory observes real notification preferences and manual reactivation`() = runBlocking {
        seed()
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("conversation_notification_prefs",Context.MODE_PRIVATE).edit().clear().commit()
        val preferences=conectate.com.util.ConversationNotificationPrefs(context)
        val projection=conectate.com.data.repository.createLocalInboxReader(db,{account},context)
        val values=Channel<List<ChatWithLastMessage>>(Channel.UNLIMITED)
        val observer=launch { projection.observe(MutableStateFlow(account)).collect { values.send(it) } }
        suspend fun awaitMute(expected:Boolean)=withTimeout(10000) {
            while(values.receive().singleOrNull()?.chat?.is_muted != expected) { }
        }
        try {
            awaitMute(false)
            preferences.muteConversationUntil("chat","chat",System.currentTimeMillis()+60000,"a")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            awaitMute(true)
            assertFalse(db.chatDao().getChatByIdSync("chat")!!.is_muted)
            preferences.unmuteConversation("chat","chat","a")
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            awaitMute(false)
        } finally { observer.cancelAndJoin();values.close();preferences.unmuteConversation("chat","chat","a") }
    }
    @Test fun `startup inbox factory keeps mute scoped to the account and preserves server preferences`() = runBlocking {
        seed()
        val context=ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("conversation_notification_prefs",Context.MODE_PRIVATE).edit().clear().commit()
        val preferences=conectate.com.util.ConversationNotificationPrefs(context)
        val projection=conectate.com.data.repository.createLocalInboxReader(db,{account},context)
        try {
            preferences.muteConversationUntil("chat","chat",System.currentTimeMillis()+60000,"a")
            assertTrue(projection.read("a").single().chat.is_muted)
            account="b";assertFalse(projection.read("b").single().chat.is_muted)
            db.chatDao().updateMuteState("chat",true)
            assertTrue(projection.read("b").single().chat.is_muted)
        } finally { preferences.unmuteConversation("chat","chat","a") }
    }

}
