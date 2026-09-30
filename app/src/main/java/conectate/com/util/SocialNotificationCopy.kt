package conectate.com.util

/** Old payloads may contain an empty body or repeat only the actor's name. */
internal fun socialNotificationBody(actor: String, suppliedBody: String?, action: String?): String {
    val body=suppliedBody?.trim().orEmpty()
    if (action.isNullOrBlank()) return body
    return if (body.isBlank() || body.equals(actor.trim(),ignoreCase=true)) action else body
}
