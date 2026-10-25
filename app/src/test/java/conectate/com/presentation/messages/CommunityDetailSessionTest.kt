package conectate.com.presentation.messages

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CommunityDetailSessionTest {
    @Test fun reopeningSameCommunityDoesNotCreateMoreObservers() = runTest {
        val sessions = CommunityDetailSession(backgroundScope)
        val first = sessions.open("community", "account")!!
        var observers = 0
        first.scope.launch { observers++; try { awaitCancellation() } finally { observers-- } }
        runCurrent()
        repeat(10) { assertNull(sessions.open("community", "account")) }
        assertEquals(1, observers)
    }

    @Test fun switchingCommunityCancelsPreviousObserversAndReads() = runTest {
        val sessions = CommunityDetailSession(backgroundScope)
        val first = sessions.open("first", "account")!!
        var previousCancelled = false
        first.scope.launch { try { awaitCancellation() } finally { previousCancelled = true } }
        runCurrent()
        val second = sessions.open("second", "account")!!
        runCurrent()
        assertTrue(previousCancelled)
        assertFalse(sessions.isCurrent(first, "account"))
        assertTrue(sessions.isCurrent(second, "account"))
    }

    @Test fun lateResponseFromCancelledSourceCannotPublishIntoAnotherCommunity() = runTest {
        val sessions = CommunityDetailSession(backgroundScope)
        val first = sessions.open("first", "account")!!
        val response = CompletableDeferred<String>()
        var visible = "second community"
        first.scope.launch {
            // Some transports deliver a callback even after cancellation.
            withContext(NonCancellable) {
                val fetched = response.await()
                if (sessions.isCurrent(first, "account")) visible = fetched
            }
        }
        runCurrent()
        sessions.open("second", "account")
        response.complete("stale first community")
        runCurrent()
        assertEquals("second community", visible)
    }

    @Test fun accountChangeInvalidatesEvenTheSameCommunity() = runTest {
        val sessions = CommunityDetailSession(backgroundScope)
        val first = sessions.open("community", "first account")!!
        val second = sessions.open("community", "second account")!!
        assertFalse(sessions.isCurrent(first, "second account"))
        assertFalse(sessions.isCurrent(second, "first account"))
        assertTrue(sessions.isCurrent(second, "second account"))
    }
}
