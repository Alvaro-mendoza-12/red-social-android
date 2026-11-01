package conectate.com.data.repository.collection

import android.content.Context
import android.net.Uri
import conectate.com.core.messaging.MessageUploadProgress
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.supabase.OwnedUploadSession
import conectate.com.data.supabase.StorageService
import conectate.com.util.guessMimeTypeFromUri
import conectate.com.util.inferMimeTypeFromBytes
import conectate.com.util.resolveMediaMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CollectionMediaStore(private val storageService: StorageService) {
    /** Freeze the actor before dispatch, reading metadata or choosing another bucket. */
    private suspend fun <T> withUploadOwner(
        expectedAccountId: String? = null,
        operation: suspend (OwnedUploadSession) -> Result<T>,
    ): Result<T> {
        val session = try { storageService.captureUploadSession(expectedAccountId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { return Result.failure(error) }
        return session.uploadWithFallback(primary = { withContext(Dispatchers.IO) { operation(session) } })
    }

    suspend fun uploadImage(
        ownerId: String, bytes: ByteArray, bucketName: String, filePrefix: String,
        expectedAccountId: String? = null,
    ): Result<String> = withUploadOwner(expectedAccountId) { session ->
        val icon = conectate.com.core.media.normalizeCollectionIcon(bytes)
        uploadBytes(session, bucketName, null, collectionMediaFileName(filePrefix, ownerId, icon.extension), icon.bytes, icon.contentType)
    }

    suspend fun uploadAudio(
        ownerId: String, bytes: ByteArray, primaryBucket: String, fallbackBucket: String? = null,
        filePrefix: String, extension: String, contentType: String,
        expectedAccountId: String? = null,
    ): Result<String> = withUploadOwner(expectedAccountId) { session ->
        uploadBytes(session, primaryBucket, fallbackBucket, collectionMediaFileName(filePrefix, ownerId, extension), bytes, contentType)
    }

    suspend fun uploadMessageMedia(
        ownerId: String, bytes: ByteArray, contentType: String, primaryBucket: String,
        fallbackBucket: String? = null, filePrefix: String, expectedAccountId: String? = null,
    ): Result<String> = withUploadOwner(expectedAccountId) { session ->
        val normalized = resolveMediaMeta(
            preferredMediaType = when {
                contentType.startsWith("video/", ignoreCase = true) -> "video"
                contentType.startsWith("audio/", ignoreCase = true) -> "audio"
                contentType.equals("image/gif", ignoreCase = true) -> "gif"
                contentType.startsWith("image/", ignoreCase = true) -> "image"
                else -> "file"
            },
            declaredMime = contentType, guessedMime = null, inferredMime = inferMimeTypeFromBytes(bytes),
        )
        val fileName = collectionMediaFileName(filePrefix, ownerId, normalized.extensionWithDot.removePrefix("."))
        uploadBytes(session, primaryBucket, fallbackBucket, fileName, bytes, normalized.mimeType)
    }

    suspend fun uploadMessageMediaFromUri(
        context: Context, ownerId: String, sourceUri: Uri, preferredMediaType: String,
        primaryBucket: String, fallbackBucket: String? = null, filePrefix: String,
        accountId: String = "", messageId: String = "",
    ): Result<String> = withUploadOwner(accountId.takeIf(String::isNotBlank)) { session ->
        val account = session.accountId
        MessageUploadProgress.update(account, messageId, 0f)
        try {
            val header = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val buffer = ByteArray(512)
                val read = input.read(buffer)
                if (read > 0) buffer.copyOf(read) else null
            } ?: return@withUploadOwner Result.failure(Exception("No se pudo leer el archivo pendiente."))
            val normalized = resolveMediaMeta(preferredMediaType, context.contentResolver.getType(sourceUri),
                guessMimeTypeFromUri(sourceUri), inferMimeTypeFromBytes(header))
            val sizeBytes = queryUriSize(context, sourceUri)
            if (sizeBytes <= 0L) return@withUploadOwner Result.failure(Exception("El archivo pendiente está vacío."))
            val fileName = collectionMediaFileName(filePrefix, ownerId, normalized.extensionWithDot.removePrefix("."))
            session.requireCurrent()
            suspend fun upload(bucket: String) = storageService.uploadFileFromUri(
                bucketName = bucket, fileName = fileName, sourceUri = sourceUri, sizeBytes = sizeBytes,
                contentType = normalized.mimeType, allowOverwrite = false, expectedAccountId = account,
                onProgress = { MessageUploadProgress.update(account, messageId, it) },
            ).map { response -> publicUrl(bucket, response.Key ?: fileName) }
            session.uploadWithFallback(primary = { upload(primaryBucket) },
                fallback = fallbackBucket?.takeUnless { it.isBlank() || it == primaryBucket }?.let { bucket ->
                    suspend { upload(bucket) }
                })
        } finally { MessageUploadProgress.finish(account, messageId) }
    }

    private suspend fun uploadBytes(
        session: OwnedUploadSession, primaryBucket: String, fallbackBucket: String?,
        fileName: String, bytes: ByteArray, contentType: String,
    ): Result<String> {
        suspend fun upload(bucket: String) = storageService.uploadFile(bucketName = bucket, fileName = fileName,
            bytes = bytes, contentType = contentType, allowOverwrite = false, expectedAccountId = session.accountId)
            .map { response -> publicUrl(bucket, response.Key ?: fileName) }
        return session.uploadWithFallback(primary = { upload(primaryBucket) },
            fallback = fallbackBucket?.takeUnless { it.isBlank() || it == primaryBucket }?.let { bucket ->
                suspend { upload(bucket) }
            })
    }

    private fun publicUrl(bucket: String, key: String): String =
        UbuntuStorageUrl.rewrite(storageService.getPublicUrl(bucket, key))?.takeIf(String::isNotBlank)
            ?: error("No pudimos obtener el enlace del archivo subido.")

    private fun queryUriSize(context: Context, uri: Uri): Long {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length > 0L) return descriptor.length
        }
        return 0L
    }
}
