package conectate.com.data.repository.collection

import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.supabase.StorageService
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
            return Result.success(UbuntuStorageUrl.rewrite(storageService.getPublicUrl(primaryBucket, fileName)).orEmpty())
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
            Result.success(UbuntuStorageUrl.rewrite(storageService.getPublicUrl(fallbackBucket, fileName)).orEmpty())
        } else {
            Result.failure(fallback.exceptionOrNull() ?: primary.exceptionOrNull() ?: Exception("Upload failed"))
        }
    }
}



