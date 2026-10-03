package conectate.com.data.localfallback

import org.junit.Assert.*
import org.junit.Test

class PublicFeedPagingTest {
    @Test fun serverContinuationSurvivesSmallFilteredPages() {
        assertEquals(60, publicFeedNextOffset(0, 60, 3, true, 60))
        assertEquals(120, publicFeedNextOffset(60, 60, 0, true, 120))
    }
    @Test fun olderServersUseRawPageSizeBeforeLocalFiltering() {
        assertEquals(60, publicFeedNextOffset(0, 60, 60, null, null))
        assertNull(publicFeedNextOffset(0, 60, 3, null, null))
    }
    @Test fun staleInvalidAndExhaustedOffsetsCannotLoop() {
        assertNull(publicFeedNextOffset(60, 60, 60, true, 60))
        assertNull(publicFeedNextOffset(60, 60, 60, true, -1))
        assertNull(publicFeedNextOffset(9960, 60, 60, true, 10020))
        assertNull(publicFeedNextOffset(60, 60, 60, false, 120))
    }
}
