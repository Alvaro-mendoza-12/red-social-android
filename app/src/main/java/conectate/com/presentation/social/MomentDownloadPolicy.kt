package conectate.com.presentation.social

import java.net.URI
import java.util.Locale

internal data class MomentExportTarget(val fileName: String, val mimeType: String)

private val videoExportTypes = mapOf(
    "mp4" to "video/mp4",
    "m4v" to "video/x-m4v",
    "mov" to "video/quicktime",
    "webm" to "video/webm",
    "mkv" to "video/x-matroska",
    "3gp" to "video/3gpp",
    "avi" to "video/x-msvideo",
)

/** Preserve the original container; an unknown original must not be advertised as an MP4. */
internal fun momentExportTarget(id: String, mediaUrl: String, mediaType: String): MomentExportTarget {
    val path = runCatching { URI(mediaUrl.trim()).path }.getOrNull()
        ?: mediaUrl.substringBefore('?').substringBefore('#')
    val extension = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        .takeIf { it in videoExportTypes }
        ?: videoExportTypes.entries.firstOrNull { it.value.equals(mediaType.trim(), ignoreCase = true) }?.key
    val baseName = "Novvin_Momento_${id.filter { it.isLetterOrDigit() }.take(40).ifBlank { "video" }}"
    return MomentExportTarget(
        fileName = if (extension != null) "$baseName.$extension" else baseName,
        mimeType = extension?.let(videoExportTypes::get) ?: "application/octet-stream"
    )
}

/** Returns the expected decoded byte count, when the response describes it reliably. */
internal fun validateMomentExportResponse(
    target: MomentExportTarget,
    headers: Map<String, List<String>>,
    maxBytes: Long,
): Long? {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()?.trim()
    val contentType = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    require(contentType.isNullOrBlank() || contentType.startsWith("video/") || contentType == "application/octet-stream") {
        "El servidor no devolvió un archivo de video."
    }
    fun container(mime: String) = when (mime) {
        "video/x-m4v", "video/m4v", "video/mp4" -> "mp4"
        "video/matroska", "video/x-matroska" -> "mkv"
        else -> mime
    }
    if (contentType in videoExportTypes.values && target.mimeType != "application/octet-stream") {
        require(container(contentType.orEmpty()) == container(target.mimeType)) {
            "El formato del video no coincide con el archivo solicitado."
        }
    }
    require(header("Content-Range").isNullOrBlank()) { "El servidor devolvió solo una parte del video." }
    val rawLength = header("Content-Length")
    val declared = rawLength?.toLongOrNull()
    require(rawLength == null || (declared != null && declared >= 0)) { "El tamaño del video no es válido." }
    require(declared == null || declared in 1..maxBytes) { "El video está vacío o supera el límite permitido." }
    val encoding = header("Content-Encoding")
    return declared.takeIf { encoding.isNullOrBlank() || encoding.equals("identity", ignoreCase = true) }
}

internal fun validateMomentExportSize(copiedBytes: Long, expectedBytes: Long?) {
    require(copiedBytes > 0 && (expectedBytes == null || copiedBytes == expectedBytes)) {
        "La descarga del video quedó incompleta."
    }
}
