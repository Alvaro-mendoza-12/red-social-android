package conectate.com.data.localfallback

/** Wait for acceptance without changing the queued row into a failed message. */
internal fun isPendingMessagePermission(body: String?): Boolean =
    body?.contains("MESSAGE_REQUEST_PENDING", ignoreCase = true) == true
