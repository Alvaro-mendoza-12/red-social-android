package conectate.com.presentation.posts

import org.junit.Assert.*
import org.junit.Test

class PostCommentDraftRevisionTest {
    @Test fun aConfirmedUnchangedDraftCanBeClearedOnlyOnce() {
        val draft = PostCommentDraftRevision()
        val sent = draft.snapshot()
        assertTrue(draft.acknowledge(sent))
        assertFalse(draft.acknowledge(sent))
    }
    @Test fun typingOrChangingTheReplyWhileSendingProtectsTheNewDraft() {
        val draft = PostCommentDraftRevision()
        val sent = draft.snapshot()
        draft.changed()
        assertFalse(draft.acknowledge(sent))
    }
    @Test fun clearingAndRetypingTheSameTextStillCountsAsANewDraft() {
        val draft = PostCommentDraftRevision()
        val sent = draft.snapshot()
        draft.changed()
        draft.changed()
        assertFalse(draft.acknowledge(sent))
        assertTrue(draft.acknowledge(draft.snapshot()))
    }
}
