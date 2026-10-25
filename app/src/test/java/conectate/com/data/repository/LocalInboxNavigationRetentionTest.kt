package conectate.com.data.repository

import conectate.com.data.model.Chat
import conectate.com.data.model.ChatWithLastMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalInboxNavigationRetentionTest {
    private fun rows(id: String) = listOf(ChatWithLastMessage(Chat(id = id)))

    @Test fun `return from a long chat keeps current rows without reopening Room`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val localRows = MutableStateFlow(rows("first"))
        var reads = 0
        val inbox = observeLocalInboxSnapshots(backgroundScope, accounts, { accounts.value }) {
            flow { reads++; emitAll(localRows) }
        }
        runCurrent()
        assertEquals(0, reads) // Creating the repository must not open the database.
        val firstScreen = backgroundScope.launch { inbox.collect {} }
        runCurrent()
        assertEquals(rows("first"), inbox.value.conversations)
        firstScreen.cancel()
        advanceTimeBy(60_000)
        runCurrent()
        localRows.value = rows("received-while-in-chat")
        runCurrent()
        val returned = mutableListOf<LocalInboxSnapshot>()
        backgroundScope.launch { inbox.collect { returned += it } }
        runCurrent()
        assertEquals(1, reads)
        assertEquals(rows("received-while-in-chat"), returned.first().conversations)
        assertTrue(returned.all { it.accountId == "a" })
    }

    @Test fun `account change while screen is absent replaces private rows and logout clears them`() = runTest {
        val accounts = MutableStateFlow<String?>("a")
        val aRows = MutableStateFlow(rows("private-a"))
        val bRows = MutableStateFlow(rows("private-b"))
        var bReadStarted = false
        val inbox = observeLocalInboxSnapshots(backgroundScope, accounts, { accounts.value }) { account ->
            if (account == "a") aRows else flow {
                bReadStarted = true
                emitAll(bRows)
            }
        }
        val screen = backgroundScope.launch { inbox.collect {} }
        runCurrent()
        screen.cancel()
        accounts.value = "b"
        // Cached projections must also be rejected synchronously before collectors run.
        assertTrue(inbox.value.forAccount(accounts.value).conversations.isEmpty())
        runCurrent()
        assertTrue(bReadStarted)
        assertEquals("b", inbox.value.accountId)
        assertEquals(rows("private-b"), inbox.value.conversations)
        aRows.value = rows("late-a")
        runCurrent()
        assertEquals(rows("private-b"), inbox.value.conversations)
        accounts.value = null
        runCurrent()
        assertEquals(LocalInboxSnapshot(), inbox.value)
    }
}
