package conectate.com.data.local

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PostDraft(
    val content: String,
    val mediaUris: List<Uri>,
    val visibility: String,
    val isSensitive: Boolean,
    val savedAt: Long,
)

/** One private, recoverable post draft per account. Media is copied before metadata is committed. */
object PostDraftStore {
    private const val MAX_IMAGES = 7
    private const val MAX_TOTAL_BYTES = 100L * 1024L * 1024L
    private val gate = Mutex()

    suspend fun load(context: Context, ownerId: String): Result<PostDraft?> =
        withContext(Dispatchers.IO) {
            runCatching { gate.withLock { loadLocked(context.applicationContext, ownerId) } }
        }

    suspend fun save(
        context: Context,
        ownerId: String,
        content: String,
        mediaUris: List<Uri>,
        visibility: String,
        isSensitive: Boolean,
    ): Result<PostDraft> = withContext(Dispatchers.IO) {
        runCatching {
            gate.withLock {
                require(mediaUris.size <= MAX_IMAGES) { "El borrador admite hasta $MAX_IMAGES imágenes." }
                require(visibility == "public" || visibility == "private") { "Audiencia no válida." }
                val root = root(context.applicationContext)
                val key = ownerKey(ownerId)
                val target = File(root, key)
                val backup = File(root, "$key.backup")
                recoverPreviousDraft(target, backup)
                val stage = File(root, "$key.tmp-${UUID.randomUUID()}")
                require(stage.mkdirs()) { "No se pudo preparar el borrador." }
                try {
                    var copiedBytes = 0L
                    val files = JSONArray()
                    mediaUris.forEachIndexed { index, uri ->
                        val extension = imageExtension(context, uri)
                        val fileName = "image-$index.$extension"
                        val destination = File(stage, fileName)
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw IllegalStateException("No se pudo leer la imagen ${index + 1}.")
                        input.use { source ->
                            destination.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    copiedBytes += count
                                    if (copiedBytes > MAX_TOTAL_BYTES) {
                                        throw IllegalArgumentException("El borrador supera los 100 MB.")
                                    }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        files.put(fileName)
                    }
                    val savedAt = System.currentTimeMillis()
                    File(stage, "draft.json").writeText(
                        JSONObject()
                            .put("content", content)
                            .put("visibility", visibility)
                            .put("isSensitive", isSensitive)
                            .put("savedAt", savedAt)
                            .put("images", files)
                            .toString(),
                    )
                    if (backup.exists()) backup.deleteRecursively()
                    if (target.exists() && !target.renameTo(backup)) {
                        throw IllegalStateException("No se pudo conservar la versión anterior del borrador.")
                    }
                    if (!stage.renameTo(target)) {
                        if (backup.exists()) backup.renameTo(target)
                        throw IllegalStateException("No se pudo guardar el borrador.")
                    }
                    if (backup.exists()) backup.deleteRecursively()
                    PostDraft(content, filesToUris(target, files), visibility, isSensitive, savedAt)
                } finally {
                    if (stage.exists()) stage.deleteRecursively()
                }
            }
        }
    }

    suspend fun delete(context: Context, ownerId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            gate.withLock {
                val root = root(context.applicationContext)
                val key = ownerKey(ownerId)
                listOf(File(root, key), File(root, "$key.backup")).forEach { directory ->
                    if (directory.exists() && !directory.deleteRecursively()) {
                        throw IllegalStateException("No se pudo eliminar el borrador.")
                    }
                }
            }
        }
    }

    private fun loadLocked(context: Context, ownerId: String): PostDraft? {
        val root = root(context)
        val key = ownerKey(ownerId)
        val target = File(root, key)
        val backup = File(root, "$key.backup")
        recoverPreviousDraft(target, backup)
        val metadata = File(target, "draft.json")
        if (!metadata.isFile) return null
        val json = JSONObject(metadata.readText())
        val files = json.optJSONArray("images") ?: JSONArray()
        require(files.length() <= MAX_IMAGES) { "El borrador tiene demasiadas imágenes." }
        val uris = filesToUris(target, files)
        require(uris.size == files.length()) { "Falta una imagen del borrador." }
        return PostDraft(
            content = json.optString("content"),
            mediaUris = uris,
            visibility = json.optString("visibility", "public").takeIf { it == "private" } ?: "public",
            isSensitive = json.optBoolean("isSensitive"),
            savedAt = json.optLong("savedAt"),
        )
    }

    private fun filesToUris(directory: File, names: JSONArray): List<Uri> =
        (0 until names.length()).map { index ->
            val name = names.getString(index)
            require(Regex("image-[0-6]\\.(jpg|jpeg|png|webp|gif|heic)").matches(name)) {
                "Imagen del borrador no válida."
            }
            val file = File(directory, name)
            require(file.isFile) { "Falta una imagen del borrador." }
            Uri.fromFile(file)
        }

    private fun imageExtension(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.lowercase()
        val fromPath = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        return listOf(fromMime, fromPath).firstOrNull {
            it in setOf("jpg", "jpeg", "png", "webp", "gif", "heic")
        } ?: "jpg"
    }

    private fun recoverPreviousDraft(target: File, backup: File) {
        if (!backup.exists() || File(target, "draft.json").isFile) return
        if (target.exists() && !target.deleteRecursively()) {
            throw IllegalStateException("No se pudo recuperar el borrador anterior.")
        }
        if (!backup.renameTo(target)) {
            throw IllegalStateException("No se pudo recuperar el borrador anterior.")
        }
    }

    private fun root(context: Context): File = File(context.filesDir, "post-drafts").apply { mkdirs() }

    private fun ownerKey(ownerId: String): String {
        val clean = ownerId.trim().lowercase()
        require(clean.isNotBlank()) { "Inicia sesión para guardar un borrador." }
        return MessageDigest.getInstance("SHA-256")
            .digest(clean.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(40)
    }
}
