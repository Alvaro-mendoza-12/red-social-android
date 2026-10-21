package conectate.com.data.repository.message

import conectate.com.data.model.Chat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountChatAccessTest {
    private fun chat(owner: String = "a", id: String = "chat") = Chat(id = id, participants = listOf(owner, "person"))

    @Test fun `preview and read changes do not restart history but membership still does`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val chats = MutableStateFlow<Chat?>(chat())
        var subscriptions = 0
        val visible = mutableListOf<List<String>>()
        val job = launch {
            accountScopedChatFlow(accounts, chats.distinctHistoryAccess(), { accounts.value }, emptyList<String>()) { _, _ ->
                subscriptions++
                flowOf(listOf("saved-message"))
            }.collect { visible += it }
        }
        runCurrent()
        chats.value = chat().copy(last_message_at = "2026-10-20T12:00:00Z")
        runCurrent()
        assertEquals(1, subscriptions)
        assertEquals(listOf("saved-message"), visible.last())
        chats.value = chat("other")
        runCurrent()
        assertTrue(visible.last().isEmpty())
        chats.value = chat()
        runCurrent()
        assertEquals(2, subscriptions)
        job.cancel()
    }

    @Test fun `known account chat opens without touching network`() = runTest {
        var remoteCalls = 0
        val reader = AccountChatReader({ chat(" A ") }, { null }, { remoteCalls++; Result.failure(Exception("offline")) })
        assertEquals("chat", reader.read(" CHAT ", "A") { true }.getOrThrow().id)
        assertEquals(0, remoteCalls)
    }

    @Test fun `no account cannot read cached chat`() = runTest {
        var localCalls = 0
        val reader = AccountChatReader({ localCalls++; chat() }, { chat() }, { Result.success(chat()) })
        assertTrue(reader.read("chat", null) { true }.isFailure)
        assertTrue(reader.read("chat", " ") { true }.isFailure)
        assertEquals(0, localCalls)
    }

    @Test fun `another accounts Room and memory rows are not returned offline`() = runTest {
        val reader = AccountChatReader({ chat("old") }, { chat("old") }, { Result.failure(Exception("offline")) })
        assertTrue(reader.read("chat", "a") { true }.isFailure)
    }

    @Test fun `authorized server response can replace stale cached membership`() = runTest {
        val reader = AccountChatReader({ chat("old") }, { null }, { Result.success(chat()) })
        assertEquals(chat(), reader.read("chat", "a") { true }.getOrThrow())
    }

    @Test fun `remote response must match requested ID and account`() = runTest {
        for (response in listOf(chat("other"), chat(id = "different"), Chat(id = "chat"))) {
            val reader = AccountChatReader({ null }, { null }, { Result.success(response) })
            assertTrue(reader.read("chat", "a") { true }.exceptionOrNull() is SecurityException)
        }
    }

    @Test fun `switch during local lookup prevents publication`() = runTest {
        var current = true
        val reader = AccountChatReader({ current = false; chat() }, { null }, { fail("No remote call"); Result.success(chat()) })
        try {
            reader.read("chat", "a") { current }
            fail("Stale read returned")
        } catch (_: CancellationException) { }
    }

    @Test fun `switch during remote lookup prevents publication`() = runTest {
        var current = true
        val reader = AccountChatReader({ null }, { null }, { current = false; Result.success(chat()) })
        try {
            reader.read("chat", "a") { current }
            fail("Stale read returned")
        } catch (_: CancellationException) { }
    }

    @Test fun `membership applies to groups and rejects missing participants`() {
        assertTrue(chat("a").copy(is_group = true).isAccessibleTo(" A "))
        assertFalse(chat("b").copy(is_group = true).isAccessibleTo("a"))
        assertFalse(Chat(participants = listOf(" ")).isAccessibleTo(" "))
    }

    @Test fun `account switch clears old metadata without a Room change`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val chats = MutableStateFlow<Chat?>(chat())
        val visible = mutableListOf<Chat?>()
        val job = launch { accountScopedChatFlow(accounts, chats, { accounts.value }, null as Chat?) { c, _ -> flowOf(c) }.collect { visible += it } }
        runCurrent()
        assertEquals(chat(), visible.last())
        accounts.value = "b"
        runCurrent()
        assertNull(visible.last())
        accounts.value = "a"
        runCurrent()
        assertEquals(chat(), visible.last())
        job.cancel()
    }

    @Test fun `sign out cancels history and cannot expose subsequent old messages`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val chats = MutableStateFlow<Chat?>(chat())
        val messages = MutableStateFlow(listOf("cached"))
        val visible = mutableListOf<List<String>>()
        val job = launch { accountScopedChatFlow(accounts, chats, { accounts.value }, emptyList<String>()) { _, _ -> messages }.collect { visible += it } }
        runCurrent()
        assertEquals(listOf("cached"), visible.last())
        accounts.value = null
        runCurrent()
        messages.value = listOf("late")
        runCurrent()
        assertTrue(visible.last().isEmpty())
        assertFalse(visible.contains(listOf("late")))
        job.cancel()
    }

    @Test fun `membership removal clears history and cannot reopen it from stale data`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val chats = MutableStateFlow<Chat?>(chat())
        val visible = mutableListOf<List<String>>()
        val job = launch { accountScopedChatFlow(accounts, chats, { accounts.value }, emptyList<String>()) { _, _ -> flowOf(listOf("cached")) }.collect { visible += it } }
        runCurrent()
        chats.value = chat("other")
        runCurrent()
        assertTrue(visible.last().isEmpty())
        job.cancel()
    }

    @Test fun `emission rechecks actual account while session flow is delayed`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        var actualAccount: String? = "a"
        val messages = MutableStateFlow(listOf("cached"))
        val visible = mutableListOf<List<String>>()
        val job = launch { accountScopedChatFlow(accounts, flowOf(chat()), { actualAccount }, emptyList<String>()) { _, _ -> messages }.collect { visible += it } }
        runCurrent()
        actualAccount = "b"
        messages.value = listOf("late")
        runCurrent()
        assertTrue(visible.last().isEmpty())
        assertFalse(visible.contains(listOf("late")))
        job.cancel()
    }

    @Test fun `switch between participants clears old private visibility before new history arrives`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val visible = mutableListOf<List<String>>()
        val job = launch {
            accountScopedChatFlow(accounts, flowOf(chat()), { accounts.value }, emptyList<String>()) { _, account ->
                flow {
                    if (account == "person") delay(1000)
                    emit(listOf("visible-for-$account"))
                }
            }.collect { visible += it }
        }
        runCurrent()
        assertEquals(listOf("visible-for-a"), visible.last())
        accounts.value = "person"
        runCurrent()
        assertTrue(visible.last().isEmpty())
        job.cancel()
    }
}
