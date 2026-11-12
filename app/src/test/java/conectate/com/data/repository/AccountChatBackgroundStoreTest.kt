package conectate.com.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class AccountChatBackgroundStoreTest {
    @Test fun keysAndFilesAreAccountScopedAndCannotCollideBySanitizingPunctuation() {
        val first = requireNotNull(accountChatBackgroundKey("a", "group:room"))
        val second = requireNotNull(accountChatBackgroundKey("b", "group:room"))
        assertNotEquals(first, second)
        assertNotEquals(chatBackgroundFileName(first), chatBackgroundFileName(second))
        assertNotEquals(chatBackgroundFileName(first), chatBackgroundFileName(requireNotNull(accountChatBackgroundKey("a", "group_room"))))
        assertEquals(first, accountChatBackgroundKey(" A ", " GROUP:ROOM "))
        assertNotEquals(accountChatBackgroundKey("a:b", "c"), accountChatBackgroundKey("a", "b:c"))
        assertNull(accountChatBackgroundKey(null, "room"))
        assertNull(accountChatBackgroundKey("a", " "))
    }

    @Test fun imageFinishingAfterAccountClearCannotRepopulateTheNewAccountsBackground() = runBlocking {
        val owner = MutableStateFlow<String?>("a")
        val rows = mutableMapOf<String, ChatBackgroundSettings>()
        val processing = CompletableDeferred<String>()
        val resume = CompletableDeferred<String>()
        val store = AccountChatBackgroundStore({ owner.value }, owner,
            { MutableStateFlow(rows[it] ?: ChatBackgroundSettings()) },
            { key, value -> rows[key] = value }, { rows.remove(it) },
            { key, _ -> processing.complete(key); resume.await() })
        val pending = launch { store.set("a", "group:room", "custom", "content://fixture") }
        val oldKey = processing.await()
        rows.clear() // Existing account-switch cache cleanup.
        owner.value = "b"
        val newKey = requireNotNull(accountChatBackgroundKey("b", "group:room"))
        rows[newKey] = ChatBackgroundSettings("custom", "new-account.jpg")
        assertNotEquals(chatBackgroundFileName(oldKey), chatBackgroundFileName(newKey))
        resume.complete("old-account.jpg")
        pending.join()
        assertEquals(mapOf(newKey to ChatBackgroundSettings("custom", "new-account.jpg")), rows)
    }

    @Test fun staleQueuedSetAndClearCannotMutateAnotherAccount() = runBlocking {
        val owner = MutableStateFlow<String?>("b")
        var writes = 0
        val store = AccountChatBackgroundStore({ owner.value }, owner,
            { MutableStateFlow(ChatBackgroundSettings()) }, { _, _ -> writes++ }, { writes++ },
            { _, _ -> fail("must not process a stale upload"); null })
        store.set("a", "room", "custom", "content://fixture")
        store.remove("a", "room")
        assertEquals(0, writes)
    }

    @Test fun activeAccountWritesOnlyItsKeyAndNeverAdoptsOwnerlessRows() = runBlocking {
        val owner = MutableStateFlow<String?>("a")
        val rows = mutableMapOf("room" to ChatBackgroundSettings("custom", "legacy.jpg"))
        val store = AccountChatBackgroundStore({ owner.value }, owner,
            { MutableStateFlow(rows[it] ?: ChatBackgroundSettings()) },
            { key, value -> rows[key] = value }, { rows.remove(it) }, { _, uri -> uri })
        store.set("a", "room", "custom", "new.jpg")
        val key = requireNotNull(accountChatBackgroundKey("a", "room"))
        assertEquals("new.jpg", rows[key]?.backgroundUri)
        store.remove("a", "room")
        assertEquals(mapOf("room" to ChatBackgroundSettings("custom", "legacy.jpg")), rows)
    }

    @Test fun observationClearsOldImageAndSwitchesOwnerIncludingSignOut() = runBlocking {
        val owner = MutableStateFlow<String?>("a")
        val a = MutableStateFlow(ChatBackgroundSettings("custom", "a.jpg"))
        val b = MutableStateFlow(ChatBackgroundSettings("custom", "b.jpg"))
        val requested = mutableListOf<String>()
        val store = AccountChatBackgroundStore({ owner.value }, owner,
            { key -> requested += key; if (key == accountChatBackgroundKey("a", "room")) a else b },
            { _, _ -> }, {}, { _, uri -> uri })
        val output = Channel<ChatBackgroundSettings>(Channel.UNLIMITED)
        val job = launch { store.observe("room").collect { output.send(it) } }
        suspend fun next() = withTimeout(2000) { output.receive() }
        assertEquals(ChatBackgroundSettings(), next())
        assertEquals("a.jpg", next().backgroundUri)
        owner.value = "b"
        assertEquals(ChatBackgroundSettings(), next())
        assertEquals("b.jpg", next().backgroundUri)
        a.value = ChatBackgroundSettings("custom", "late-a.jpg")
        assertNull(withTimeoutOrNull(50) { output.receive() })
        owner.value = null
        assertEquals(ChatBackgroundSettings(), next())
        assertEquals(listOf(accountChatBackgroundKey("a", "room"), accountChatBackgroundKey("b", "room")), requested)
        job.cancel()
    }
    @Test fun deliveryChecksCurrentOwnerEvenBeforeTheSessionFlowCatchesUp() = runBlocking {
        var active: String? = "a"
        val notifications = MutableStateFlow<String?>("a")
        val oldRows = MutableStateFlow(ChatBackgroundSettings("custom", "a.jpg"))
        val store = AccountChatBackgroundStore({ active }, notifications, { oldRows }, { _, _ -> }, {}, { _, uri -> uri })
        val output = Channel<ChatBackgroundSettings>(Channel.UNLIMITED)
        val job = launch { store.observe("room").collect { output.send(it) } }
        withTimeout(2000) { output.receive(); output.receive() }
        active = "b"
        oldRows.value = ChatBackgroundSettings("custom", "late-a.jpg")
        assertEquals(ChatBackgroundSettings(), withTimeout(2000) { output.receive() })
        job.cancel()
    }
}
