package conectate.com.presentation.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AnnouncementAdminMutationTest {
    @Test fun committedNoticeDoesNotBecomeFailedWhenRefreshFails() = runTest {
        var commits = 0
        val result = commitAnnouncementAndRefresh({ commits++ }, { error("list unavailable") })
        assertEquals(1, commits)
        assertNull(result.notices)
    }
    @Test fun failedPublicationDoesNotRefreshOrClearTheDraft() = runTest {
        var refreshed = false
        try {
            commitAnnouncementAndRefresh({ error("publish failed") }, { refreshed = true; emptyList() })
            fail("Expected publication failure")
        } catch (_: IllegalStateException) { }
        assertFalse(refreshed)
    }
    @Test fun successfulPublicationReturnsTheUpdatedList() = runTest {
        assertEquals(emptyList<Any>(), commitAnnouncementAndRefresh({}, { emptyList() }).notices)
    }
    @Test fun cancellationIsPreservedAfterTheCommit() = runTest {
        try {
            commitAnnouncementAndRefresh({}, { throw CancellationException("screen closed") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }
}
