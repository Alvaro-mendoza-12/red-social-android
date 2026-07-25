package conectate.com.data.repository.collection

import android.content.Context
import android.net.Uri
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.supabase.StorageService
import conectate.com.util.guessMimeTypeFromUri
import conectate.com.util.inferMimeTypeFromBytes
import conectate.com.util.resolveMediaMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class CollectionMediaStore(
    private val storageService: StorageService
) {
    suspend fun uploadImage(
        ownerId: String,
        bytes: ByteArray,
        bucketName: String,
        filePrefix: String
    ): Result<String> = uploadFixedMime(
        bucketName = bucketName,
        fileName = "${filePrefix}_${ownerId}_${System.currentTimeMillis()}.jpg",
        bytes = bytes,
        contentType = "image/jpeg"
    )

    suspend fun uploadAudio(
        ownerId: String,
        bytes: ByteArray,
        primaryBucket: String,
        fallbackBucket: String? = null,
        filePrefix: String,
        extension: String,
        contentType: String
    ): Result<String> = uploadFixedMime(
        bucketName = primaryBucket,
        fallbackBucket = fallbackBucket,
        fileName = "${filePrefix}_${ownerId}_${System.currentTimeMillis()}.$extension",
        bytes = bytes,
        contentType = contentType
    )

    suspend fun uploadMessageMedia(
        ownerId: String,
        bytes: ByteArray,
        contentType: String,
        primaryBucket: String,
        fallbackBucket: String? = null,
        filePrefix: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val normalized = resolveMediaMeta(
                preferredMediaType = when {
                    contentType.startsWith("video/", ignoreCase = true) -> "video"
                    contentType.startsWith("audio/", ignoreCase = true) -> "audio"
                    contentType.equals("image/gif", ignoreCase = true) -> "gif"
                    contentType.startsWith("image/", ignoreCase = true) -> "image"
                    else -> "file"
                },
                declaredMime = contentType,
                guessedMime = null,
                inferredMime = inferMimeTypeFromBytes(bytes)
            )
            val extension = normalized.extensionWithDot.removePrefix(".")
            var fileName = "${filePrefix}_${ownerId}_${System.currentTimeMillis()}.$extension"
            uploadWithOptionalFallback(
                primaryBucket = primaryBucket,
                fallbackBucket = fallbackBucket,
                fileName = fileName,
                bytes = bytes,
                contentType = normalized.mimeType
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadMessageMediaFromUri(
        context: Context,
        ownerId: String,
        sourceUri: Uri,
        preferredMediaType: String,
        primaryBucket: String,
        fallbackBucket: String? = null,
        filePrefix: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val header = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val buffer = ByteArray(512)
                val read = input.read(buffer)
                if (read > 0) buffer.copyOf(read) else null
            } ?: return@withContext Result.failure(Exception("No se pudo leer el archivo pendiente"))
            val normalized = resolveMediaMeta(
                preferredMediaType = preferredMediaType,
                declaredMime = context.contentResolver.getType(sourceUri),
                guessedMime = guessMimeTypeFromUri(sourceUri),
                inferredMime = inferMimeTypeFromBytes(header)
            )
            val sizeBytes = queryUriSize(context, sourceUri)
            if (sizeBytes <= 0L) {
                return@withContext Result.failure(Exception("El archivo pendiente está vacío"))
            }
            val extension = normalized.extensionWithDot.removePrefix(".")
            val fileName = "${filePrefix}_${ownerId}_${System.currentTimeMillis()}.$extension"
            uploadUriWithOptionalFallback(
                primaryBucket = primaryBucket,
                fallbackBucket = fallbackBucket,
                fileName = fileName,
                sourceUri = sourceUri,
                sizeBytes = sizeBytes,
                contentType = normalized.mimeType
            )
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun uploadFixedMime(
        bucketName: String,
        fileName: String,
        bytes: ByteArray,
        contentType: String,
        fallbackBucket: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            uploadWithOptionalFallback(
                primaryBucket = bucketName,
                fallbackBucket = fallbackBucket,
                fileName = fileName,
                bytes = bytes,
                contentType = contentType
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun uploadWithOptionalFallback(
        primaryBucket: String,
        fallbackBucket: String?,
        fileName: String,
        bytes: ByteArray,
        contentType: String
    ): Result<String> {
        val primary = storageService.uploadFile(
            bucketName = primaryBucket,
            fileName = fileName,
            bytes = bytes,
            contentType = contentType
        )
        if (primary.isSuccess) {
            val remoteKey = primary.getOrThrow().Key ?: fileName
            return Result.success(UbuntuStorageUrl.rewrite(storageService.getPublicUrl(primaryBucket, remoteKey)).orEmpty())
        }

        if (fallbackBucket.isNullOrBlank() || fallbackBucket == primaryBucket) {
            return Result.failure(primary.exceptionOrNull() ?: Exception("Upload failed"))
        }

        val fallback = storageService.uploadFile(
            bucketName = fallbackBucket,
            fileName = fileName,
            bytes = bytes,
            contentType = contentType
        )
        return if (fallback.isSuccess) {
            val remoteKey = fallback.getOrThrow().Key ?: fileName
            Result.success(UbuntuStorageUrl.rewrite(storageService.getPublicUrl(fallbackBucket, remoteKey)).orEmpty())
        } else {
            Result.failure(fallback.exceptionOrNull() ?: primary.exceptionOrNull() ?: Exception("Upload failed"))
        }
    }

    private suspend fun uploadUriWithOptionalFallback(
        primaryBucket: String,
        fallbackBucket: String?,
        fileName: String,
        sourceUri: Uri,
        sizeBytes: Long,
        contentType: String
    ): Result<String> {
        val primary = storageService.uploadFileFromUri(
            bucketName = primaryBucket,
            fileName = fileName,
            sourceUri = sourceUri,
            sizeBytes = sizeBytes,
            contentType = contentType,
            allowOverwrite = true,
            onProgress = {}
        )
        if (primary.isSuccess) {
            val remoteKey = primary.getOrThrow().Key ?: fileName
            return Result.success(UbuntuStorageUrl.rewrite(storageService.getPublicUrl(primaryBucket, remoteKey)).orEmpty())
        }
        if (fallbackBucket.isNullOrBlank() || fallbackBucket == primaryBucket) {
            return Result.failure(primary.exceptionOrNull() ?: Exception("Upload failed"))
        }
        val fallback = storageService.uploadFileFromUri(
            bucketName = fallbackBucket,
            fileName = fileName,
            sourceUri = sourceUri,
            sizeBytes = sizeBytes,
            contentType = contentType,
            allowOverwrite = true,
            onProgress = {}
        )
        return if (fallback.isSuccess) {
            val remoteKey = fallback.getOrThrow().Key ?: fileName
            Result.success(UbuntuStorageUrl.rewrite(storageService.getPublicUrl(fallbackBucket, remoteKey)).orEmpty())
        } else {
            Result.failure(fallback.exceptionOrNull() ?: primary.exceptionOrNull() ?: Exception("Upload failed"))
        }
    }

    private fun queryUriSize(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) return descriptor.length
            }
        }
        return 0L
    }
}



