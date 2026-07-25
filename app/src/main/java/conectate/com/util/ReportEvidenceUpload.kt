package conectate.com.util

import android.content.Context
import android.net.Uri
import android.util.Log
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.supabase.StorageService
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val REPORT_EVIDENCE_BUCKET = "report-evidence"
private const val MAX_REPORT_EVIDENCE_BYTES = 10L * 1024L * 1024L
private val allowedReportEvidenceTypes = setOf("image/jpeg", "image/png", "image/webp")

data class UploadedReportEvidence(
    val publicUrl: String,
    val objectKey: String
)

suspend fun uploadReportEvidence(
    context: Context,
    storageService: StorageService,
    userId: String,
    sourceUri: Uri
): Result<UploadedReportEvidence> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val safeUserId = userId.trim().lowercase()
        .replace(Regex("[^a-z0-9._-]"), "_")
        .take(128)
    if (safeUserId.isBlank()) {
        return@withContext Result.failure(IllegalArgumentException("Usuario inválido para la evidencia."))
    }

    var uploadedKey: String? = null
    try {
        val header = resolver.openInputStream(sourceUri)?.use { stream ->
            val buffer = ByteArray(512)
            val count = stream.read(buffer)
            if (count > 0) buffer.copyOf(count) else ByteArray(0)
        } ?: throw IllegalArgumentException("No se pudo leer la evidencia seleccionada.")
        val contentType = canonicalizeMediaContentType(inferMimeTypeFromBytes(header))
            ?.takeIf { it in allowedReportEvidenceTypes }
            ?: throw IllegalArgumentException("La evidencia debe ser una imagen JPG, PNG o WebP válida.")
        val extension = preferredExtensionForMime(contentType)
            ?: throw IllegalArgumentException("Formato de evidencia no compatible.")

        val knownSize = runCatching {
            resolver.openAssetFileDescriptor(sourceUri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
            }
        }.getOrNull()
        if (knownSize != null && knownSize > MAX_REPORT_EVIDENCE_BYTES) {
            throw IllegalArgumentException("La evidencia supera el límite de 10 MB.")
        }

        val requestedPath = "reports/$safeUserId/${UUID.randomUUID()}.$extension"
        val uploadResult = if (knownSize != null) {
            storageService.uploadFileFromUri(
                bucketName = REPORT_EVIDENCE_BUCKET,
                fileName = requestedPath,
                sourceUri = sourceUri,
                sizeBytes = knownSize,
                contentType = contentType,
                allowOverwrite = false,
                onProgress = {}
            )
        } else {
            val bytes = readBytesBounded(context, sourceUri, MAX_REPORT_EVIDENCE_BYTES)
            storageService.uploadFile(
                bucketName = REPORT_EVIDENCE_BUCKET,
                fileName = requestedPath,
                bytes = bytes,
                contentType = contentType,
                allowOverwrite = false
            )
        }
        val uploaded = uploadResult.getOrElse { throw it }
        uploadedKey = uploaded.Key ?: requestedPath
        val publicUrl = UbuntuStorageUrl.rewrite(
            storageService.getPublicUrl(REPORT_EVIDENCE_BUCKET, uploadedKey)
        ).orEmpty()
        if (publicUrl.isBlank()) throw IllegalStateException("No se pudo generar la URL de la evidencia.")

        Result.success(UploadedReportEvidence(publicUrl = publicUrl, objectKey = uploadedKey))
    } catch (error: Throwable) {
        uploadedKey?.let { key ->
            storageService.deleteFile(REPORT_EVIDENCE_BUCKET, key).onFailure { cleanupError ->
                Log.w("ReportEvidenceUpload", "Could not remove incomplete evidence upload", cleanupError)
            }
        }
        Result.failure(error)
    }
}

private fun readBytesBounded(context: Context, sourceUri: Uri, maxBytes: Long): ByteArray {
    val stream = context.contentResolver.openInputStream(sourceUri)
        ?: throw IllegalArgumentException("No se pudo abrir la evidencia seleccionada.")
    return stream.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw IllegalArgumentException("La evidencia supera el límite de 10 MB.")
            output.write(buffer, 0, count)
        }
        output.toByteArray().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("La evidencia está vacía.")
    }
}
