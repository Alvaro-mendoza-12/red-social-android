package conectate.com.data.repository.message

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import conectate.com.data.auth.AccountLocalStorageGate
import conectate.com.data.local.AppDatabase
import conectate.com.data.localfallback.UbuntuRealtimeClient
import conectate.com.data.localfallback.RecordingRealtimeSocket
import conectate.com.data.model.Message
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real listener -> per-visit queue -> guarded Room transaction, with controlled barriers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class MessageRealtimeLifecycleTest {
    private lateinit var db: AppDatabase
    private lateinit var sync: MessageRealtimeSync
    private lateinit var transport: UbuntuRealtimeClient
    private lateinit var guard: AuthSessionMutationGuard
    private lateinit var activation: Mutex
    private lateinit var enteredStorage: CompletableDeferred<Unit>
    private lateinit var opened: CompletableDeferred<Pair<WebSocket, WebSocketListener>>
    private var account: String? = "a"
    private var token = "fixture-token"

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        guard = AuthSessionMutationGuard(); activation = Mutex()
        val storage = AccountLocalStorageGate(activation, guard)
        enteredStorage = CompletableDeferred(); opened = CompletableDeferred()
        val access = object : RealtimeAccountAccess {
            override fun capture() = guard.captureAccountLease { account }
            override fun token() = token
            override fun commit(owner: AuthSessionLease, mutation: () -> Unit) = guard.commitForLease(owner, mutation)
            override suspend fun <T> withStorage(owner: AuthSessionLease, write: suspend () -> T): T {
                enteredStorage.complete(Unit)
                return storage.withOwner(owner, write)
            }
        }
        transport = UbuntuRealtimeClient({guard.captureAccountLease { account }}, {token}, guard::commitForLease) { _, listener ->
            RecordingRealtimeSocket().also { opened.complete(it to listener) }
        }
        sync = MessageRealtimeSync(null, access, transport, db.messageDao(), db, db.chatDao(),
            { throw AssertionError("Unexpected receive failure", it) }, { _, _ -> }, context, usesUbuntu = true)
    }

    @After fun close() { sync.clearAll(); db.close() }

    private fun processor(): Job {
        val session = sync.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.get(sync)
        return session.javaClass.getDeclaredField("processor").apply { isAccessible = true }.get(session) as Job
    }

    private fun listener(): Job {
        val session = sync.javaClass.getDeclaredField("activeSession").apply { isAccessible = true }.get(sync)
        return session.javaClass.getDeclaredField("listener").apply { isAccessible = true }.get(session) as Job
    }

    private suspend fun send(id: String) {
        val (socket, listener) = withTimeout(5_000) { opened.await() }
        val message = Message(id = id, chat_id = "chat", sender_id = "peer", content = "fixture", created_at = "2026-11-12T00:00:00Z")
        listener.onMessage(socket, buildJsonObject {
            put("type", "event"); put("channel", "user:a")
            put("payload", buildJsonObject {
                put("event", "message"); put("chatId", "chat"); put("action", "inserted")
                put("message", Json.encodeToJsonElement(Message.serializer(), message))
            })
        }.toString())
        withTimeout(5_000) { enteredStorage.await() }
    }

    @Test fun repeatedConsumersAndSessionRefreshPreserveTheMessageAlreadyWaitingForStorage() = runBlocking {
        activation.lock(); sync.start("a")
        val received = async(start = CoroutineStart.UNDISPATCHED) { sync.messageEvents.first { it.id == "preserved" } }
        try {
            send("preserved")
            val original = processor()
            token = "fixture-rotated-token"
            sync.stopIfAccountChanged(); sync.start("a")
            repeat(8) { transport.replaceUserSubscription("a") }
            assertSame(original, processor())
            activation.unlock()
            assertEquals("preserved", withTimeout(5_000) { received.await() }.id)
            assertNotNull(db.messageDao().getMessageById("preserved"))
        } finally { if (activation.isLocked) activation.unlock(); received.cancelAndJoin() }
    }

    @Test fun anOldQueuedMessageCannotWriteAfterAnUnobservedAbaTransition() = runBlocking {
        activation.lock(); sync.start("a")
        try {
            send("revoked")
            val original = processor()
            guard.invalidate { account = "b" }; guard.invalidate { account = "a" }
            transport.replaceUserSubscription("a")
            activation.unlock()
            withTimeout(5_000) { original.join() }
            assertNull(db.messageDao().getMessageById("revoked"))
        } finally { if (activation.isLocked) activation.unlock() }
    }

    @Test fun aListenerThatStartedWithoutCredentialsCanResumeWithoutReplacingItsQueue() = runBlocking {
        token = ""; sync.start("a")
        withTimeout(5_000) { listener().join() }
        val original = processor()
        token = "fixture-ready-token"
        sync.start("a")
        val received = async(start = CoroutineStart.UNDISPATCHED) { sync.messageEvents.first { it.id == "after-token" } }
        try {
            send("after-token")
            assertSame(original, processor())
            assertEquals("after-token", withTimeout(5_000) { received.await() }.id)
            assertNotNull(db.messageDao().getMessageById("after-token"))
        } finally { received.cancelAndJoin() }
    }
}
