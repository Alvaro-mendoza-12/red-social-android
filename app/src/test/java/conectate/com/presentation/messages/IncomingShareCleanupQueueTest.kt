package conectate.com.presentation.messages

import org.junit.Assert.*
import org.junit.Test

class IncomingShareCleanupQueueTest {
    private fun queue() = IncomingShareCleanupQueue<String> { it }

    @Test fun theCurrentDeliveryKeepsItsSourceUntilItIsConsumed() {
        val queue = queue()
        assertTrue(queue.enqueue("a", "a").isEmpty())
        assertEquals(listOf("a"), queue.enqueue("a", null))
        assertTrue(queue.finishCapture(queue.beginCapture(), null).isEmpty())
    }

    @Test fun consumingTheOldDeliveryCannotDeleteTheNextCapturesSource() {
        val queue = queue()
        val next = queue.beginCapture()
        assertTrue(queue.enqueue("old-source", null).isEmpty())
        assertEquals(listOf("old-source"), queue.finishCapture(next, "new-copy"))
    }

    @Test fun aNewDeliveryWithTheSamePayloadIdKeepsTheSharedFolder() {
        val queue = queue()
        val capture = queue.beginCapture()
        assertTrue(queue.enqueue("same-folder", null).isEmpty())
        assertTrue(queue.finishCapture(capture, "same-folder").isEmpty())
        assertEquals(listOf("same-folder"), queue.enqueue("same-folder", null))
    }

    @Test fun everyPendingCaptureMustFinishBeforeObsoleteFilesAreReleased() {
        val queue = queue()
        val first = queue.beginCapture(); val second = queue.beginCapture()
        queue.enqueue("a", null); queue.enqueue("b", null); queue.enqueue("latest", null)
        assertTrue(queue.finishCapture(first, "latest").isEmpty())
        assertEquals(listOf("a", "b"), queue.finishCapture(second, "latest"))
        assertEquals(listOf("latest"), queue.enqueue("latest", null))
    }

    @Test fun repeatedCompletionCannotUnpinAnotherCapture() {
        val queue = queue()
        val old = queue.beginCapture(); val current = queue.beginCapture()
        queue.enqueue("a", null)
        assertTrue(queue.finishCapture(old, null).isEmpty())
        assertTrue(queue.finishCapture(old, null).isEmpty())
        assertEquals(listOf("a"), queue.finishCapture(current, "b"))
    }

    @Test fun pinningTheNewCaptureBeforeCancellingTheOldOneProtectsItsSource() {
        val queue = queue()
        val old = queue.beginCapture(); queue.enqueue("source", null)
        val replacement = queue.beginCapture()
        assertTrue(queue.finishCapture(old, null).isEmpty())
        assertEquals(listOf("source"), queue.finishCapture(replacement, "own-copy"))
    }

    @Test fun repeatedCleanupRequestsReleaseEachFolderOnlyOnce() {
        val queue = queue(); val capture = queue.beginCapture()
        queue.enqueue("a", null); queue.enqueue("a", null)
        assertEquals(listOf("a"), queue.finishCapture(capture, null))
        assertTrue(queue.finishCapture(capture, null).isEmpty())
    }
}
