package conectate.com.presentation.profile

import conectate.com.data.model.Notification

fun buildNotificationPlainText(notification: Notification): String {
    val actor = notification.actorName?.trim().orEmpty().ifBlank { "Alguien" }
    return when (notification.type.trim().lowercase()) {
        "call_invite" -> "$actor te llamó"
        "call_cancel" -> {
            val action = notification.extraData?.get("call_action")?.toString()?.trim('"')?.lowercase().orEmpty()
            if (action == "end") {
                "$actor finalizó la llamada"
            } else {
                "$actor canceló la llamada"
            }
        }
        else -> notification.content?.trim().orEmpty().ifBlank { "$actor interactuó contigo" }
    }
}
