package conectate.com.core.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UnreadPolicyRegressionTest {

    @Test
    fun `incoming message increments unread only when conversation is hidden`() {
        assertTrue(
            UnreadPolicy.shouldIncrementUnread(
                senderId = "other-user",
                currentUserId = "me",
                isConversationVisible = false
            )
        )
        assertFalse(
            UnreadPolicy.shouldIncrementUnread(
                senderId = "other-user",
                currentUserId = "me",
                isConversationVisible = true
            )
        )
        assertFalse(
            UnreadPolicy.shouldIncrementUnread(
                senderId = "me",
                currentUserId = "me",
                isConversationVisible = false
            )
        )
    }

    @Test
    fun `read transition accepts sent delivered and received`() {
        assertTrue(UnreadPolicy.isEligibleStatusForReadTransition("sent"))
        assertTrue(UnreadPolicy.isEligibleStatusForReadTransition("delivered"))
        assertTrue(UnreadPolicy.isEligibleStatusForReadTransition("received"))
        assertTrue(UnreadPolicy.isEligibleStatusForReadTransition("SENT"))
        assertFalse(UnreadPolicy.isEligibleStatusForReadTransition("read"))
        assertFalse(UnreadPolicy.isEligibleStatusForReadTransition("failed"))
        assertFalse(UnreadPolicy.isEligibleStatusForReadTransition("sending"))
    }

    @Test
    fun `dm unread counter clears after enter and exit lifecycle`() {
        val sim = ConversationUnreadSimulator(currentUserId = "me", otherUserId = "other")
        sim.setVisible(false)
        sim.receiveIncoming(status = "sent")
        sim.receiveIncoming(status = "delivered")
        assertEquals(2, sim.unreadCount)

        sim.setVisible(true)
        sim.markVisibleAsRead(sim.incomingMessageIds())
        assertEquals(0, sim.unreadCount)

        sim.setVisible(false)
        assertEquals(0, sim.unreadCount)
    }

    @Test
    fun `group unread counter clears after enter and exit lifecycle`() {
        val sim = ConversationUnreadSimulator(currentUserId = "me", otherUserId = "member-a")
        sim.setVisible(false)
        sim.receiveIncoming(status = "received")
        sim.receiveIncoming(status = "sent")
        assertEquals(2, sim.unreadCount)

        sim.setVisible(true)
        sim.markVisibleAsRead(sim.incomingMessageIds())
        assertEquals(0, sim.unreadCount)

        sim.setVisible(false)
        assertEquals(0, sim.unreadCount)
    }

    @Test
    fun `community unread counter clears after enter and exit lifecycle`() {
        val sim = ConversationUnreadSimulator(currentUserId = "me", otherUserId = "member-b")
        sim.setVisible(false)
        sim.receiveIncoming(status = "delivered")
        sim.receiveIncoming(status = "sent")
        assertEquals(2, sim.unreadCount)

        sim.setVisible(true)
        sim.markVisibleAsRead(sim.incomingMessageIds())
        assertEquals(0, sim.unreadCount)

        sim.setVisible(false)
        assertEquals(0, sim.unreadCount)
    }
}

private class ConversationUnreadSimulator(
    private val currentUserId: String,
    private val otherUserId: String
) {
    private data class SimMessage(
        val id: String,
        val senderId: String,
        var status: String,
        var isRead: Boolean = false
    )

    private val messages = mutableListOf<SimMessage>()
    private var isVisible = false
    var unreadCount: Int = 0
        private set

    fun setVisible(value: Boolean) {
        isVisible = value
    }

    fun receiveIncoming(status: String) {
        val message = SimMessage(
            id = UUID.randomUUID().toString(),
            senderId = otherUserId,
            status = status
        )
        messages += message
        if (
            UnreadPolicy.shouldIncrementUnread(
                senderId = message.senderId,
                currentUserId = currentUserId,
                isConversationVisible = isVisible
            )
        ) {
            unreadCount += 1
        }
    }

    fun incomingMessageIds(): Set<String> {
        return messages
            .asSequence()
            .filter { it.senderId != currentUserId }
            .map { it.id }
            .toSet()
    }

    fun markVisibleAsRead(visibleMessageIds: Set<String>) {
        if (!isVisible || visibleMessageIds.isEmpty()) return

        val transitioned = messages
            .filter { message ->
                !message.isRead &&
                    message.senderId != currentUserId &&
                    visibleMessageIds.contains(message.id) &&
                    UnreadPolicy.isEligibleStatusForReadTransition(message.status)
            }

        transitioned.forEach { message ->
            message.isRead = true
            message.status = "read"
        }

        unreadCount = (unreadCount - transitioned.size).coerceAtLeast(0)
    }
}
