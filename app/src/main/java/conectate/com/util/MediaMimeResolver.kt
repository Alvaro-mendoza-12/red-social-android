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
        "audio/mp3", "audio/x-mp3", "audio/x-mpeg" -> "audio/mpeg"
        "audio/x-wav" -> "audio/wav"
        "audio/x-flac" -> "audio/flac"
        "application/msexcel", "application/x-msexcel", "application/xls", "application/x-excel" -> "application/vnd.ms-excel"
        "application/mspowerpoint", "application/x-mspowerpoint" -> "application/vnd.ms-powerpoint"
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
        "audio/flac" -> "flac"
        "application/pdf" -> "pdf"
        "application/zip" -> "zip"
        "application/vnd.ms-excel" -> "xls"
        "application/vnd.ms-excel.sheet.macroenabled.12" -> "xlsm"
        "application/vnd.ms-excel.sheet.binary.macroenabled.12" -> "xlsb"
        "application/msword" -> "doc"
        "application/vnd.ms-word.document.macroenabled.12" -> "docm"
        "application/vnd.ms-powerpoint" -> "ppt"
        "application/vnd.ms-powerpoint.presentation.macroenabled.12" -> "pptm"
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
        "application/vnd.oasis.opendocument.text" -> "odt"
        "application/vnd.oasis.opendocument.spreadsheet" -> "ods"
        "application/vnd.oasis.opendocument.presentation" -> "odp"
        "application/rtf" -> "rtf"
        "text/plain" -> "txt"
        "text/csv" -> "csv"
        "text/tab-separated-values" -> "tsv"
        "application/json" -> "json"
        "application/xml", "text/xml" -> "xml"
        "application/epub+zip" -> "epub"
        "application/x-7z-compressed" -> "7z"
        "application/vnd.rar" -> "rar"
        else -> null
    }
}

fun fileExtensionFromName(fileName: String?): String? {
    val cleanName = fileName
        ?.substringBefore('?')
        ?.substringBefore('#')
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        .orEmpty()
    val extension = cleanName.substringAfterLast('.', "").trim().lowercase()
    return extension.takeIf { it.matches(Regex("[a-z0-9]{1,16}")) }
}

fun guessMimeTypeFromFileName(fileName: String?): String? {
    val extension = fileExtensionFromName(fileName) ?: return null
    val explicit = when (extension) {
        "xls" -> "application/vnd.ms-excel"
        "xlsm" -> "application/vnd.ms-excel.sheet.macroEnabled.12"
        "xlsb" -> "application/vnd.ms-excel.sheet.binary.macroEnabled.12"
        "doc" -> "application/msword"
        "docm" -> "application/vnd.ms-word.document.macroEnabled.12"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptm" -> "application/vnd.ms-powerpoint.presentation.macroEnabled.12"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        "rtf" -> "application/rtf"
        "tsv" -> "text/tab-separated-values"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "epub" -> "application/epub+zip"
        "7z" -> "application/x-7z-compressed"
        "rar" -> "application/vnd.rar"
        else -> null
    }
    return explicit ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}

fun guessMimeTypeFromUri(uri: Uri?): String? {
    val safeUri = uri ?: return null
    guessMimeTypeFromFileName(safeUri.lastPathSegment)?.let { return it }
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
        bytes[0] == 0x4F.toByte() &&
        bytes[1] == 0x67.toByte() &&
        bytes[2] == 0x67.toByte() &&
        bytes[3] == 0x53.toByte()
    ) {
        return "audio/ogg"
    }
    if (
        bytes[0] == 0x66.toByte() &&
        bytes[1] == 0x4C.toByte() &&
        bytes[2] == 0x61.toByte() &&
        bytes[3] == 0x43.toByte()
    ) {
        return "audio/flac"
    }
    if (
        bytes[0] == 0x1A.toByte() &&
        bytes[1] == 0x45.toByte() &&
        bytes[2] == 0xDF.toByte() &&
        bytes[3] == 0xA3.toByte()
    ) {
        return "audio/webm"
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
        // MediaMuxer uses generic MP4 brands (for example isom) for AAC-only
        // recordings too. ftyp identifies the container, not its track types.
        // Preserve the voice-message kind, including retries of old .mp4 drafts,
        // so the backend does not try to remux an audio file as a video.
        "audio" -> inferred?.let { if (it == "video/mp4") "audio/mp4" else it }
            ?: declared?.takeIf { it.startsWith("audio/") }
            ?: guessed?.takeIf { it.startsWith("audio/") }
            ?: "audio/mpeg"
        "gif" -> "image/gif"
        "sticker" -> inferred
            ?.takeIf { it == "image/webp" || it == "image/png" }
            ?: declared?.takeIf { it == "image/webp" || it == "image/png" }
            ?: guessed?.takeIf { it == "image/webp" || it == "image/png" }
            ?: "image/png"
        "document", "file" -> inferred
            ?: guessed?.takeIf(::isSafeOpaqueAttachmentMime)
            ?: declared?.takeIf(::isSafeOpaqueAttachmentMime)
            ?: "application/octet-stream"
        else -> inferred ?: declared ?: guessed ?: "application/octet-stream"
    }

    var extension = preferredExtensionForMime(resolvedMime) ?: fallbackExtension
    return ResolvedMediaMeta(
        mimeType = resolvedMime,
        extensionWithDot = ".${extension.trimStart('.')}"
    )
}

private fun isSafeOpaqueAttachmentMime(mime: String): Boolean {
    return mime in setOf(
        "application/pdf", "application/zip", "application/vnd.rar", "application/x-7z-compressed",
        "application/vnd.ms-excel", "application/msword", "application/vnd.ms-powerpoint",
        "application/vnd.ms-excel.sheet.macroenabled.12",
        "application/vnd.ms-excel.sheet.binary.macroenabled.12",
        "application/vnd.ms-word.document.macroenabled.12",
        "application/vnd.ms-powerpoint.presentation.macroenabled.12",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation", "application/rtf", "application/epub+zip",
        "application/json", "application/xml", "text/xml", "text/plain", "text/csv",
        "text/tab-separated-values"
    )
}



