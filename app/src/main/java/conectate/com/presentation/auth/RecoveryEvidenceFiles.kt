package conectate.com.presentation.auth

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import conectate.com.data.localfallback.RecoveryUploadEvidence
import java.io.ByteArrayOutputStream

const val RECOVERY_EVIDENCE_MAX_BYTES = 8 * 1024 * 1024
data class RecoveryEvidenceSelection(val uri: Uri, val name: String, val mime: String, val size: Long)
fun readRecoveryEvidenceMetadata(context: Context, uri: Uri): RecoveryEvidenceSelection {
    var name = "evidencia"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty().ifBlank { name }
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    require(size <= RECOVERY_EVIDENCE_MAX_BYTES) { "$name supera los 8 MB." }
    return RecoveryEvidenceSelection(uri, name.take(160), context.contentResolver.getType(uri) ?: "application/octet-stream", size)
}
fun loadRecoveryEvidence(context: Context, selection: RecoveryEvidenceSelection): RecoveryUploadEvidence {
    val stream = context.contentResolver.openInputStream(selection.uri) ?: error("No se pudo abrir ${selection.name}. Vuelve a seleccionarlo.")
    val output = ByteArrayOutputStream()
    stream.use {
        val chunk = ByteArray(32 * 1024)
        while (true) {
            val count = it.read(chunk)
            if (count < 0) break
            require(output.size() + count <= RECOVERY_EVIDENCE_MAX_BYTES) { "${selection.name} supera los 8 MB." }
            output.write(chunk, 0, count)
        }
    }
    require(output.size() > 0) { "${selection.name} está vacío." }
    return RecoveryUploadEvidence(selection.name, selection.mime, output.toByteArray())
}
