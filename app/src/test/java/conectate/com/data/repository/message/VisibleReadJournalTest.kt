package conectate.com.data.repository.message

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class VisibleReadJournalTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var store = VisibleReadJournal(context)
    @After fun close() { store.close(); context.deleteDatabase("visible_read_receipts.db") }

    @Test fun `seen messages survive reopening and switching back to account A`() {
        for (kind in listOf("chat", "group", "community")) store.record("A", kind, "room", listOf("old"))
        store.close()
        store = VisibleReadJournal(context)
        for (kind in listOf("chat", "group", "community")) {
            assertEquals(1, store.resolveUnread("B", kind, "room", 1, listOf("old")))
            assertEquals(0, store.resolveUnread(" A ", kind, "room", 1, listOf("old")))
            assertEquals(1, store.resolveUnread("A", kind, "room", 2, listOf("old", "new")))
        }
    }

    @Test fun `same identifiers never leak across accounts rooms or conversation kinds`() {
        store.record("a", "group", "room", listOf("m"))
        assertTrue(store.seenIds("a", "community", "room").isEmpty())
        assertTrue(store.seenIds("b", "group", "room").isEmpty())
        assertTrue(store.seenIds("a", "group", "another").isEmpty())
    }

    @Test fun `duplicate viewport events do not decrement a badge twice`() {
        assertEquals(setOf("one", "two"), store.record("a", "chat", "room", listOf("ONE", "one", "two")))
        assertTrue(store.record("a", "chat", "room", listOf("one")).isEmpty())
    }

    @Test fun `partial server confirmation retains failed receipts for retry`() {
        store.record("a", "community", "room", listOf("one", "two"))
        store.acknowledge("a", "community", "room", listOf("one"))
        store.close(); store = VisibleReadJournal(context)
        assertEquals(setOf("two"), store.pendingIds("a", "community", "room"))
        assertEquals(setOf("one", "two"), store.seenIds("a", "community", "room"))
    }

    @Test fun `delayed server result cannot restore seen badges or hide unseen messages`() {
        store.record("a", "chat", "room", listOf("seen"))
        assertEquals(2, store.resolveUnread("a", "chat", "room", 3, listOf("seen", "seen", "new", "offscreen")))
        assertEquals(1, store.resolveUnread("a", "chat", "room", 1, listOf("new")))
        assertEquals(4, store.resolveUnread("a", "chat", "room", 4, null))
    }
}
