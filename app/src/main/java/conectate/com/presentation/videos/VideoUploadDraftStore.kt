package conectate.com.presentation.videos

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import conectate.com.data.model.VideoChapter

@Serializable
data class VideoUploadDraft(
    val id: String,
    val title: String = "",
    val description: String = "",
    val tags: String = "",
    val isPublic: Boolean = true,
    val allowClipSharing: Boolean = true,
    val isSensitive: Boolean = false,
    val hasVideo: Boolean = false,
    val hasThumbnail: Boolean = false,
    val videoDurationMs: Long = 0L,
    val requiresEditing: Boolean = false,
    val soundId: String? = null,
    val soundSourceVideoId: String? = null,
    val soundTitle: String? = null,
    val soundArtist: String? = null,
    val soundUrl: String? = null,
    val soundCompositionReady: Boolean = true,
    val chapters: List<VideoChapter> = emptyList(),
    val savedAtMs: Long = 0L,
)

@Singleton
class VideoUploadDraftStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private fun accountFolder(accountId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(accountId.trim().lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "video_upload_drafts/$digest")
    }

    private fun draftFolder(accountId: String, draftId: String): File {
        require(idPattern.matches(draftId)) { "Identificador de borrador inválido" }
        return File(accountFolder(accountId), draftId)
    }

    suspend fun list(accountId: String): List<VideoUploadDraft> = withContext(Dispatchers.IO) {
        accountFolder(accountId).listFiles().orEmpty().asSequence()
            .filter { it.isDirectory && idPattern.matches(it.name) }
            .mapNotNull { folder -> readMetadata(folder) }
            .sortedByDescending(VideoUploadDraft::savedAtMs)
            .toList()
    }

    suspend fun load(accountId: String, draftId: String): VideoUploadDraft? = withContext(Dispatchers.IO) {
        readMetadata(draftFolder(accountId, draftId))
    }

    suspend fun save(accountId: String, draft: VideoUploadDraft,
                     videoUri: Uri?, thumbnailUri: Uri?): VideoUploadDraft = withContext(Dispatchers.IO) {
        val folder = draftFolder(accountId, draft.id)
        check(folder.isDirectory || folder.mkdirs()) { "No se pudo crear la carpeta del borrador." }
        val videoFile = File(folder, "source.mp4")
        val thumbFile = File(folder, "cover.jpg")
        if (videoUri != null) copyMediaIfNeeded(videoUri, videoFile, 200L * 1024L * 1024L)
        if (thumbnailUri != null) copyMediaIfNeeded(thumbnailUri, thumbFile, 20L * 1024L * 1024L)
        val saved = draft.copy(
            hasVideo = videoUri != null && videoFile.isFile,
            hasThumbnail = thumbnailUri != null && thumbFile.isFile,
            savedAtMs = System.currentTimeMillis(),
        )
        val target = AtomicFile(File(folder, "draft.json"))
        val bytes = json.encodeToString(saved).toByteArray(Charsets.UTF_8)
        val output = target.startWrite()
        try {
            output.write(bytes)
            target.finishWrite(output)
        } catch (error: Exception) {
            target.failWrite(output)
            throw error
        }
        saved
    }

    suspend fun videoUri(accountId: String, draftId: String): Uri? = withContext(Dispatchers.IO) {
        File(draftFolder(accountId, draftId), "source.mp4").takeIf(File::isFile)?.let(Uri::fromFile)
    }

    suspend fun thumbnailUri(accountId: String, draftId: String): Uri? = withContext(Dispatchers.IO) {
        File(draftFolder(accountId, draftId), "cover.jpg").takeIf(File::isFile)?.let(Uri::fromFile)
    }

    suspend fun delete(accountId: String, draftId: String): Boolean = withContext(Dispatchers.IO) {
        val folder = draftFolder(accountId, draftId)
        !folder.exists() || folder.deleteRecursively()
    }

    private fun readMetadata(folder: File): VideoUploadDraft? {
        val file = File(folder, "draft.json")
        if (!file.isFile || file.length() !in 1L..64_000L) return null
        return runCatching {
            json.decodeFromString<VideoUploadDraft>(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        }.getOrNull()?.takeIf { it.id == folder.name }
    }

    private fun copyMediaIfNeeded(source: Uri, destination: File, maxBytes: Long) {
        if (source.scheme == "file" && source.path == destination.absolutePath && destination.isFile) return
        val temporary = File(destination.parentFile, "${destination.name}.copying")
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: throw IllegalStateException("No se pudo abrir el archivo del borrador.")
            input.use { stream ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        copied += count
                        check(copied <= maxBytes) { "El archivo supera el límite del borrador." }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(temporary.renameTo(destination)) { "No se pudo conservar el archivo del borrador." }
        } finally {
            temporary.delete()
        }
    }
}
