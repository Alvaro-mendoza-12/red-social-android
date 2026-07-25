package conectate.com.util

import android.net.Uri
import android.webkit.MimeTypeMap

data class ResolvedMediaMeta(
    val mimeType: String,
    val extensionWithDot: String
)

fun canonicalizeMediaContentType(contentType: String?): String? {
    val clean = contentType
        ?.trim()
        ?.lowercase()
        ?.substringBefore(";")
        ?.trim()
        .orEmpty()
    if (clean.isBlank()) return null
    return when (clean) {
        "image/jpg" -> "image/jpeg"
        "audio/m4a", "audio/x-m4a" -> "audio/mp4"
        else -> clean
    }
}

fun preferredExtensionForMime(contentType: String?): String? {
    return when (canonicalizeMediaContentType(contentType)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "audio/mpeg" -> "mp3"
        "audio/wav" -> "wav"
        "audio/mp4" -> "m4a"
        "audio/ogg" -> "ogg"
        "audio/webm" -> "webm"
        "application/pdf" -> "pdf"
        "application/zip" -> "zip"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        "text/plain" -> "txt"
        "text/csv" -> "csv"
        else -> null
    }
}

fun guessMimeTypeFromUri(uri: Uri?): String? {
    val safeUri = uri ?: return null
    var extension = MimeTypeMap.getFileExtensionFromUrl(safeUri.toString())
        ?.trim()
        ?.lowercase()
        ?.ifBlank { null }
        ?: return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}

fun inferMimeTypeFromBytes(bytes: ByteArray): String? {
    if (bytes.size < 12) return null

    if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
        return "image/jpeg"
    }
    if (
        bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte()
    ) {
        return "image/png"
    }
    if (
        bytes[0] == 0x47.toByte() &&
        bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte()
    ) {
        return "image/gif"
    }
    if (
        bytes[0] == 0x52.toByte() &&
        bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() &&
        bytes[3] == 0x46.toByte() &&
        bytes.getOrNull(8) == 0x57.toByte() &&
        bytes.getOrNull(9) == 0x45.toByte() &&
        bytes.getOrNull(10) == 0x42.toByte() &&
        bytes.getOrNull(11) == 0x50.toByte()
    ) {
        return "image/webp"
    }
    if (
        bytes[0] == 0x52.toByte() &&
        bytes[1] == 0x49.toByte() &&
        bytes[2] == 0x46.toByte() &&
        bytes[3] == 0x46.toByte() &&
        bytes.getOrNull(8) == 0x57.toByte() &&
        bytes.getOrNull(9) == 0x41.toByte() &&
        bytes.getOrNull(10) == 0x56.toByte() &&
        bytes.getOrNull(11) == 0x45.toByte()
    ) {
        return "audio/wav"
    }
    if (
        bytes[0] == 0x49.toByte() &&
        bytes[1] == 0x44.toByte() &&
        bytes[2] == 0x33.toByte()
    ) {
        return "audio/mpeg"
    }
    if ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0) {
        return "audio/mpeg"
    }
    if (
        bytes.getOrNull(4) == 0x66.toByte() &&
        bytes.getOrNull(5) == 0x74.toByte() &&
        bytes.getOrNull(6) == 0x79.toByte() &&
        bytes.getOrNull(7) == 0x70.toByte()
    ) {
        val brand = bytes.copyOfRange(8, 12).decodeToString().lowercase()
        return if (brand.contains("m4a")) "audio/mp4" else "video/mp4"
    }
    return null
}

fun resolveMediaMeta(
    preferredMediaType: String,
    declaredMime: String?,
    guessedMime: String?,
    inferredMime: String?,
    fallbackExtension: String = "bin"
): ResolvedMediaMeta {
    val declared = canonicalizeMediaContentType(declaredMime)
    val guessed = canonicalizeMediaContentType(guessedMime)
    val inferred = canonicalizeMediaContentType(inferredMime)

    val resolvedMime = when (preferredMediaType.trim().lowercase()) {
        "image", "view_once_image" -> inferred
            ?: declared?.takeIf { it.startsWith("image/") }
            ?: guessed?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"
        "video", "view_once_video" -> inferred
            ?: declared?.takeIf { it.startsWith("video/") }
            ?: guessed?.takeIf { it.startsWith("video/") }
            ?: "video/mp4"
        "audio" -> inferred
            ?: declared?.takeIf { it.startsWith("audio/") }
            ?: guessed?.takeIf { it.startsWith("audio/") }
            ?: "audio/mpeg"
        "gif" -> "image/gif"
        "sticker" -> inferred
            ?.takeIf { it == "image/webp" || it == "image/png" }
            ?: declared?.takeIf { it == "image/webp" || it == "image/png" }
            ?: guessed?.takeIf { it == "image/webp" || it == "image/png" }
            ?: "image/png"
        else -> inferred ?: declared ?: guessed ?: "application/octet-stream"
    }

    var extension = preferredExtensionForMime(resolvedMime) ?: fallbackExtension
    return ResolvedMediaMeta(
        mimeType = resolvedMime,
        extensionWithDot = ".${extension.trimStart('.')}"
    )
}



