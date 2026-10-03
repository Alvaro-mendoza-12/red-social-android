package conectate.com.presentation.social

import org.junit.Assert.*
import org.junit.Test

class SocialFeedPagingPolicyTest {
    @Test fun compactAndLargeViewportsRequestContinuationNearTheEnd() {
        assertTrue(shouldLoadMoreSocialPosts(2, 3, true, false, false, false))
        assertTrue(shouldLoadMoreSocialPosts(16, 20, true, false, false, false))
        assertFalse(shouldLoadMoreSocialPosts(4, 20, true, false, false, false))
    }
    @Test fun aFilteredPageCanContinueAfterItsLoadingStateFinishes() {
        assertFalse(shouldLoadMoreSocialPosts(2, 3, true, false, true, false))
        assertTrue(shouldLoadMoreSocialPosts(2, 3, true, false, false, false))
    }
    @Test fun errorsExhaustionAndRefreshDoNotCreateRequestLoops() {
        assertFalse(shouldLoadMoreSocialPosts(2, 3, true, false, false, true))
        assertFalse(shouldLoadMoreSocialPosts(2, 3, false, false, false, false))
        assertFalse(shouldLoadMoreSocialPosts(2, 3, true, true, false, false))
        assertFalse(shouldLoadMoreSocialPosts(null, 0, true, false, false, false))
    }
}
