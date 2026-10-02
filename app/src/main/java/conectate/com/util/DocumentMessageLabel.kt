package conectate.com.util

internal fun documentMessageLabel(caption: String, providerName: String?): String {
    if (caption.isNotBlank() && caption.trim() !in setOf("📎 Archivo", "Archivo adjunto")) return caption.trim()
    val name = providerName?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.filterNot { it.isISOControl() }?.trim()?.take(255)
    return name?.takeIf { it.isNotEmpty() && it !in setOf(".", "..") } ?: "Archivo adjunto"
}
