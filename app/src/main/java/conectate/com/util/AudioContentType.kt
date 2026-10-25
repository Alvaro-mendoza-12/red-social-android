package conectate.com.util

/** A browser voice note can use a WebM container even when it only has audio. */
internal fun isAudioDeliveryContentType(value: String): Boolean {
    val mime = value.substringBefore(';').trim().lowercase()
    return mime.isEmpty() || mime.startsWith("audio/") ||
        mime in setOf("video/mp4", "video/webm", "application/ogg", "application/octet-stream")
}
