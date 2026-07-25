package conectate.com.util

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PendingOutgoingMedia(
    val uri: Uri,
    val sizeBytes: Long,
    val mimeType: String
)

enum class IncomingShareKind {
    IMAGE,
    GIF,
    STICKER,
    VIDEO,
    AUDIO,
    DOCUMENT
}

data class IncomingSharedItem(
    val uri: Uri,
    val mimeType: String,
    val displayName: String? = null,
    val sizeBytes: Long = 0L,
    val kind: IncomingShareKind = classifyIncomingShareKind(mimeType, displayName)
) {
    val messageContentType: String
        get() = when (kind) {
            IncomingShareKind.IMAGE -> "image"
            IncomingShareKind.GIF -> "gif"
            IncomingShareKind.STICKER -> "sticker"
            IncomingShareKind.VIDEO -> "video"
            IncomingShareKind.AUDIO -> "audio"
            IncomingShareKind.DOCUMENT -> "document"
        }
}

data class IncomingSharePayload(
    val id: String = UUID.randomUUID().toString(),
    val text: String? = null,
    val subject: String? = null,
    val items: List<IncomingSharedItem> = emptyList(),
    val receivedAtMs: Long = System.currentTimeMillis(),
    val failedItemCount: Int = 0,
    val captureError: String? = null
) {
    val hasContent: Boolean
        get() = !text.isNullOrBlank() || items.isNotEmpty()

    val isTextOnly: Boolean
        get() = items.isEmpty() && !text.isNullOrBlank()
}

fun classifyIncomingShareKind(mimeType: String?, displayName: String?): IncomingShareKind {
    val mime = canonicalizeMediaContentType(mimeType).orEmpty()
    val name = displayName.orEmpty().substringBefore('?').substringBefore('#').lowercase()
    return when {
        mime == "image/gif" || name.endsWith(".gif") -> IncomingShareKind.GIF
        mime == "image/webp" || name.endsWith(".webp") -> IncomingShareKind.STICKER
        mime.startsWith("image/") -> IncomingShareKind.IMAGE
        mime.startsWith("video/") -> IncomingShareKind.VIDEO
        mime.startsWith("audio/") -> IncomingShareKind.AUDIO
        else -> IncomingShareKind.DOCUMENT
    }
}

object PendingOutgoingMediaStore {
    private const val ROOT_DIRECTORY = "pending-collection-media"
    private const val INCOMING_ROOT_DIRECTORY = "pending-incoming-share"
    private const val MAX_BYTES = 100L * 1024L * 1024L
    private const val MAX_INCOMING_TOTAL_BYTES = 300L * 1024L * 1024L
    private const val MAX_INCOMING_ITEMS = 20
    private const val MIN_FREE_BYTES = 32L * 1024L * 1024L
    private const val INCOMING_TTL_MS = 48L * 60L * 60L * 1000L

    suspend fun persist(
        context: Context,
        accountId: String,
        namespace: String,
        messageId: String,
        sourceUri: Uri,
        preferredMediaType: String
    ): Result<PendingOutgoingMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val header = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                val buffer = ByteArray(512)
                val read = input.read(buffer)
                if (read > 0) buffer.copyOf(read) else null
            } ?: error("No se pudo leer el archivo")
            val meta = resolveMediaMeta(
                preferredMediaType = preferredMediaType,
                declaredMime = context.contentResolver.getType(sourceUri),
                guessedMime = guessMimeTypeFromUri(sourceUri),
                inferredMime = inferMimeTypeFromBytes(header)
            )
            val directory = File(
                context.filesDir,
                "$ROOT_DIRECTORY/${safePart(accountId)}/${safePart(namespace)}"
            ).apply { mkdirs() }
            val baseName = safePart(messageId).ifBlank { UUID.randomUUID().toString() }
            val finalFile = File(directory, "$baseName${meta.extensionWithDot}")
            val partialFile = File(directory, "$baseName${meta.extensionWithDot}.part")
            partialFile.delete()

            val declaredSize = querySize(context, sourceUri)
            require(declaredSize <= 0L || declaredSize <= MAX_BYTES) { "El archivo supera el límite de 100 MB" }
            require(declaredSize <= 0L || StatFs(directory.absolutePath).availableBytes >= declaredSize + MIN_FREE_BYTES) {
                "No hay espacio suficiente para guardar el mensaje"
            }

            var copied = 0L
            try {
                context.contentResolver.openInputStream(sourceUri)?.buffered()?.use { input ->
                    partialFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            copied += read
                            require(copied <= MAX_BYTES) { "El archivo supera el límite de 100 MB" }
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                } ?: error("No se pudo abrir el archivo")
                require(copied > 0L) { "El archivo está vacío" }
                if (!partialFile.renameTo(finalFile)) {
                    partialFile.copyTo(finalFile, overwrite = true)
                    partialFile.delete()
                }
                PendingOutgoingMedia(Uri.fromFile(finalFile), copied, meta.mimeType)
            } catch (error: Exception) {
                partialFile.delete()
                finalFile.delete()
                throw error
            }
        }
    }

    suspend fun persistIncomingShare(
        context: Context,
        payload: IncomingSharePayload
    ): Result<IncomingSharePayload> = withContext(Dispatchers.IO) {
        runCatching {
            cleanupExpiredIncomingShares(context)
            val sourceItems = payload.items.take(MAX_INCOMING_ITEMS)
            val directory = incomingShareDirectory(context, payload.id).apply { mkdirs() }
            require(directory.isDirectory) { "No se pudo preparar el almacenamiento temporal" }

            var totalBytes = 0L
            var failures = payload.failedItemCount + (payload.items.size - sourceItems.size).coerceAtLeast(0)
            var firstError = payload.captureError
            val copiedItems = sourceItems.mapIndexedNotNull { index, item ->
                val alreadyPersisted = resolveIncoming(context, item.uri)
                if (alreadyPersisted != null) {
                    totalBytes += alreadyPersisted.length()
                    return@mapIndexedNotNull item.copy(
                        uri = Uri.fromFile(alreadyPersisted),
                        sizeBytes = alreadyPersisted.length()
                    )
                }

                runCatching {
                    val header = context.contentResolver.openInputStream(item.uri)?.use { input ->
                        val buffer = ByteArray(512)
                        val read = input.read(buffer)
                        if (read > 0) buffer.copyOf(read) else null
                    } ?: error("No se pudo leer el archivo compartido")
                    val resolved = resolveMediaMeta(
                        preferredMediaType = item.kind.preferredMediaType,
                        declaredMime = item.mimeType,
                        guessedMime = guessMimeTypeFromUri(item.uri),
                        inferredMime = inferMimeTypeFromBytes(header)
                    )
                    val declaredSize = querySize(context, item.uri)
                    require(declaredSize <= 0L || declaredSize <= MAX_BYTES) {
                        "Un archivo supera el límite de 100 MB"
                    }
                    require(declaredSize <= 0L || totalBytes + declaredSize <= MAX_INCOMING_TOTAL_BYTES) {
                        "La selección supera el límite temporal de 300 MB"
                    }
                    require(
                        declaredSize <= 0L ||
                            StatFs(directory.absolutePath).availableBytes >= declaredSize + MIN_FREE_BYTES
                    ) { "No hay espacio suficiente para guardar lo compartido" }

                    val cleanBaseName = item.displayName
                        ?.let { name -> name.substringBeforeLast('.', missingDelimiterValue = name) }
                        ?.let(::safePart)
                        ?.takeIf { it.isNotBlank() }
                        ?: "archivo"
                    val finalFile = File(directory, "${index}_${cleanBaseName}${resolved.extensionWithDot}")
                    val partialFile = File(directory, "${finalFile.name}.part")
                    partialFile.delete()

                    var copied = 0L
                    try {
                        context.contentResolver.openInputStream(item.uri)?.buffered()?.use { input ->
                            partialFile.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    copied += read
                                    require(copied <= MAX_BYTES) { "Un archivo supera el límite de 100 MB" }
                                    require(totalBytes + copied <= MAX_INCOMING_TOTAL_BYTES) {
                                        "La selección supera el límite temporal de 300 MB"
                                    }
                                    output.write(buffer, 0, read)
                                }
                                output.flush()
                            }
                        } ?: error("No se pudo abrir el archivo compartido")
                        require(copied > 0L) { "El archivo compartido está vacío" }
                        if (!partialFile.renameTo(finalFile)) {
                            partialFile.copyTo(finalFile, overwrite = true)
                            partialFile.delete()
                        }
                    } catch (error: Exception) {
                        partialFile.delete()
                        finalFile.delete()
                        throw error
                    }

                    totalBytes += copied
                    val finalKind = classifyIncomingShareKind(resolved.mimeType, finalFile.name)
                    IncomingSharedItem(
                        uri = Uri.fromFile(finalFile),
                        mimeType = resolved.mimeType,
                        displayName = item.displayName ?: finalFile.name,
                        sizeBytes = copied,
                        kind = finalKind
                    )
                }.getOrElse { error ->
                    failures += 1
                    if (firstError.isNullOrBlank()) {
                        firstError = error.message ?: "No se pudo conservar un archivo compartido"
                    }
                    null
                }
            }

            payload.copy(
                items = copiedItems,
                failedItemCount = failures,
                captureError = firstError
            )
        }
    }

    fun resolve(context: Context, value: String?): Uri? {
        val uri = value?.trim()?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val path = uri.path ?: return null
        val root = File(context.filesDir, ROOT_DIRECTORY).canonicalFile
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return if (file.isFile && file.path.startsWith(root.path + File.separator)) Uri.fromFile(file) else null
    }

    fun delete(context: Context, uri: Uri) {
        resolve(context, uri.toString())?.path?.let { path -> runCatching { File(path).delete() } }
    }

    fun deleteIncomingShare(context: Context, payload: IncomingSharePayload) {
        val directory = incomingShareDirectory(context, payload.id)
        val root = File(context.filesDir, INCOMING_ROOT_DIRECTORY).canonicalFile
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return
        if (canonical.path.startsWith(root.path + File.separator)) {
            runCatching { canonical.deleteRecursively() }
        }
    }

    fun cleanupExpiredIncomingShares(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val root = File(context.filesDir, INCOMING_ROOT_DIRECTORY)
        root.listFiles()?.filter { it.isDirectory }?.forEach { directory ->
            val newestTimestamp = directory.walkTopDown()
                .filter { it.isFile }
                .maxOfOrNull { it.lastModified() }
                ?: directory.lastModified()
            if (newestTimestamp <= 0L || nowMs - newestTimestamp > INCOMING_TTL_MS) {
                runCatching { directory.deleteRecursively() }
            }
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) return descriptor.length
            }
        }
        return 0L
    }

    private fun resolveIncoming(context: Context, uri: Uri): File? {
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val path = uri.path ?: return null
        val root = File(context.filesDir, INCOMING_ROOT_DIRECTORY).canonicalFile
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
    }

    private fun incomingShareDirectory(context: Context, payloadId: String): File = File(
        context.filesDir,
        "$INCOMING_ROOT_DIRECTORY/${safePart(payloadId).ifBlank { UUID.randomUUID().toString() }}"
    )

    private fun safePart(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9._-]"), "_")
        .take(100)

    private val IncomingShareKind.preferredMediaType: String
        get() = when (this) {
            IncomingShareKind.IMAGE -> "image"
            IncomingShareKind.GIF -> "gif"
            IncomingShareKind.STICKER -> "sticker"
            IncomingShareKind.VIDEO -> "video"
            IncomingShareKind.AUDIO -> "audio"
            IncomingShareKind.DOCUMENT -> "document"
        }
}
