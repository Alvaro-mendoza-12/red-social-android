package conectate.com.data.repository

import conectate.com.data.model.Chat
import conectate.com.data.model.ChatWithLastMessage
import org.junit.Assert.*
import org.junit.Test

class LocalInboxSnapshotTest {
    private val loaded = LocalInboxSnapshot("account-a", listOf(ChatWithLastMessage(Chat(id = "own-chat"))))

    @Test fun `current account retains the local rows`() {
        assertSame(loaded, loaded.forAccount("account-a"))
    }

    @Test fun `identity matching normalizes whitespace and case`() {
        assertSame(loaded, loaded.forAccount(" ACCOUNT-A "))
    }

    @Test fun `account switch clears the cached identity and rows`() {
        assertEquals(LocalInboxSnapshot(), loaded.forAccount("account-b"))
    }

    @Test fun `logout and invalid identity do not expose cached rows`() {
        assertEquals(LocalInboxSnapshot(), loaded.forAccount(null))
        assertEquals(LocalInboxSnapshot(), loaded.forAccount(" "))
    }

    @Test fun `empty but completed read differs from not yet loaded`() {
        val empty = LocalInboxSnapshot("account-a")
        assertSame(empty, empty.forAccount("account-a"))
        assertNotEquals(LocalInboxSnapshot(), empty)
    }
}
