package conectate.com.data.repository.message

import org.junit.Assert.*
import org.junit.Test

class InboxInitialPresentationTest {
    @Test fun `unknown local snapshot keeps initial loading`() {
        assertTrue(inboxNeedsInitialLoading(InboxSyncState(isRefreshing = true)))
    }
    @Test fun `a completed empty local read is usable during cloud refresh`() {
        assertFalse(inboxNeedsInitialLoading(InboxSyncState(hasLoadedLocalSnapshot = true, isRefreshing = true)))
    }
    @Test fun `completed cloud refresh remains usable`() {
        assertFalse(inboxNeedsInitialLoading(InboxSyncState(hasCompletedInitialSync = true)))
    }
    @Test fun `account reset requires a new read`() {
        assertTrue(inboxNeedsInitialLoading(InboxSyncState()))
    }
}
