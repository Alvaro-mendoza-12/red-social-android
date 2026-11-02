package conectate.com.util

import conectate.com.data.model.Notification
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class NotificationMetadataTest {
    @Test fun aMessageQuotingASuspensionCannotAuthorizeSessionChanges() {
        val message = Notification(recipientId = "owner", actorId = "friend", type = "message",
            content = "Me llegó un texto que decía: tu cuenta fue suspendida")
        assertEquals("message", declaredNotificationType(message))
        // Historic imported descriptions remain readable without being security events.
        assertEquals("account_suspended", notificationDisplayType(message))
    }

    @Test fun malformedOrNullOptionalValuesKeepTheDeclaredEventAndDoNotCrash() {
        for (value in listOf(JsonObject(emptyMap()), JsonArray(emptyList()), JsonNull, JsonPrimitive(" null "))) {
            val notification = Notification(recipientId = "owner", actorId = "actor", type = "like_post",
                extraData = JsonObject(mapOf("notification_type" to value, "comment_id" to value)))
            assertEquals("like_post", declaredNotificationType(notification))
            assertNull(notificationExtra(notification, "comment_id"))
            assertTrue(conectate.com.presentation.profile.buildNotificationText(notification).text.isNotEmpty())
        }
    }

    @Test fun explicitSecurityEventTypesRemainAvailableWithoutInterpretingTheirText() {
        val notification = Notification(recipientId = "owner", actorId = "moderator", type = "message", content = "Aviso",
            extraData = JsonObject(mapOf("notification_type" to JsonPrimitive(" ACCOUNT_SUSPENDED "))))
        assertEquals("account_suspended", declaredNotificationType(notification))
        assertEquals("account_suspended", notificationDisplayType(notification))
    }
}
