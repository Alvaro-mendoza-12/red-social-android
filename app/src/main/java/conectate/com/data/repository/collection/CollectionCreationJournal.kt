package conectate.com.data.repository.collection

import android.content.Context
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The exact command saved BEFORE a creation request can reach the server. No credentials or image bytes. */
@Serializable
data class CollectionCreationRequest(
    val id: String,
    val accountId: String,
    val kind: String,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val category: String? = null,
    val isPublic: Boolean = false,
    val memberIds: List<String> = emptyList(),
    val createdAt: String,
) {
    internal fun validate(owner: String, expectedKind: String) {
        require(accountId.equals(owner, true) && kind == expectedKind)
        require(UUID.fromString(id).toString().equals(id, true))
        require(kind in setOf("group", "community"))
        requireCollectionCreationDetails(name, description, category)
        require(memberIds.all { it.isNotBlank() } && memberIds.distinct().size == memberIds.size)
        java.time.Instant.parse(createdAt)
    }
}

internal fun requireCollectionCreationDetails(name: String, description: String?, category: String? = null) {
    require(name.isNotBlank() && name.length <= 120) { "El nombre debe tener entre 1 y 120 caracteres." }
    require(description.orEmpty().length <= 500) { "La descripción admite hasta 500 caracteres." }
    require(category.orEmpty().length <= 80) { "La categoría admite hasta 80 caracteres." }
}

/** Only server rejections known to happen before any room/membership write may discard an intent. */
internal class CollectionCreationRejectedException(message: String) : IllegalStateException(message)

/** One unconfirmed creation per account and kind; survives screen/process exits, never device backup. */
@Singleton
class CollectionCreationJournal internal constructor(private val directory: File) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(File(context.noBackupFilesDir, "collection-creations-v1"))

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun pending(owner: String, kind: String): CollectionCreationRequest? = withContext(Dispatchers.IO) {
        val file = file(owner, kind)
        mutex(file).withLock { read(file, owner, kind) }
    }

    /**
     * A lost acknowledgement, cancellation, or local save failure keeps the same command.
     * Preparing/uploading may fail before a command exists; that leaves the draft editable.
     */
    internal suspend fun execute(
        owner: String,
        kind: String,
        requireCurrent: () -> Unit,
        prepare: suspend () -> CollectionCreationRequest,
        sendAndSave: suspend (CollectionCreationRequest) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val file = file(owner, kind)
        mutex(file).withLock {
            requireCurrent()
            val request = read(file, owner, kind) ?: prepare().also {
                requireCurrent()
                it.validate(owner, kind)
                write(file, it)
            }
            requireCurrent()
            try {
                sendAndSave(request)
            } catch (rejected: CollectionCreationRejectedException) {
                requireCurrent()
                file.delete()
                throw rejected
            }
            requireCurrent()
            file.delete()
            check(!file.baseFile.exists()) { "No pudimos cerrar la creación pendiente. Reintenta." }
            request.id
        }
    }

    private fun file(owner: String, kind: String): AtomicFile {
        require(owner.isNotBlank() && kind in setOf("group", "community"))
        val hash = MessageDigest.getInstance("SHA-256")
            .digest((owner.trim().lowercase() + "\u0000" + kind).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$hash.json"))
    }

    private fun read(file: AtomicFile, owner: String, kind: String): CollectionCreationRequest? {
        val bytes = try { file.readFully() } catch (missing: FileNotFoundException) {
            // Do not replace an existing but unreadable intent with a new ID.
            if (file.baseFile.exists()) throw missing
            return null
        }
        check(bytes.size <= 1024 * 1024) { "No pudimos recuperar la creación pendiente." }
        return json.decodeFromString<CollectionCreationRequest>(bytes.toString(Charsets.UTF_8))
            .also { it.validate(owner, kind) }
    }

    private fun write(file: AtomicFile, request: CollectionCreationRequest) {
        check(directory.isDirectory || directory.mkdirs()) { "No pudimos guardar la creación. Reintenta." }
        val bytes = json.encodeToString(request).toByteArray(Charsets.UTF_8)
        check(bytes.size <= 1024 * 1024) { "La selección de participantes es demasiado grande." }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
            check(file.readFully().contentEquals(bytes)) { "No pudimos guardar la creación. Reintenta." }
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        // AtomicFile requires caller-side exclusion, including separately constructed store instances.
        val locks = ConcurrentHashMap<String, Mutex>()
        fun mutex(file: AtomicFile): Mutex = locks.getOrPut(file.baseFile.absolutePath) { Mutex() }
    }
}
