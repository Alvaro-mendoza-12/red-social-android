package conectate.com.presentation.messages

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import conectate.com.util.IncomingShareKind
import conectate.com.util.IncomingSharedItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal class ShareBatchOwnerMismatch : IllegalStateException("Este compartido pertenece a otra cuenta. Cierra y vuelve a compartirlo con la cuenta actual.")

internal data class SavedShareBatch(
    val plan: List<ShareBatchItem>,
    val receipts: Map<String, ShareDestination>,
    val closed: Boolean,
)

/** Private exact command, written before dispatch. Closed records retain no content or recipients. */
@Singleton
internal class ShareBatchJournal internal constructor(private val directory: File) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.noBackupFilesDir, "share-batches-v1"))
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(id: String, owner: String, requireCurrent: () -> Unit): SavedShareBatch? =
        locked(id, requireCurrent) { file ->
            val record = read(file, id, owner)
            if (record == null) requireAbsentIndex(id, owner)
            record?.toBatch()
        }

    suspend fun prepare(id: String, owner: String, proposed: List<ShareBatchItem>, requireCurrent: () -> Unit): SavedShareBatch =
        locked(id, requireCurrent) { file ->
            val existing = read(file, id, owner)
            if (existing == null) requireAbsentIndex(id, owner)
            val record = existing ?: Record(id, owner, preserveAttachments(file, proposed, requireCurrent).map(StoredItem::from))
                .also { write(file, it, requireCurrent) }
            index(id, owner, pending = !record.closed, requireCurrent)
            record.toBatch()
        }

    suspend fun accepted(id: String, owner: String, key: String, target: ShareDestination, requireCurrent: () -> Unit) {
        locked(id, requireCurrent) { file ->
            val original = checkNotNull(read(file, id, owner)) { "No se pudo recuperar el envío pendiente." }
            if (!original.closed) {
                check(original.items.any { it.toItem().key == key })
                val receipts = original.receipts + (key to target)
                val finished = receipts.size == original.items.size
                write(file, if (finished) Record(id, owner, closed = true)
                    else original.copy(receipts = receipts), requireCurrent)
                if (finished) {
                    deleteAttachments(file)
                    index(id, owner, pending = false, requireCurrent)
                }
            }
        }
    }

    suspend fun close(id: String, owner: String, requireCurrent: () -> Unit) {
        locked(id, requireCurrent) { file ->
            // A different account must not receive or overwrite the previous account's command.
            if (read(file, id, owner) == null) indexLock().withLock {
                readIndex()[id]?.let { if (it != owner) throw ShareBatchOwnerMismatch() }
            }
            write(file, Record(id, owner, closed = true), requireCurrent)
            deleteAttachments(file)
            index(id, owner, pending = false, requireCurrent)
        }
    }

    suspend fun pending(owner: String, requireCurrent: () -> Unit): List<String> = withContext(Dispatchers.IO) {
        indexLock().withLock {
            requireCurrent()
            readIndex().filterValues { it == owner }.keys.take(20).also { requireCurrent() }
        }
    }

    private fun indexFile() = AtomicFile(File(directory, "pending.index"))
    private fun indexLock() = indexLocks[(directory.absolutePath.hashCode() and Int.MAX_VALUE) % indexLocks.size]
    private fun readIndex(): Map<String, String> {
        val bytes = readBytes(indexFile()) ?: return emptyMap()
        return json.decodeFromString<Map<String, String>>(bytes.toString(Charsets.UTF_8)).also { entries ->
            check(entries.size <= 4096 && entries.all { (id, owner) -> id.isNotBlank() && id.length <= 128 && owner.isNotBlank() && owner.length <= 128 })
        }
    }

    private suspend fun index(id: String, owner: String, pending: Boolean, requireCurrent: () -> Unit) {
        indexLock().withLock {
            requireCurrent()
            val previous = readIndex()
            if (previous[id] != null && previous[id] != owner) throw ShareBatchOwnerMismatch()
            val next = if (pending) previous + (id to owner) else previous - id
            check(next.size <= 4096) { "Hay demasiados envíos pendientes. Continúa o cierra alguno antes de empezar otro." }
            if (next != previous) writeBytes(indexFile(), json.encodeToString(next).toByteArray(Charsets.UTF_8), requireCurrent)
        }
    }

    private suspend fun requireAbsentIndex(id: String, owner: String) {
        indexLock().withLock {
            readIndex()[id]?.let {
                if (it != owner) throw ShareBatchOwnerMismatch()
                error("No se pudo recuperar el contenido del envío pendiente.")
            }
        }
    }

    private fun attachmentDirectory(file: AtomicFile): File {
        val root = directory.canonicalFile
        val folder = File(root, file.baseFile.nameWithoutExtension + ".media").canonicalFile
        check(folder.parentFile == root) { "No se pudo conservar el archivo compartido." }
        return folder
    }

    private fun preserveAttachments(file: AtomicFile, plan: List<ShareBatchItem>, requireCurrent: () -> Unit): List<ShareBatchItem> {
        val retained = mutableMapOf<String, IncomingSharedItem>()
        return plan.map { row ->
            val content = row.content as? ShareOutgoingContent.Attachment ?: return@map row
            val key = content.item.uri.toString()
            val item = retained.getOrPut(key) {
                // The Activity already captured external providers into app-owned files. A batch
                // needs its own copy: later incoming intents may release that Activity capture.
                check(content.item.uri.scheme.equals("file", true)) { "Vuelve a adjuntar una copia del archivo." }
                val source = File(checkNotNull(content.item.uri.path)).canonicalFile
                val folder = attachmentDirectory(file)
                check(folder.isDirectory || folder.mkdirs())
                val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                val extension = source.extension.takeIf { it.length in 1..12 && it.all(Char::isLetterOrDigit) } ?: "bin"
                val destination = File(folder, "$hash.$extension")
                if (!destination.isFile || destination.length() !in 1..MAX_MEDIA_BYTES) {
                    val partial = File(folder, "$hash.${java.util.UUID.randomUUID()}.part")
                    try {
                        requireCurrent()
                        check(source.isFile && source.length() in 1..MAX_MEDIA_BYTES) { "No se pudo recuperar el archivo compartido." }
                        check(android.os.StatFs(folder.path).availableBytes >= source.length() + 20L * 1024 * 1024) { "No hay espacio suficiente para conservar el envío." }
                        source.inputStream().use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var copied = 0L
                            while (true) {
                                requireCurrent()
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                check(copied <= MAX_MEDIA_BYTES)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        } }
                        requireCurrent()
                        check(partial.length() > 0 && partial.renameTo(destination)) { "No se pudo conservar el archivo compartido." }
                    } finally { partial.delete() }
                }
                content.item.copy(uri = Uri.fromFile(destination), sizeBytes = destination.length())
            }
            row.copy(content = content.copy(item = item))
        }
    }

    private fun deleteAttachments(file: AtomicFile) {
        // Only this journal's hashed request folder, never the Activity capture or outbox files.
        runCatching { attachmentDirectory(file).deleteRecursively() }
            .onFailure { android.util.Log.w("ShareRecovery", "Cannot remove completed batch media", it) }
    }

    private suspend fun <T> locked(id: String, requireCurrent: () -> Unit, action: suspend (AtomicFile) -> T): T = withContext(Dispatchers.IO) {
        require(id.isNotBlank() && id.length <= 128)
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        val name = hash.joinToString("") { "%02x".format(it) }
        val file = AtomicFile(File(directory, "$name.json"))
        locks[(file.baseFile.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            requireCurrent()
            action(file).also { requireCurrent() }
        }
    }

    private fun read(file: AtomicFile, id: String, owner: String): Record? {
        require(owner.isNotBlank())
        val bytes = readBytes(file) ?: return null
        val record = json.decodeFromString<Record>(bytes.toString(Charsets.UTF_8))
        check(record.version == 1 && record.id == id)
        if (record.owner != owner) throw ShareBatchOwnerMismatch()
        record.validate()
        return record
    }

    private fun readBytes(file: AtomicFile): ByteArray? = try {
            file.openRead().use { input ->
                // Bound allocation before parsing; do not read an arbitrary oversized file first.
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    check(output.size() + count <= MAX_BYTES) { "No se pudo recuperar el envío pendiente." }
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
        } catch (missing: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw missing
            null
        }

    private fun write(file: AtomicFile, record: Record, requireCurrent: () -> Unit) {
        record.validate()
        val bytes = json.encodeToString(record).toByteArray(Charsets.UTF_8)
        writeBytes(file, bytes, requireCurrent)
    }

    private fun writeBytes(file: AtomicFile, bytes: ByteArray, requireCurrent: () -> Unit) {
        check(bytes.size <= MAX_BYTES) { "La selección es demasiado grande para conservarla. Reduce los envíos." }
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo conservar el envío. Revisa el espacio disponible." }
        requireCurrent()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            requireCurrent()
            file.finishWrite(stream)
            check(file.readFully().contentEquals(bytes)) { "No se pudo confirmar el guardado del envío." }
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    @Serializable private data class Record(
        val id: String, val owner: String, val items: List<StoredItem> = emptyList(),
        val receipts: Map<String, ShareDestination> = emptyMap(), val closed: Boolean = false, val version: Int = 1,
    ) {
        fun toBatch() = SavedShareBatch(items.map(StoredItem::toItem), receipts, closed)
        fun validate() {
            check(id.isNotBlank() && id.length <= 128 && owner.isNotBlank() && owner.length <= 128)
            if (closed) check(items.isEmpty() && receipts.isEmpty())
            else {
                check(items.size in 1..MAX_FORWARD_OPERATIONS)
                val keys = items.map { it.toItem().key }
                check(keys.distinct().size == keys.size && receipts.keys.all { it in keys })
                check(items.map { it.destination.key }.distinct().size <= MAX_FORWARD_DESTINATIONS)
                items.forEach { check(it.sourceKey.isNotBlank() && it.destination.id.isNotBlank()) }
            }
        }
    }

    @Serializable private data class StoredItem(
        val sourceKey: String, val destination: ShareDestination, val kind: String, val text: String = "",
        val uri: String? = null, val mime: String? = null, val name: String? = null, val size: Long = 0,
        val mediaKind: String? = null, val forward: ForwardMessagePayload? = null,
    ) {
        fun toItem(): ShareBatchItem = ShareBatchItem(sourceKey, destination, when (kind) {
            "text" -> ShareOutgoingContent.Text(text)
            "attachment" -> ShareOutgoingContent.Attachment(IncomingSharedItem(Uri.parse(checkNotNull(uri)),
                checkNotNull(mime), name, size, IncomingShareKind.valueOf(checkNotNull(mediaKind))), text)
            "forward" -> ShareOutgoingContent.Forward(checkNotNull(forward))
            else -> error("No se pudo recuperar el tipo de envío.")
        })
        companion object {
            fun from(item: ShareBatchItem): StoredItem = when (val content = item.content) {
                is ShareOutgoingContent.Text -> StoredItem(item.sourceKey, item.destination, "text", content.content)
                is ShareOutgoingContent.Forward -> StoredItem(item.sourceKey, item.destination, "forward", forward = content.source)
                is ShareOutgoingContent.Attachment -> StoredItem(item.sourceKey, item.destination, "attachment", content.content,
                    content.item.uri.toString(), content.item.mimeType, content.item.displayName, content.item.sizeBytes, content.item.kind.name)
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_MEDIA_BYTES = 100L * 1024 * 1024
        // AtomicFile itself supplies no locking. Bound the locks across all store instances.
        val locks = Array(64) { Mutex() }
        val indexLocks = Array(16) { Mutex() }
    }
}
