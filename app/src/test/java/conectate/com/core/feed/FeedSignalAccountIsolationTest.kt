package conectate.com.core.feed

import conectate.com.data.model.Post
import org.junit.Assert.*
import org.junit.Test

class FeedSignalAccountIsolationTest {
    private val post = Post(id="post",userId="author",userName="Author",content="test")
    @Test fun `changing accounts discards another accounts transient affinities`() {
        val tracker=InMemoryFeedSignalTracker();tracker.setAccount("a")
        tracker.recordInteraction(post,FeedInteractionAction.LIKE)
        assertFalse(tracker.snapshot().authorAffinity.isEmpty())
        tracker.setAccount("b")
        assertEquals(FeedSignalSnapshot.EMPTY,tracker.snapshot())
    }
    @Test fun `canonical equivalent account preserves signals while logout removes them`() {
        val tracker=InMemoryFeedSignalTracker();tracker.setAccount("Account")
        tracker.recordInteraction(post,FeedInteractionAction.IMPRESSION)
        val recorded=tracker.snapshot();tracker.setAccount(" ACCOUNT ")
        assertEquals(recorded,tracker.snapshot())
        tracker.setAccount(null);assertEquals(FeedSignalSnapshot.EMPTY,tracker.snapshot())
    }
}
