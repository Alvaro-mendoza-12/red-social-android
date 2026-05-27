package conectate.com.presentation.profile

import conectate.com.data.model.Notification
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextSupportTest {

    @Test
    fun callInvite_usesHumanFriendlyText() {
        val notification = Notification(
            recipientId = "receiver",
            actorId = "actor",
            type = "call_invite",
            actorName = "Alvaro"
        )

        assertEquals("Alvaro te llamó", buildNotificationPlainText(notification))
    }

    @Test
    fun callCancelWithEndAction_usesEndedLabel() {
        val notification = Notification(
            recipientId = "receiver",
            actorId = "actor",
            type = "call_cancel",
            actorName = "Alvaro",
            extraData = buildJsonObject {
                put("call_action", "end")
            }
        )

        assertEquals("Alvaro finalizó la llamada", buildNotificationPlainText(notification))
    }
}
