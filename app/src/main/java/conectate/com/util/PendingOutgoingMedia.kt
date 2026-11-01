package conectate.com.util

import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal const val MAX_INCOMING_SHARE_ITEMS = 20

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
        get() = !text.isNullOrBlank() || !subject.isNullOrBlank() || items.isNotEmpty()

    val isTextOnly: Boolean
        get() = items.isEmpty() && (!text.isNullOrBlank() || !subject.isNullOrBlank())
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
    private const val MIN_FREE_BYTES = 32L * 1024L * 1024L
    private const val INCOMING_TTL_MS = 48L * 60L * 60L * 1000L
    // Bounded stripes serialize only colliding file keys; uploads never rewrite a durable source.
    private val writeLocks = Array(64) { Mutex() }
    private fun writeLock(key: String) = writeLocks[key.hashCode() and (writeLocks.size - 1)]


    suspend fun persist(
        context: Context,
        accountId: String,
        namespace: String,
        messageId: String,
        sourceUri: Uri,
        preferredMediaType: String,
        requireCurrent: () -> Unit = {},
    ): Result<PendingOutgoingMedia> = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "$ROOT_DIRECTORY/${safePart(accountId)}/${safePart(namespace)}")
        val baseName = safePart(messageId).ifBlank { UUID.randomUUID().toString() }
        writeLock("${directory.absolutePath}/$baseName").withLock {
            try {
                currentCoroutineContext().ensureActive(); requireCurrent()
                // A retry may arrive after its source permission expired. Its durable copy is authoritative.
                val existing = directory.listFiles()?.firstOrNull {
                    it.isFile && it.name.startsWith("$baseName.") && !it.name.endsWith(".part") && it.length() > 0L
                }
                if (existing != null) {
                    val mime = guessMimeTypeFromFileName(existing.name) ?: "application/octet-stream"
                    return@withLock Result.success(PendingOutgoingMedia(Uri.fromFile(existing), existing.length(), mime))
                }
                require(directory.isDirectory || directory.mkdirs()) { "No se pudo preparar el archivo" }
                val header = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    val buffer = ByteArray(512)
                    val read = input.read(buffer)
                    if (read > 0) buffer.copyOf(read) else null
                } ?: error("No se pudo leer el archivo")
                currentCoroutineContext().ensureActive(); requireCurrent()
                val meta = resolveMediaMeta(preferredMediaType, context.contentResolver.getType(sourceUri),
                    guessMimeTypeFromUri(sourceUri), inferMimeTypeFromBytes(header))
                val finalFile = File(directory, "$baseName${meta.extensionWithDot}")
                val partialFile = File(directory, "${finalFile.name}.${UUID.randomUUID()}.part")
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
                                currentCoroutineContext().ensureActive(); requireCurrent()
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
                    currentCoroutineContext().ensureActive(); requireCurrent()
                    check(partialFile.renameTo(finalFile)) { "No se pudo conservar el archivo. Intenta otra vez." }
                    Result.success(PendingOutgoingMedia(Uri.fromFile(finalFile), copied, meta.mimeType))
                } finally {
                    partialFile.delete() // This attempt only owns its unique unfinished copy.
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
        }
    }

    suspend fun persistIncomingShare(
        context: Context,
        payload: IncomingSharePayload
    ): Result<IncomingSharePayload> = withContext(Dispatchers.IO) {
        writeLock("incoming:${safePart(payload.id)}").withLock {
          try {
            currentCoroutineContext().ensureActive()
            cleanupExpiredIncomingShares(context)
            val sourceItems = payload.items.take(MAX_INCOMING_SHARE_ITEMS)
            val directory = incomingShareDirectory(context, payload.id).apply { mkdirs() }
            require(directory.isDirectory) { "No se pudo preparar el almacenamiento temporal" }

            var totalBytes = 0L
            var failures = payload.failedItemCount + (payload.items.size - sourceItems.size).coerceAtLeast(0)
            var firstError = payload.captureError
            val copiedItems = sourceItems.mapIndexedNotNull { index, item ->
                currentCoroutineContext().ensureActive()
                val alreadyPersisted = resolveIncoming(context, item.uri)?.takeIf {
                    it.parentFile?.canonicalFile == directory.canonicalFile &&
                        it.length() in 1..MAX_BYTES && totalBytes + it.length() <= MAX_INCOMING_TOTAL_BYTES
                }
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
                        guessedMime = guessMimeTypeFromFileName(item.displayName)
                            ?: guessMimeTypeFromUri(item.uri),
                        inferredMime = inferMimeTypeFromBytes(header),
                        fallbackExtension = fileExtensionFromName(item.displayName) ?: "bin"
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
                    val partialFile = File(directory, "${finalFile.name}.${UUID.randomUUID()}.part")

                    var copied = 0L
                    try {
                        context.contentResolver.openInputStream(item.uri)?.buffered()?.use { input ->
                            partialFile.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
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
                        currentCoroutineContext().ensureActive()
                        check(partialFile.renameTo(finalFile)) { "No se pudo conservar el archivo compartido. Intenta otra vez." }
                    } finally {
                        partialFile.delete()
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
                    if (error is CancellationException) throw error
                    failures += 1
                    if (firstError.isNullOrBlank()) {
                        firstError = error.message ?: "No se pudo conservar un archivo compartido"
                    }
                    null
                }
            }

            currentCoroutineContext().ensureActive()
            Result.success(payload.copy(items = copiedItems, failedItemCount = failures, captureError = firstError))
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { Result.failure(error) }
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
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length > 0L) return descriptor.length
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Providers may legitimately omit their length. */ }
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
        .trim('.')

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
