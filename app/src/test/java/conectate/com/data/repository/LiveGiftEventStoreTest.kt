package conectate.com.data.repository

import conectate.com.data.model.LiveGiftEvent
import conectate.com.data.model.VirtualGift
import conectate.com.presentation.wallet.GiftEventQueue
import org.junit.Assert.*
import org.junit.Test

class LiveGiftEventStoreTest {
    private fun gift(id: Int) = LiveGiftEvent(
        transactionId = "transaction-$id", liveStreamId = "live", senderId = "sender",
        senderName = "Sender", receiverId = "host", gift = VirtualGift("gift", "Gift", priceDiamonds = 100),
    )

    @Test
    fun `receipt and socket echo enter history and celebration only once`() {
        val store = LiveGiftEventStore()
        assertTrue(store.record(gift(1), "live"))
        assertFalse(store.record(gift(1).copy(senderName = "Echo"), "live"))
        val queue = GiftEventQueue()
        queue.offerAll(store.events.value)
        queue.complete("transaction-1")
        queue.offerAll(store.events.value)
        assertNull(queue.peek())
        assertEquals(1, store.events.value.size)
    }

    @Test
    fun `burst remains available when animation queue is full`() {
        val store = LiveGiftEventStore()
        val queue = GiftEventQueue(capacity = 3)
        (1..12).forEach { store.record(gift(it), "live") }
        val celebrated = mutableListOf<String>()
        repeat(12) {
            queue.offerAll(store.events.value)
            val event = requireNotNull(queue.peek())
            celebrated += event.transactionId
            queue.complete(event.transactionId)
        }
        assertEquals((1..12).map { "transaction-$it" }, celebrated)
        assertEquals(12, store.events.value.size)
    }

    @Test
    fun `history is bounded but a delayed duplicate does not reenter after eviction`() {
        val store = LiveGiftEventStore(capacity = 2)
        (1..4).forEach { store.record(gift(it), "live") }
        assertFalse(store.record(gift(1), "live"))
        assertEquals(listOf("transaction-3", "transaction-4"), store.events.value.map { it.transactionId })
    }

    @Test
    fun `ended session rejects late events and new sessions start empty`() {
        val store = LiveGiftEventStore()
        store.record(gift(1), "live")
        store.clear()
        assertFalse(store.record(gift(2), ""))
        assertFalse(store.record(gift(3), "other-live"))
        assertTrue(store.events.value.isEmpty())
    }

    @Test
    fun `backgrounded gifts are not replayed but new foreground gifts are displayed`() {
        val queue = GiftEventQueue()
        queue.offerAll(listOf(gift(1)))
        queue.discardAll(listOf(gift(1), gift(2)))
        queue.offerAll(listOf(gift(1), gift(2), gift(3)))
        assertEquals("transaction-3", queue.peek()?.transactionId)
    }
}
