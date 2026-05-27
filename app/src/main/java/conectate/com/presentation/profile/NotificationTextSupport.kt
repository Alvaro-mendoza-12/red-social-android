package conectate.com.presentation.profile

import conectate.com.data.model.Notification
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun buildNotificationPlainText(notification: Notification): String {
    val actor = notification.actorName?.takeIf { it.isNotBlank() } ?: "Alguien"
    return when (notification.type.trim().lowercase()) {
        "call_invite" -> "$actor te llamó"
        "call_cancel" -> {
            val action = notification.extraData
                ?.get("call_action")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.lowercase()
            if (action == "end") "$actor finalizó la llamada" else "$actor canceló la llamada"
        }
        "follow" -> "$actor comenzó a seguirte"
        "like_post" -> "$actor reaccionó a tu publicación"
        "comment_post" -> "$actor comentó tu publicación"
        "share_post" -> "$actor compartió tu publicación"
        "like_video" -> "$actor reaccionó a tu video"
        "comment_video" -> "$actor comentó tu video"
        else -> notification.content?.takeIf { it.isNotBlank() } ?: "$actor tiene una actualización para ti"
    }
}
