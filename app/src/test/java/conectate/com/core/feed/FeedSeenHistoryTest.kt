package conectate.com.core.feed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class FeedSeenHistoryTest {
    private lateinit var context: Context
    private val stores = mutableListOf<FeedSeenHistory>()
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("feed_seen_history.db") }
    @After fun cleanup() { stores.forEach { it.closeForTest() }; context.deleteDatabase("feed_seen_history.db") }
    private fun store() = FeedSeenHistory(context).also(stores::add)

    @Test fun `visible IDs survive recreation and never leak between accounts or surfaces`() = runBlocking {
        val first = store()
        first.record(" Account-A ", "video", " Video-1 ")!!.join()
        first.record("account-a", "social", "Post-1")!!.join()
        val reopened = store()
        assertEquals(setOf("video-1"), reopened.snapshot("ACCOUNT-A", "video"))
        assertEquals(setOf("post-1"), reopened.snapshot("account-a", "social"))
        assertTrue(reopened.snapshot("account-b", "video").isEmpty())
        assertTrue(reopened.snapshot(null, "video").isEmpty())
    }
    @Test fun `snapshot excludes every seen candidate and deduplicates canonical IDs`() = runBlocking {
        val history = store()
        history.record("a", "video", "seen")!!.join()
        assertEquals(listOf("new"), unseenFeedItems(listOf(" SEEN ", "new", "NEW", "", "   "), history.snapshot("a", "video")) { it })
    }
    @Test fun `recording a current item does not erase it from the fixed visit snapshot`() = runBlocking {
        val history = store(); val visit = history.snapshot("a", "social")
        history.record("a", "social", "post")!!.join()
        assertEquals(listOf("post"), unseenFeedItems(listOf("post"), visit) { it })
        assertTrue(unseenFeedItems(listOf("post"), history.snapshot("a", "social")) { it }.isEmpty())
    }
    @Test fun `a consumed page does not hide unseen content on subsequent pages`() {
        val seen = (0..119).map { "v$it" }.toSet()
        assertTrue(unseenFeedItems((0..59).map { "v$it" }, seen) { it }.isEmpty())
        assertEquals(listOf("v120", "v121"), unseenFeedItems((60..121).map { "v$it" }, seen) { it })
    }
    @Test fun `duplicate samples do not schedule duplicate writes and invalid IDs are ignored`() = runBlocking {
        val history = store()
        history.record("a", "video", "v")!!.join()
        assertNull(history.record("a", "video", " V "))
        assertNull(history.record("", "video", "v"))
        assertNull(history.record("a", "video", "x".repeat(129)))
        assertEquals(setOf("v"), history.snapshot("a", "video"))
    }

    @Test fun `recency persists independently for each account and surface`() = runBlocking {
        val history = store()
        history.recordAt("a", "video", "old", 100_000L)!!.join()
        history.recordAt("a", "video", "recent", 110_000L)!!.join()
        history.recordAt("b", "video", "other", 120_000L)!!.join()
        history.recordAt("a", "social", "post", 130_000L)!!.join()
        assertEquals(listOf("recent", "old"), store().recent("a", "video"))
        assertEquals(listOf("other"), history.recent("b", "video"))
        assertEquals(listOf("post"), history.recent("a", "social"))
        assertTrue(history.recent(null, "video").isEmpty())
    }
    @Test fun `a replay refreshes recency without deleting seen IDs`() = runBlocking {
        val history = store()
        history.recordAt("a", "video", "first", 100_000L)!!.join()
        history.recordAt("a", "video", "second", 110_000L)!!.join()
        assertNull(history.recordAt("a", "video", "first", 120_000L))
        history.recordAt("a", "video", "first", 140_000L)!!.join()
        assertEquals(listOf("first", "second"), history.recent("a", "video"))
        assertEquals(setOf("first", "second"), history.snapshot("a", "video"))
        assertEquals(listOf("first"), history.recent("a", "video", 1))
    }
    @Test fun `version one history upgrades without losing existing IDs`() = runBlocking {
        context.openOrCreateDatabase("feed_seen_history.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE seen(account TEXT NOT NULL,surface TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(account,surface,id)) WITHOUT ROWID")
            db.execSQL("INSERT INTO seen VALUES('a','video','legacy')")
            db.version = 1
        }
        val history = store()
        assertEquals(setOf("legacy"), history.snapshot("a", "video"))
        history.recordAt("a", "video", "current", 100_000L)!!.join()
        assertEquals(listOf("current", "legacy"), history.recent("a", "video"))
    }

    @Test fun `an older writer cannot move a recently seen item backwards`() = runBlocking {
        val first = store()
        first.recordAt("a", "video", "newest", 140_000L)!!.join()
        first.recordAt("a", "video", "older", 120_000L)!!.join()
        val reopened = store()
        reopened.recordAt("a", "video", "newest", 100_000L)!!.join()
        assertEquals(listOf("newest", "older"), reopened.recent("a", "video"))
    }
}
