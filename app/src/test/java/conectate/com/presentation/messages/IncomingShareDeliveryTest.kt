package conectate.com.presentation.messages

import org.junit.Assert.*
import org.junit.Test

class IncomingShareDeliveryTest {
    @Test fun consumingTheCurrentShareAllowsItsConversationAndInvalidatesItsOldCapture() {
        val gate = IncomingShareDeliveryGate<String>()
        val capture = gate.beginIntent()
        val delivery = gate.publish(capture, "file")!!
        val consumed = gate.consume(delivery)!!
        assertEquals("file", consumed.delivery.payload)
        assertEquals(ShareDismissal.CURRENT, consumed.dismissal)
        assertTrue(consumed.dismissal.consumed)
        assertTrue(consumed.dismissal.mayNavigate)
        assertNull(gate.publish(capture, "late result"))
    }

    @Test fun aLateShareCompletionMustNotClearOrNavigateOverANewerLink() {
        val gate = IncomingShareDeliveryGate<String>()
        val old = gate.publish(gate.beginIntent(), "file A")!!
        val newLink = gate.beginIntent()
        val consumed = gate.consume(old)!!
        assertEquals(ShareDismissal.SUPERSEDED, consumed.dismissal)
        assertTrue(consumed.dismissal.consumed)
        assertFalse(consumed.dismissal.mayNavigate)
        assertTrue(gate.isCurrentCapture(newLink))
    }

    @Test fun closingThePreviousShareDoesNotCancelTheNextFileStillBeingPrepared() {
        val gate = IncomingShareDeliveryGate<String>()
        val old = gate.publish(gate.beginIntent(), "file A")!!
        val preparing = gate.beginIntent()
        gate.consume(old)
        val next = gate.publish(preparing, "file B")!!
        assertEquals("file B", next.payload)
        assertEquals(ShareDismissal.CURRENT, gate.consume(next)?.dismissal)
    }

    @Test fun anOldCallbackCannotDeleteTheNewlyPublishedShare() {
        val gate = IncomingShareDeliveryGate<String>()
        val old = gate.publish(gate.beginIntent(), "file A")!!
        val next = gate.publish(gate.beginIntent(), "file B")!!
        assertNull(gate.consume(old))
        assertSame(next, gate.consume(next)?.delivery)
    }

    @Test fun equalPayloadIdsDoNotMakeDifferentIntentDeliveriesInterchangeable() {
        val gate = IncomingShareDeliveryGate<String>()
        val old = gate.publish(gate.beginIntent(), "same persisted ID")!!
        val next = gate.publish(gate.beginIntent(), "same persisted ID")!!
        assertNull(gate.consume(old))
        assertSame(next, gate.consume(next)?.delivery)
    }

    @Test fun anOlderCaptureCannotReplaceANewerCompletedCapture() {
        val gate = IncomingShareDeliveryGate<String>()
        val first = gate.beginIntent()
        val next = gate.publish(gate.beginIntent(), "new")!!
        assertNull(gate.publish(first, "old"))
        assertSame(next, gate.consume(next)?.delivery)
    }

    @Test fun repeatedOrNullCallbacksDoNotConsumeAnotherDelivery() {
        val gate = IncomingShareDeliveryGate<String>()
        val first = gate.publish(gate.beginIntent(), "first")!!
        gate.consume(first)
        val next = gate.publish(gate.beginIntent(), "next")!!
        assertNull(gate.consume(null))
        assertNull(gate.consume(first))
        assertSame(next, gate.consume(next)?.delivery)
    }

    @Test fun anOldInternalShareCannotDismissItsReplacement() {
        val coordinator = UniversalForwardCoordinator()
        fun payload(id: String) = ForwardMessagePayload(id, "sender", "text", "text", null, 0L, false)
        coordinator.open(listOf(payload("a")), setOf("a"))
        val first = coordinator.request!!.requestId
        coordinator.open(listOf(payload("b")), setOf("b"))
        val next = coordinator.request!!.requestId
        assertEquals(ShareDismissal.IGNORED, coordinator.dismiss(first))
        assertEquals(next, coordinator.activeRequestId)
        assertEquals(ShareDismissal.CURRENT, coordinator.dismiss(next))
        assertNull(coordinator.activeRequestId)
        assertNull(coordinator.request)
    }
}
