package conectate.com.data.repository.message

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.auth.AccountLocalStorageGate
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.model.Message
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Barrier tests for the exact transaction boundary used by realtime ingestion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class MessageRealtimeAccountTransactionTest {
    private lateinit var database: AppDatabase
    private lateinit var sessions: AuthSessionMutationGuard
    private lateinit var activation: Mutex
    private lateinit var storage: AccountLocalStorageGate
    private var account: String? = "a"
    private val message = MessageEntity.fromDomain(Message("message", "chat", "peer", "text", status = "sent"))

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        sessions = AuthSessionMutationGuard()
        activation = Mutex()
        storage = AccountLocalStorageGate(activation, sessions)
        account = "a"
    }

    @After fun close() { database.close() }

    @Test fun ordinaryMessageTransactionCommits() = runBlocking {
        val owner = sessions.captureAccountLease { account }
        storage.withOwner(owner) {
            withRealtimeAccountTransaction(database, owner::requireCurrent) { database.messageDao().insertMessage(message) }
        }
        assertNotNull(database.messageDao().getMessageById(message.id))
    }

    @Test fun revokedWhileWaitingForStorageNeverWritesEvenAfterReturningToSameAccount() = runBlocking {
        val owner = sessions.captureAccountLease { account }
        activation.lock()
        val started = CompletableDeferred<Unit>()
        val writing = async(Dispatchers.IO) {
            started.complete(Unit)
            runCatching { storage.withOwner(owner) {
                withRealtimeAccountTransaction(database, owner::requireCurrent) { database.messageDao().insertMessage(message) }
            } }
        }
        withTimeout(5_000) { started.await() }
        sessions.invalidate { account = "b" }
        sessions.invalidate { account = "a" }
        activation.unlock()
        assertTrue(withTimeout(5_000) { writing.await() }.exceptionOrNull() is CancellationException)
        assertNull(database.messageDao().getMessageById(message.id))
    }

    @Test fun revocationBetweenReadAndInsertRollsBackTheWholeTransaction() = runBlocking {
        val owner = sessions.captureAccountLease { account }
        val read = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writing = async(Dispatchers.IO) {
            runCatching { storage.withOwner(owner) {
                withRealtimeAccountTransaction(database, owner::requireCurrent) {
                    database.messageDao().getMessageById(message.id)
                    read.complete(Unit)
                    release.await()
                    database.messageDao().insertMessage(message)
                }
            } }
        }
        try {
            withTimeout(5_000) { read.await() }
            sessions.invalidate { account = "b" }
            release.complete(Unit)
            assertTrue(withTimeout(5_000) { writing.await() }.exceptionOrNull() is CancellationException)
            assertNull(database.messageDao().getMessageById(message.id))
        } finally { release.complete(Unit); writing.cancelAndJoin() }
    }

    @Test fun cancellationAfterInsertRollsBackBeforeAccountCacheCanBeReplaced() = runBlocking {
        val owner = sessions.captureAccountLease { account }
        val inserted = CompletableDeferred<Unit>()
        val writing = launch(Dispatchers.IO) {
            storage.withOwner(owner) {
                withRealtimeAccountTransaction(database, owner::requireCurrent) {
                    database.messageDao().insertMessage(message)
                    inserted.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        try {
            withTimeout(5_000) { inserted.await() }
            writing.cancelAndJoin()
            assertFalse(activation.isLocked)
            assertNull(database.messageDao().getMessageById(message.id))
        } finally { writing.cancelAndJoin() }
    }
}
