package conectate.com.util

/** Old payloads may contain an empty body or repeat only the actor's name. */
internal fun socialNotificationBody(actor: String, suppliedBody: String?, action: String?): String {
    val body=suppliedBody?.trim().orEmpty()
    if (action.isNullOrBlank()) return body
    return if (body.isBlank() || body.equals(actor.trim(),ignoreCase=true)) action else body
}

/** Legacy mention_post events refer to comments; new publication events say so explicitly. */
internal fun postMentionAction(notification: conectate.com.data.model.Notification): String =
    if ((notification.extraData?.get("mention_context") as? kotlinx.serialization.json.JsonPrimitive)?.content == "publication")
        "Te mencionó en esta publicación"
    else "Te mencionó en un comentario"
