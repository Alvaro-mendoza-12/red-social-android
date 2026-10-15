package conectate.com.presentation.surveys

import android.content.Context
import android.net.Uri
import android.util.Base64
import conectate.com.core.media.savePublicDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class SavedSurveyExport(val name: String, val uri: Uri, val location: String)

/** User initiated exports are saved atomically; no external destination picker. */
internal suspend fun saveSurveyExport(context: Context, input: JSONObject): SavedSurveyExport = withContext(Dispatchers.IO) {
    val extensions = mapOf(
        "application/pdf" to "pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "text/csv" to "csv", "image/png" to "png", "image/svg+xml" to "svg",
        "image/webp" to "webp", "image/jpeg" to "jpg"
    )
    val mime = input.getString("mime").substringBefore(';').lowercase()
    val extension = requireNotNull(extensions[mime]) { "Formato de descarga no admitido." }
    val encoded = input.getString("data")
    require(encoded.isNotEmpty() && encoded.length <= 64 * 1024 * 1024 && encoded.length % 4 == 0 &&
        Regex("[A-Za-z0-9+/]*={0,2}").matches(encoded)) { "El archivo de encuesta no es válido o supera 48 MB." }
    val bytes = Base64.decode(encoded, Base64.NO_WRAP)
    require(bytes.isNotEmpty() && bytes.size <= 48 * 1024 * 1024) { "El archivo está vacío o supera 48 MB." }
    val stem = input.optString("name", "encuesta").substringBeforeLast('.', "encuesta")
        .replace(Regex("[\\\\/\\x00-\\x1f]"), "").take(120).trim().ifBlank { "encuesta" }
    val filename = "$stem.$extension"
    val job = currentCoroutineContext()
    val uri = savePublicDownload(context, filename, mime, checkActive = { job.ensureActive() }) { it.write(bytes) }
    SavedSurveyExport(filename, uri, "Descargas/Novvin")
}
