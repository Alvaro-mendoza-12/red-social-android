package conectate.com.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPreviewOrderingTest {
    @Test
    fun `older message cannot replace current conversation preview`() {
        assertFalse(
            shouldReplaceConversationPreview(
                currentTimestamp = "2026-07-12T18:30:00Z",
                incomingTimestamp = "2026-07-12T18:29:59Z"
            )
        )
    }

    @Test
    fun `newer message replaces current conversation preview`() {
        assertTrue(
            shouldReplaceConversationPreview(
                currentTimestamp = "2026-07-12 18:30:00Z",
                incomingTimestamp = "2026-07-12T18:30:01Z"
            )
        )
    }

    @Test
    fun `first message initializes an empty conversation preview`() {
        assertTrue(
            shouldReplaceConversationPreview(
                currentTimestamp = null,
                incomingTimestamp = "2026-07-12T18:30:01Z"
            )
        )
    }
}
