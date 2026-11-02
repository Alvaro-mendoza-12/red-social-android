package conectate.com.util

import conectate.com.data.model.Notification
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun notificationExtra(notification: Notification, key: String): String? =
    (notification.extraData?.get(key) as? JsonPrimitive)?.contentOrNull
        ?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }

/** Only an explicit event type can describe a session/security event. */
internal fun declaredNotificationType(notification: Notification): String =
    notificationExtra(notification, "notification_type")?.lowercase()
        ?: notification.type.trim().lowercase()

/** Preserve wording for imported notices; this fallback must never authorize logout. */
internal fun notificationDisplayType(notification: Notification): String {
    val type = declaredNotificationType(notification)
    if (type != "message") return type
    val content = notification.content?.trim()?.lowercase().orEmpty()
    return when {
        content.contains("cuenta fue suspendida") || content.contains("cuenta ha sido suspendida") -> "account_suspended"
        content.contains("cuenta ha sido habilitada") || content.contains("cuenta fue habilitada") ||
            content.contains("cuenta habilitada nuevamente") -> "account_enabled"
        else -> type
    }
}
