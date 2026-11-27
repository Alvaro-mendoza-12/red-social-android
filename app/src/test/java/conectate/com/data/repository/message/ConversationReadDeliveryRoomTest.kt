package conectate.com.data.repository.message

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.auth.AccountLocalStorageGate
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.dao.MessageDao
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.local.entity.CommunityEntity
import conectate.com.data.local.entity.CommunityMessageEntity
import conectate.com.data.local.entity.GroupEntity
import conectate.com.data.local.entity.GroupMessageEntity
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.Message
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import conectate.com.util.ConversationReadEvent
import conectate.com.util.ConversationReadStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
open class ConversationReadDeliveryRoomTest {
    private val a = "10000000-0000-4000-8000-000000000001"
    private val b = "10000000-0000-4000-8000-000000000002"
    private val peer = "10000000-0000-4000-8000-000000000003"
    private val room = "20000000-0000-4000-8000-000000000001"
    private val first = "30000000-0000-4000-8000-000000000001"
    private val second = "30000000-0000-4000-8000-000000000002"
    private val outgoing = "30000000-0000-4000-8000-000000000003"
    private val time = "2026-11-03T12:00:00Z"
    private val later = "2026-11-03T12:01:00Z"
    private lateinit var db: AppDatabase
    private lateinit var journal: ConversationReadStore
    private lateinit var sessions: AuthSessionMutationGuard
    private lateinit var activation: Mutex
    private lateinit var gate: AccountLocalStorageGate
    private var account: String? = null
    private val alerts = CopyOnWriteArrayList<ConversationReadEvent>()

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("conversation_read_sync_v1", Context.MODE_PRIVATE).edit().clear().commit()
        journal = ConversationReadStore(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        sessions = AuthSessionMutationGuard()
        activation = Mutex()
        gate = AccountLocalStorageGate(activation, sessions)
        account = a
        seed(a)
    }

    @After fun close() { db.close() }

    private suspend fun seed(owner: String) {
        db.chatDao().insertChat(ChatEntity(room, null, false, peer, "[\"$owner\",\"$peer\"]", time, "Chat", null, unread_count = 2))
        db.groupDao().upsertGroup(GroupEntity(room, "Group", null, null, time, owner, "Preview", time, unread_count = 2))
        db.communityDao().insertCommunities(listOf(CommunityEntity(room, "Community", null, null, null, false, time, owner, "Preview", time, unread_count = 2)))
        for ((id, sender, at) in listOf(Triple(first, peer, time), Triple(second, peer, later), Triple(outgoing, owner, time))) {
            db.messageDao().insertMessage(MessageEntity.fromDomain(Message(id, room, sender, "Text", created_at = at, status = "sent")))
            db.groupDao().insertMessages(listOf(GroupMessageEntity.fromDomain(GroupMessage(id, room, sender, "Text", created_at = at, status = "sent"))))
            db.communityDao().insertMessages(listOf(CommunityMessageEntity.fromDomain(CommunityMessage(id = id, community_id = room, sender_id = sender, content = "Text", created_at = at, status = "sent"))))
        }
    }

    private fun event(kind: String = "chat", ids: Set<String> = setOf(first), through: String? = null) =
        ConversationReadEvent(a, kind, room, ids, through)
    private fun owner() = sessions.captureAccountLease { account }
    private fun delivery(messages: MessageDao = db.messageDao()) = ConversationReadDelivery(
        remember = journal::remember,
        clearAlerts = { alerts += it },
        local = ConversationReadLocalStore(db, messages = messages) { lease, write -> gate.withOwner(lease, write) },
    )

    private suspend fun status(kind: String, id: String): String? = when (kind) {
        "chat" -> db.messageDao().getMessageById(id)?.status
        "group" -> db.groupDao().getMessageById(id)?.status
        else -> db.communityDao().getMessageById(id)?.status
    }

    private suspend fun unread(kind: String): Int? = when (kind) {
        "chat" -> db.chatDao().getChatById(room)?.unread_count
        "group" -> db.groupDao().getGroupById(room)?.unread_count
        else -> db.communityDao().getCommunityById(room)?.unread_count
    }

    @Test fun visibleSelectionUsesOnlyRequestedIncomingUnreadRowsInTheConversation() = runBlocking {
        val otherRoom = UUID.randomUUID().toString()
        val outside = UUID.randomUUID().toString()
        db.chatDao().insertChat(ChatEntity(otherRoom, null, false, peer, "[]", time, "Other", null))
        db.groupDao().upsertGroup(GroupEntity(otherRoom, "Other", null, null, time, a, null, null))
        db.communityDao().insertCommunities(listOf(CommunityEntity(otherRoom, "Other", null, null, null, false, time, a, null, null)))
        db.messageDao().insertMessage(MessageEntity.fromDomain(Message(outside, otherRoom, peer, "Text", created_at = time, status = "sent")))
        db.groupDao().insertMessages(listOf(GroupMessageEntity.fromDomain(GroupMessage(outside, otherRoom, peer, "Text", created_at = time, status = "sent"))))
        db.communityDao().insertMessages(listOf(CommunityMessageEntity.fromDomain(CommunityMessage(id = outside, community_id = otherRoom, sender_id = peer, content = "Text", created_at = time, status = "sent"))))
        // A preserved direct read timestamp wins over that row's older delivery status.
        db.messageDao().insertMessage(db.messageDao().getMessageById(first)!!.copy(read_at = time, status = "sent"))
        db.groupDao().markMessagesAsReadByIds(room, time, a, listOf(first))
        db.communityDao().markMessagesAsReadByIds(room, time, a, listOf(first))

        val visible = listOf(first, second, outgoing, outside)
        assertEquals(setOf(second), selectVisibleIncomingReadIds(visible, room, a, db.messageDao()::getVisibleReadCandidates))
        assertEquals(setOf(second), selectVisibleIncomingReadIds(visible, room, a, db.groupDao()::getVisibleReadCandidates))
        assertEquals(setOf(second), selectVisibleIncomingReadIds(visible, room, a, db.communityDao()::getVisibleReadCandidates))
    }

    @Test fun visibleSelectionChunksABatchLargerThanTheLegacySqliteBindLimit() = runBlocking {
        val visible = listOf(first) + List(1_000) { UUID.randomUUID().toString() } + listOf(second, outgoing)
        val querySizes = mutableListOf<Int>()
        val ids = selectVisibleIncomingReadIds(visible, room, a) { batch ->
            querySizes.add(batch.size)
            db.messageDao().getVisibleReadCandidates(batch)
        }

        assertEquals(setOf(first, second), ids)
        assertEquals(listOf(200, 200, 200, 200, 200, 3), querySizes)
    }

    @Test fun aFailedVisibleReadBatchKeepsCollectionMessagesAndUnreadCountConsistent() = runBlocking {
        val batches = listOf(listOf(first), listOf(second))
        val updates = mapOf<String, suspend () -> Unit>(
            "group" to { db.groupDao().applyVisibleReads(room, later, a, batches, 2) },
            "community" to { db.communityDao().applyVisibleReads(room, later, a, batches, 2) },
        )
        for ((kind, applyReads) in updates) {
            val sqlite = db.openHelper.writableDatabase
            sqlite.execSQL("""
                CREATE TRIGGER fail_visible_read BEFORE UPDATE OF status ON ${kind}_messages
                WHEN NEW.id = '$second' BEGIN SELECT RAISE(ABORT, 'injected batch failure'); END
            """.trimIndent())
            try {
                val failure = runCatching { applyReads() }.exceptionOrNull()
                assertTrue(failure is SQLiteException && failure.message.orEmpty().contains("injected batch failure"))
                assertEquals("sent", status(kind, first))
                assertEquals("sent", status(kind, second))
                assertEquals(2, unread(kind))
            } finally {
                sqlite.execSQL("DROP TRIGGER fail_visible_read")
            }
            applyReads()
            assertEquals("read", status(kind, first))
            assertEquals("read", status(kind, second))
            assertEquals(0, unread(kind))
        }
    }

    @Test fun confirmedIdsAffectOnlyIncomingRowsAndReconcileAllThreeConversationKinds() = runBlocking {
        for (kind in listOf("chat", "group", "community")) {
            delivery().apply(event(kind, setOf(first, outgoing)), owner())
            assertEquals("read", status(kind, first))
            assertEquals("sent", status(kind, second))
            assertEquals("sent", status(kind, outgoing))
            assertEquals(1, unread(kind))
            assertTrue(journal.isRead(a, kind, room, first, time))
        }
        assertEquals(3, alerts.size)
    }

    @Test fun readThroughKeepsLaterRowsUnreadAndCanFinishWithAConfirmedLaterCursor() = runBlocking {
        for (kind in listOf("chat", "group", "community")) {
            delivery().apply(event(kind, emptySet(), "2026-11-03T12:00:30Z"), owner())
            assertEquals("read", status(kind, first))
            assertEquals("sent", status(kind, second))
            assertEquals(1, unread(kind))
            delivery().apply(event(kind, emptySet(), "2026-11-03T12:02:00Z"), owner())
            assertEquals("read", status(kind, second))
            assertEquals("sent", status(kind, outgoing))
            assertEquals(0, unread(kind))
        }
    }

    @Test fun anInactiveAccountKeepsItsJournalAndAlertCleanupWithoutTouchingTheActiveCache() = runBlocking {
        sessions.invalidate { account = b }
        db.clearAllTables()
        seed(b)
        delivery().apply(event(), owner())
        assertEquals("sent", status("chat", first))
        assertEquals(2, unread("chat"))
        assertTrue(journal.isRead(a, "chat", room, first, time))
        assertFalse(journal.isRead(b, "chat", room, first, time))
        assertEquals(listOf(a), alerts.map { it.accountId })
    }

    @Test fun aControlCanStillBeRememberedWhenThereIsNoActiveSession() = runBlocking {
        sessions.invalidate { account = null }
        delivery().apply(event(), owner())
        assertTrue(journal.isRead(a, "chat", room, first, time))
        assertEquals("sent", status("chat", first))
        assertEquals(listOf(a), alerts.map { it.accountId })
    }

    @Test fun returningToTheSameAccountDoesNotAuthorizeAReceiptCapturedBeforeTheSwitch() = runBlocking {
        val old = owner()
        sessions.invalidate { account = b }
        sessions.invalidate { account = a }
        delivery().apply(event(), old)
        assertEquals("sent", status("chat", first))
        assertTrue(journal.isRead(a, "chat", room, first, time))
        delivery().apply(event(), owner())
        assertEquals("read", status("chat", first))
    }

    private fun pauseAfterRead(written: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>): MessageDao =
        object : MessageDao by db.messageDao() {
            override suspend fun markMessagesAsReadByIds(chatId: String, currentUserId: String, now: String, messageIds: List<String>) {
                db.messageDao().markMessagesAsReadByIds(chatId, currentUserId, now, messageIds)
                written.complete(Unit)
                release.await()
            }
        }

    @Test fun accountActivationWaitsForTheReadTransactionBeforeReplacingTheSharedCache() = runBlocking {
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requested = CompletableDeferred<Unit>()
        val switched = CompletableDeferred<Unit>()
        val reading = async(Dispatchers.IO) { delivery(pauseAfterRead(written, release)).apply(event(), owner()) }
        try {
            withTimeout(5_000) { written.await() }
            val switching = async(Dispatchers.IO) {
                requested.complete(Unit)
                activation.withLock {
                    sessions.invalidate { account = b }
                    db.clearAllTables()
                    seed(b)
                    switched.complete(Unit)
                }
            }
            withTimeout(5_000) { requested.await() }
            assertNull(withTimeoutOrNull(100) { switched.await() })
            assertEquals(a, sessions.readCurrent { account })
            release.complete(Unit)
            withTimeout(5_000) { reading.await(); switching.await() }
            assertEquals(b, sessions.readCurrent { account })
            assertEquals("sent", status("chat", first))
            assertEquals(2, unread("chat"))
        } finally { release.complete(Unit) }
    }

    @Test fun forcedLogoutRollsBackReadRowsAndTheCounterButKeepsTheAccountScopedControl() = runBlocking {
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reading = async(Dispatchers.IO) { runCatching { delivery(pauseAfterRead(written, release)).apply(event(), owner()) } }
        try {
            withTimeout(5_000) { written.await() }
            sessions.invalidate { account = null }
            release.complete(Unit)
            val failure = withTimeout(5_000) { reading.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals("sent", status("chat", first))
            assertEquals(2, unread("chat"))
            assertTrue(journal.isRead(a, "chat", room, first, time))
            assertEquals(listOf(a), alerts.map { it.accountId })
        } finally { release.complete(Unit) }
    }
}
