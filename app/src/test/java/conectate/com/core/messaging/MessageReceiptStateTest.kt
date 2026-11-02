package conectate.com.core.messaging

import org.junit.Assert.*
import org.junit.Test

class MessageReceiptStateTest {
    @Test fun `late sent response retains read even without a read timestamp`() {
        val result = mergeMessageReceiptState("sent", null, null, "read", null, null)
        assertEquals("read", result.status)
    }

    @Test fun `read timestamp confirms delivery and ignores blank fields in late response`() {
        val result = mergeMessageReceiptState("failed", " ", "", "sent", null, "2026-11-01T12:00:02Z")
        assertEquals("read", result.status)
        assertEquals(result.readAt, result.deliveredAt)
    }

    @Test fun `retry replaces failed state when there is no receipt`() {
        val result = mergeMessageReceiptState("sending", null, null, "failed", null, null)
        assertEquals("sending", result.status)
    }

    @Test fun `legacy optimistic sent state does not mask a real failure`() {
        val result = mergeMessageReceiptState("failed", null, null, "sent", null, null)
        assertEquals("failed", result.status)
    }

    @Test fun `legacy received receipt cannot regress on retry failure`() {
        val result = mergeMessageReceiptState("failed", null, null, "received", null, null)
        assertEquals("delivered", result.status)
    }

    @Test fun `new read response preserves the previous delivery timestamp`() {
        val result = mergeMessageReceiptState("read", null, "read-time", "delivered", "delivery-time", null)
        assertEquals("read", result.status)
        assertEquals("delivery-time", result.deliveredAt)
        assertEquals("read-time", result.readAt)
    }
}
