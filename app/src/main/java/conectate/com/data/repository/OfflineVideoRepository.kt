package conectate.com.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.Video
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class OfflineVideoEntry(
    val video: Video,
    val localVideoPath: String,
    val localThumbnailPath: String? = null,
    val downloadedAtEpochMs: Long,
    val sizeBytes: Long
) {
    val localVideoUri: String
        get() = Uri.fromFile(File(localVideoPath)).toString()
}

@Singleton
class OfflineVideoRepository @Inject constructor(
    private val authService: AuthService,
    @param:ApplicationContext private val context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val writeMutex = Mutex()
    private val _videos = MutableStateFlow<List<OfflineVideoEntry>>(emptyList())
    val videos: StateFlow<List<OfflineVideoEntry>> = _videos.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

    init {
        refreshForCurrentAccount()
    }

    fun refreshForCurrentAccount() {
        val accountId = currentAccountId()
        _videos.value = if (accountId == null) emptyList() else loadAndPrune(accountId)
        _downloadProgress.value = emptyMap()
    }

    fun configuredTargetCount(): Int {
        val accountId = currentAccountId() ?: return 0
        return preferences.getInt(targetPreferenceKey(accountId), 0)
            .coerceIn(0, MAX_CONFIGURED_VIDEO_COUNT)
    }

    fun setConfiguredTargetCount(count: Int) {
        val accountId = currentAccountId() ?: return
        preferences.edit()
            .putInt(targetPreferenceKey(accountId), count.coerceIn(0, MAX_CONFIGURED_VIDEO_COUNT))
            .apply()
    }

    fun requestFreshCycle() {
        val accountId = currentAccountId() ?: return
        preferences.edit().putBoolean(refreshPreferenceKey(accountId), true).apply()
    }

    fun consumeFreshCycleRequest(): Boolean {
        val accountId = currentAccountId() ?: return false
        val requested = preferences.getBoolean(refreshPreferenceKey(accountId), false)
        if (requested) {
            preferences.edit().putBoolean(refreshPreferenceKey(accountId), false).apply()
        }
        return requested
    }

    suspend fun download(video: Video): Result<OfflineVideoEntry> = withContext(Dispatchers.IO) {
        val accountId = currentAccountId()
            ?: return@withContext Result.failure(IllegalStateException("No hay una cuenta activa"))
        val videoId = safePart(video.id)
        if (videoId.isBlank() || video.videoUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("El video no tiene una dirección válida"))
        }

        writeMutex.withLock {
            val current = loadAndPrune(accountId)
            current.firstOrNull { it.video.id.equals(video.id, ignoreCase = true) }
                ?.let { existing ->
                    _videos.value = current
                    return@withLock Result.success(existing)
                }

            val accountDirectory = accountDirectory(accountId).apply { mkdirs() }
            val finalVideo = File(accountDirectory, "$videoId.mp4")
            val partialVideo = File(accountDirectory, "$videoId.mp4.part")
            val finalThumbnail = File(accountDirectory, "$videoId.thumbnail")
            partialVideo.delete()

            try {
                val sourceUrl = UbuntuStorageUrl.rewrite(video.videoUrl) ?: video.videoUrl
                val sizeBytes = downloadToFile(
                    sourceUrl = sourceUrl,
                    destination = partialVideo,
                    maximumBytes = MAX_OFFLINE_VIDEO_BYTES,
                    onProgress = { progress -> updateProgress(video.id, progress) }
                )
                validatePlayableVideo(partialVideo)
                if (!partialVideo.renameTo(finalVideo)) {
                    partialVideo.copyTo(finalVideo, overwrite = true)
                    partialVideo.delete()
                }

                val thumbnailPath = video.thumbnailUrl.takeIf { it.isNotBlank() }?.let { thumbnailUrl ->
                    runCatching {
                        val resolved = UbuntuStorageUrl.rewrite(thumbnailUrl) ?: thumbnailUrl
                        downloadToFile(
                            sourceUrl = resolved,
                            destination = finalThumbnail,
                            maximumBytes = MAX_THUMBNAIL_BYTES,
                            onProgress = {}
                        )
                        finalThumbnail.absolutePath
                    }.getOrElse {
                        finalThumbnail.delete()
                        null
                    }
                }

                val entry = OfflineVideoEntry(
                    video = video,
                    localVideoPath = finalVideo.absolutePath,
                    localThumbnailPath = thumbnailPath,
                    downloadedAtEpochMs = System.currentTimeMillis(),
                    sizeBytes = sizeBytes
                )
                val updated = (current.filterNot { it.video.id.equals(video.id, ignoreCase = true) } + entry)
                    .sortedByDescending { it.downloadedAtEpochMs }
                persist(accountId, updated)
                _videos.value = updated
                Result.success(entry)
            } catch (error: Exception) {
                partialVideo.delete()
                finalVideo.delete()
                finalThumbnail.delete()
                Result.failure(error)
            } finally {
                updateProgress(video.id, null)
            }
        }
    }

    suspend fun remove(videoId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val accountId = currentAccountId()
            ?: return@withContext Result.failure(IllegalStateException("No hay una cuenta activa"))
        writeMutex.withLock {
            val directory = accountDirectory(accountId).canonicalFile
            val current = loadAndPrune(accountId)
            val target = current.firstOrNull { it.video.id.equals(videoId, ignoreCase = true) }
                ?: return@withLock Result.success(Unit)
            listOfNotNull(target.localVideoPath, target.localThumbnailPath).forEach { path ->
                runCatching {
                    val file = File(path).canonicalFile
                    if (file.path.startsWith(directory.path + File.separator)) file.delete()
                }
            }
            val updated = current.filterNot { it.video.id.equals(videoId, ignoreCase = true) }
            persist(accountId, updated)
            _videos.value = updated
            Result.success(Unit)
        }
    }

    suspend fun removeAll(): Result<Unit> = withContext(Dispatchers.IO) {
        val accountId = currentAccountId()
            ?: return@withContext Result.failure(IllegalStateException("No hay una cuenta activa"))
        writeMutex.withLock {
            loadAndPrune(accountId).forEach { entry ->
                File(entry.localVideoPath).delete()
                entry.localThumbnailPath?.let { File(it).delete() }
            }
            persist(accountId, emptyList())
            _videos.value = emptyList()
            Result.success(Unit)
        }
    }

    private fun loadAndPrune(accountId: String): List<OfflineVideoEntry> {
        val encoded = preferences.getString(preferenceKey(accountId), null) ?: return emptyList()
        val stored = runCatching { json.decodeFromString<List<OfflineVideoEntry>>(encoded) }
            .getOrElse { emptyList() }
        val valid = stored.filter { entry ->
            val videoFile = File(entry.localVideoPath)
            videoFile.isFile && videoFile.length() > 0L
        }.sortedByDescending { it.downloadedAtEpochMs }
        if (valid.size != stored.size) persist(accountId, valid)
        return valid
    }

    private fun persist(accountId: String, entries: List<OfflineVideoEntry>) {
        val committed = preferences.edit()
            .putString(preferenceKey(accountId), json.encodeToString(entries))
            .commit()
        check(committed) { "No se pudo guardar el índice de videos sin conexión" }
    }

    private fun downloadToFile(
        sourceUrl: String,
        destination: File,
        maximumBytes: Long,
        onProgress: (Float) -> Unit
    ): Long {
        require(sourceUrl.startsWith("http://", true) || sourceUrl.startsWith("https://", true)) {
            "La dirección del archivo no es válida"
        }
        destination.parentFile?.mkdirs()
        val storageRead = conectate.com.core.media.StorageReadSession.isStorageRequest(sourceUrl)
        val storageCredential = if (storageRead) conectate.com.core.media.StorageReadSession.readySnapshot() else null
        val storageIdentity = storageCredential?.identity
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = !storageRead
            requestMethod = "GET"
            if (storageRead) {
                conectate.com.core.media.StorageReadSession.requireCurrent(storageIdentity)
                storageCredential?.accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                setRequestProperty("Cache-Control", "no-store")
            }
            setRequestProperty("Accept", "*/*")
            UbuntuStorageUrl.publicRequestHeaders.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }
        try {
            val status = connection.responseCode
            if (storageRead) conectate.com.core.media.StorageReadSession.requireCurrent(storageIdentity)
            if (status !in 200..299) throw IllegalStateException("No se pudo descargar el archivo ($status)")
            val declaredLength = connection.contentLengthLong
            if (declaredLength > maximumBytes) {
                throw IllegalArgumentException("El archivo supera el tamaño permitido")
            }
            val availableBytes = StatFs(destination.parentFile?.absolutePath ?: context.filesDir.absolutePath).availableBytes
            if (declaredLength > 0L && availableBytes < declaredLength + MIN_FREE_SPACE_BYTES) {
                throw IllegalStateException("No hay espacio suficiente para guardar el video")
            }

            var copied = 0L
            conectate.com.core.media.StorageReadSession.guardInputStream(sourceUrl, connection.inputStream, storageIdentity).buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > maximumBytes) throw IllegalArgumentException("El archivo supera el tamaño permitido")
                        output.write(buffer, 0, read)
                        if (declaredLength > 0L) {
                            onProgress((copied.toFloat() / declaredLength.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                    output.flush()
                }
            }
            if (copied <= 0L) throw IllegalStateException("El archivo descargado está vacío")
            onProgress(1f)
            return copied
        } finally {
            connection.disconnect()
        }
    }

    private fun validatePlayableVideo(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (duration <= 0L) throw IllegalArgumentException("El archivo descargado no es un video reproducible")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun updateProgress(videoId: String, progress: Float?) {
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            if (progress == null) remove(videoId) else put(videoId, progress.coerceIn(0f, 1f))
        }
    }

    private fun currentAccountId(): String? = authService.getCurrentUserId()
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

    private fun accountDirectory(accountId: String): File =
        File(context.filesDir, "offline-videos/${safePart(accountId)}")

    private fun preferenceKey(accountId: String): String = "account:${safePart(accountId)}"
    private fun targetPreferenceKey(accountId: String): String = "target:${safePart(accountId)}"
    private fun refreshPreferenceKey(accountId: String): String = "refresh:${safePart(accountId)}"

    private fun safePart(value: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9._-]"), "_")
        .take(100)

    private companion object {
        private const val PREFERENCES_NAME = "offline_video_library_v1"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 90_000
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val MAX_OFFLINE_VIDEO_BYTES = 512L * 1024L * 1024L
        private const val MAX_THUMBNAIL_BYTES = 10L * 1024L * 1024L
        private const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L
        private const val MAX_CONFIGURED_VIDEO_COUNT = 150
    }
}
