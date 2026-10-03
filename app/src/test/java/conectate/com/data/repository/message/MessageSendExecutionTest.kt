package conectate.com.data.repository.message

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors

class MessageSendExecutionTest {
    @Test fun deliveryRunsInBackgroundAndQueueCallbackReturnsToCaller() {
        Executors.newSingleThreadExecutor { Thread(it, "qa-ui-caller") }.asCoroutineDispatcher().use { caller ->
            Executors.newSingleThreadExecutor { Thread(it, "qa-delivery") }.asCoroutineDispatcher().use { io ->
                runBlocking(caller) {
                    val events = mutableListOf<String>()
                    val result = executeMessageSend(onQueued = {
                        assertEquals("qa-ui-caller", Thread.currentThread().name.substringBefore(" @coroutine"))
                        events += "queued"
                    }, ioDispatcher = io) { queued ->
                        assertEquals("qa-delivery", Thread.currentThread().name.substringBefore(" @coroutine"))
                        events += "persisted"
                        queued()
                        assertEquals("qa-delivery", Thread.currentThread().name.substringBefore(" @coroutine"))
                        events += "ack"
                        "sent"
                    }
                    assertEquals("sent", result)
                    assertEquals(listOf("persisted", "queued", "ack"), events)
                    assertEquals("qa-ui-caller", Thread.currentThread().name.substringBefore(" @coroutine"))
                }
            }
        }
    }

    @Test fun callerRemainsAvailableWhileDeliveryWaits() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sending = launch {
            executeMessageSend(onQueued = {}) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        assertTrue(sending.isActive)
        release.complete(Unit)
        sending.join()
    }

    @Test fun cancellationStopsDeliveryAfterDurableQueueCallback() = runBlocking {
        val queued = CompletableDeferred<Unit>()
        var continued = false
        val sending = launch {
            executeMessageSend(onQueued = { queued.complete(Unit) }) { onQueued ->
                onQueued()
                awaitCancellation()
                @Suppress("UNREACHABLE_CODE")
                run { continued = true }
            }
        }
        queued.await()
        sending.cancelAndJoin()
        assertTrue(sending.isCancelled)
        assertFalse(continued)
    }

    @Test fun failedQueueCallbackDoesNotStartNetworkDelivery() = runBlocking {
        var networkStarted = false
        val failure = IllegalStateException("QA local persistence callback")
        try {
            executeMessageSend(onQueued = { throw failure }) { queued ->
                queued()
                networkStarted = true
            }
            fail("Expected callback failure")
        } catch (error: IllegalStateException) {
            assertEquals(failure.message, error.message)
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === failure })
        }
        assertFalse(networkStarted)
    }

    @Test fun deliveryCancellationIsNeverConvertedToSuccess() = runBlocking {
        val cancellation = CancellationException("QA cancelled")
        try {
            executeMessageSend(onQueued = {}) { throw cancellation }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals(cancellation.message, error.message)
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === cancellation })
        }
    }
}
