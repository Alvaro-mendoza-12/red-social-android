package conectate.com.util

import conectate.com.data.model.Notification
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun isRetiredMomentViewNotification(vararg types: String?): Boolean =
    types.any { it?.trim()?.equals("view_moment", ignoreCase = true) == true }

internal fun Notification.isRetiredNotification(): Boolean = isRetiredMomentViewNotification(
    type,
    (extraData?.get("notification_type") as? JsonPrimitive)?.contentOrNull,
    (extraData?.get("notificationType") as? JsonPrimitive)?.contentOrNull,
)
