package conectate.com.core.media

import java.io.File
import java.net.URI
import java.security.MessageDigest

internal fun chatMediaExportFilename(source: String, mediaType: String, timestampMs: Long): String {
    val extension = source.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        .lowercase().takeIf { it in EXPORT_EXTENSIONS }
        ?: when {
            mediaType.contains("video", ignoreCase = true) -> "mp4"
            mediaType.contains("gif", ignoreCase = true) -> "gif"
            else -> "jpg"
        }
    return "Novvin_$timestampMs.$extension"
}

private val EXPORT_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "mp4", "webm", "mkv", "3gp")

internal fun chatMediaExportMimeType(filename: String): String = when (filename.substringAfterLast('.').lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic" -> "image/heic"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "3gp" -> "video/3gpp"
    else -> "application/octet-stream"
}

/** Resolve only a complete offline file from the account that requested the export. */
internal fun resolveOfflineChatExportFile(filesDirectory: File, source: String, accountId: String?): File {
    val account = accountId?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        ?: error("No active account")
    val accountKey = MessageDigest.getInstance("SHA-256")
        .digest("account:$account".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val root = File(filesDirectory, "chat-media-offline/v1/$accountKey").canonicalFile
    val file = File(URI(source)).canonicalFile
    require(file.parentFile == root && file.name.endsWith(".media") && file.isFile && file.length() > 0L) {
        "Offline media does not belong to the active account"
    }
    return file
}
