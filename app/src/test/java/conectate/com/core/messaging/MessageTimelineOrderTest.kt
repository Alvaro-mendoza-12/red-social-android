package conectate.com.core.messaging

import conectate.com.data.model.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTimelineOrderTest {
    @Test fun `pending stickers follow creation time instead of random IDs`() {
        val first=Message(id="z-first",sender_id="me",content_type="sticker",status="sending",created_at="2026-10-21T13:49:06.432Z")
        val second=first.copy(id="a-second",created_at="2026-10-21T13:49:29.206Z")
        assertEquals(listOf("z-first","a-second"),orderMessageTimeline(listOf(second,first)).map { it.id })
        assertEquals(listOf("z-first","a-second"),orderMessageTimeline(listOf(first,second.copy(media_url="file:/uploaded.webp"))).map { it.id })
    }
    @Test fun `incoming messages and equal timestamp ties remain chronological`() {
        val sent=Message(id="sent",sender_id="peer",status="sent",created_at="2026-10-21T13:51:00Z")
        val a=sent.copy(id="a",status="sending",created_at="2026-10-21T13:49:00Z")
        val b=a.copy(id="b")
        assertEquals(listOf("a","b","sent"),orderMessageTimeline(listOf(b,a,sent)).map { it.id })
    }
    @Test fun `out of order acknowledgements never swap pending stickers`() {
        val first=Message(id="z-first",sender_id="me",content_type="sticker",status="sending",created_at="2026-10-21T13:49:06.432Z")
        val second=first.copy(id="a-second",created_at="2026-10-21T13:49:29.206Z")
        for ((firstState,secondState) in listOf("sending" to "sent","sent" to "sending","sent" to "sent","read" to "delivered","failed" to "sent")) {
            val actual=orderMessageTimeline(listOf(second.copy(status=secondState),first.copy(status=firstState)))
            assertEquals("States: $firstState/$secondState",listOf("z-first","a-second"),actual.map { it.id })
        }
    }
    @Test fun `equivalent UTC timestamps use chronological rather than textual ordering`() {
        val early=Message(id="early",sender_id="peer",status="sent",created_at="2026-10-21T13:00:00+00:00")
        val late=early.copy(id="late",created_at="2026-10-21T13:00:00.500Z")
        assertEquals(listOf("early","late"),orderMessageTimeline(listOf(late,early)).map { it.id })
    }
}
