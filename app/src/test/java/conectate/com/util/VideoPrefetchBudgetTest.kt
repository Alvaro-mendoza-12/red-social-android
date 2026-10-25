package conectate.com.util

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import conectate.com.test.UnitTestApplication

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class VideoPrefetchBudgetTest {
    @Test fun prefetchHasAnEntireCallDeadlineAndReusesForegroundConnections() {
        val visible = MediaPlaybackDataSources.networkClient()
        val speculative = MediaPlaybackDataSources.boundedPrefetchClient(1_500)
        assertEquals(1_500, speculative.callTimeoutMillis)
        assertEquals(1_500, speculative.connectTimeoutMillis)
        assertEquals(1_500, speculative.readTimeoutMillis)
        assertSame(visible.connectionPool, speculative.connectionPool)
        assertSame(visible.dispatcher, speculative.dispatcher)
        assertEquals(0, visible.callTimeoutMillis)
    }
}
