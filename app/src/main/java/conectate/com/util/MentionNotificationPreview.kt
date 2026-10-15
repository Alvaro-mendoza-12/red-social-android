package conectate.com.util

fun mentionNotificationPreview(content: String, mentioned: Boolean): String =
    if (mentioned) "Te mencionó: $content" else content
