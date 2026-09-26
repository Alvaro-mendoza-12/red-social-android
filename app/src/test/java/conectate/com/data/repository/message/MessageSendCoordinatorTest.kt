package conectate.com.data.repository.message

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessageSendCoordinatorTest {
    @Test fun `same message waits for foreground upload and acknowledgement`() = runTest {
        val coordinator = MessageSendCoordinator()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        launch { coordinator.withMessage("one") { events += "foreground"; release.await(); events += "ack" } }
        launch { coordinator.withMessage(" ONE ") { events += "worker" } }
        runCurrent()
        assertEquals(listOf("foreground"), events)
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("foreground", "ack", "worker"), events)
    }

    @Test fun `a slow media upload does not lock unrelated messages`() = runTest {
        val coordinator = MessageSendCoordinator()
        val release = CompletableDeferred<Unit>()
        var otherFinished = false
        launch { coordinator.withMessage("video") { release.await() } }
        launch { coordinator.withMessage("text") { otherFinished = true } }
        runCurrent()
        assertTrue(otherFinished)
        release.complete(Unit)
    }

    @Test fun `cancelling a waiter does not split the lock while an owner remains`() = runTest {
        val coordinator = MessageSendCoordinator()
        val release = CompletableDeferred<Unit>()
        var entered = false
        launch { coordinator.withMessage("one") { release.await() } }
        val waiter = launch { coordinator.withMessage("one") { fail("Cancelled waiter entered") } }
        runCurrent()
        waiter.cancel()
        runCurrent()
        launch { coordinator.withMessage("one") { entered = true } }
        runCurrent()
        assertFalse(entered)
        release.complete(Unit)
        runCurrent()
        assertTrue(entered)
        coordinator.withMessage("one") { assertTrue(entered) }
    }
}
