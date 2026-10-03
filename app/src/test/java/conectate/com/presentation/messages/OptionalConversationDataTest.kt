package conectate.com.presentation.messages

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OptionalConversationDataTest {
    @Test fun `a failed badge load does not cancel the other profile loads`() = runTest {
        val failing = async { optionalConversationData<String> { throw IllegalStateException("offline") } }
        val profile = async { optionalConversationData { delay(50); "cached-profile" } }
        assertNull(failing.await())
        assertEquals("cached-profile", profile.await())
    }
    @Test fun `optional metadata has a deadline and returns a fallback`() = runTest {
        assertNull(optionalConversationData<String> { delay(10_000); "late" })
        assertEquals(3_000, testScheduler.currentTime)
    }
    @Test fun `leaving the conversation preserves cancellation`() = runTest {
        val task = async { optionalConversationData<String> { awaitCancellation() } }
        task.cancel()
        try { task.await(); fail("Cancellation required") } catch (_: CancellationException) { }
    }
}
