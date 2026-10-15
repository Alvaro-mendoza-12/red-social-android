package conectate.com.presentation.social

import org.junit.Assert.*
import org.junit.Test

class SocialDiscoveryContinuationTest {
    @Test fun keepsAValidPersonalizedCursor() {
        assertEquals(SocialDiscoveryContinuation(true, null), socialDiscoveryContinuation(true, true))
    }
    @Test fun exhaustedShortlistContinuesWithPublicPosts() {
        assertEquals(SocialDiscoveryContinuation(false, 0), socialDiscoveryContinuation(false, false))
    }
    @Test fun inconsistentOrRepeatedCursorRecoversWithPublicPosts() {
        assertEquals(SocialDiscoveryContinuation(false, 0), socialDiscoveryContinuation(true, false))
        assertEquals(SocialDiscoveryContinuation(false, 0), socialDiscoveryContinuation(false, true))
    }
}
