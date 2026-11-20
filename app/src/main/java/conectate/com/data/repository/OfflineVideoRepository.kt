package conectate.com.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import conectate.com.data.localfallback.UbuntuStorageUrl
import conectate.com.data.model.Video
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    internal val accountVisitChanges get() = authService.accountVisitChanges

    internal fun captureOwner() = authService.captureAccountLease()

    internal fun refreshForCurrentAccount(owner: AuthSessionLease = captureOwner()) {
        val accountId = owner.accountId
        if (accountId == null) {
            _videos.value = emptyList()
            _downloadProgress.value = emptyMap()
            return
        }
        val entries = loadAvailable(accountId)
        authService.commitForAccountLease(owner) {
            _videos.value = entries
            _downloadProgress.value = emptyMap()
        }
    }

    internal fun configuredTargetCount(owner: AuthSessionLease = captureOwner()): Int {
        val accountId = owner.accountId ?: return 0
        return preferences.getInt(targetPreferenceKey(accountId), 0)
            .coerceIn(0, MAX_CONFIGURED_VIDEO_COUNT)
    }

    internal fun setConfiguredTargetCount(count: Int, owner: AuthSessionLease) {
        val accountId = owner.accountId ?: return
        authService.commitForAccountLease(owner) {
            preferences.edit().putInt(targetPreferenceKey(accountId), count.coerceIn(0, MAX_CONFIGURED_VIDEO_COUNT)).apply()
        }
    }

    internal fun requestFreshCycle(owner: AuthSessionLease) {
        val accountId = owner.accountId ?: return
        authService.commitForAccountLease(owner) {
            preferences.edit().putBoolean(refreshPreferenceKey(accountId), true).apply()
        }
    }

    internal fun consumeFreshCycleRequest(owner: AuthSessionLease): Boolean {
        val accountId = owner.accountId ?: return false
        var requested = false
        if (!authService.commitForAccountLease(owner) {
            requested = preferences.getBoolean(refreshPreferenceKey(accountId), false)
            if (requested) preferences.edit().remove(refreshPreferenceKey(accountId)).apply()
        }) owner.requireCurrent()
        return requested
    }

    internal suspend fun download(video: Video, owner: AuthSessionLease): Result<OfflineVideoEntry> = withContext(Dispatchers.IO) {
        owner.requireCurrent()
        val accountId = owner.accountId
            ?: return@withContext Result.failure(IllegalStateException("No hay una cuenta activa"))
        val videoId = safePart(video.id)
        if (videoId.isBlank() || video.videoUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("El video no tiene una dirección válida"))
        }

        writeMutex.withLock {
            owner.requireCurrent()
            val current = loadAvailable(accountId)
            current.firstOrNull { it.video.id.equals(video.id, ignoreCase = true) }
                ?.let { existing ->
                    authService.commitForAccountLease(owner) { _videos.value = current }
                    return@withLock Result.success(existing)
                }

            val accountDirectory = accountDirectory(accountId).apply { mkdirs() }
            val finalVideo = File(accountDirectory, "$videoId.mp4")
            val partialVideo = File(accountDirectory, "$videoId.mp4.part")
            val finalThumbnail = File(accountDirectory, "$videoId.thumbnail")
            partialVideo.delete()

            try {
                val job = currentCoroutineContext()
                val checkActive = { job.ensureActive(); owner.requireCurrent() }
                val sourceUrl = UbuntuStorageUrl.rewrite(video.videoUrl) ?: video.videoUrl
                val sizeBytes = downloadToFile(
                    sourceUrl = sourceUrl,
                    destination = partialVideo,
                    maximumBytes = MAX_OFFLINE_VIDEO_BYTES,
                    checkActive = checkActive,
                    onProgress = { progress -> updateProgress(owner, video.id, progress) }
                )
                checkActive()
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
                            checkActive = checkActive,
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
                checkActive()
                if (!authService.commitForAccountLease(owner) {
                    persist(accountId, updated)
                    _videos.value = updated
                }) throw CancellationException("La sesión cambió durante la descarga")
                Result.success(entry)
            } catch (error: Exception) {
                partialVideo.delete()
                finalVideo.delete()
                finalThumbnail.delete()
                if (error is CancellationException) throw error
                Result.failure(error)
            } finally {
                updateProgress(owner, video.id, null)
            }
        }
    }

    internal suspend fun remove(videoId: String, owner: AuthSessionLease): Result<Unit> =
        removeEntries(owner) { it.video.id.equals(videoId, ignoreCase = true) }

    internal suspend fun removeAll(owner: AuthSessionLease): Result<Unit> = removeEntries(owner) { true }

    private suspend fun removeEntries(owner: AuthSessionLease, selected: (OfflineVideoEntry) -> Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val accountId = owner.accountId
                ?: return@withContext Result.failure(IllegalStateException("No hay una cuenta activa"))
            writeMutex.withLock {
                authService.withAccountLocalStorage(owner) {
                    val (removed, remaining) = loadAvailable(accountId).partition(selected)
                    val directory = accountDirectory(accountId).canonicalFile
                    for (entry in removed) {
                        listOfNotNull(entry.localVideoPath, entry.localThumbnailPath).forEach { path ->
                            val file = File(path).canonicalFile
                            if (file.parentFile == directory && file.exists() && !file.delete()) {
                                return@withAccountLocalStorage Result.failure<Unit>(IllegalStateException("No se pudo eliminar el archivo"))
                            }
                        }
                    }
                    persist(accountId, remaining)
                    authService.commitForAccountLease(owner) { _videos.value = remaining }
                    Result.success(Unit)
                }
            }
        }

    private fun loadAvailable(accountId: String): List<OfflineVideoEntry> {
        val encoded = preferences.getString(preferenceKey(accountId), null) ?: return emptyList()
        val stored = runCatching { json.decodeFromString<List<OfflineVideoEntry>>(encoded) }
            .getOrElse { emptyList() }
        val directory = accountDirectory(accountId).canonicalFile
        return stored.mapNotNull { entry ->
            val video = File(entry.localVideoPath).canonicalFile
            if (video.parentFile != directory || !video.isFile || video.length() == 0L) return@mapNotNull null
            val thumbnail = entry.localThumbnailPath?.let { File(it).canonicalFile }
                ?.takeIf { it.parentFile == directory && it.isFile }
            entry.copy(localThumbnailPath = thumbnail?.absolutePath)
        }.sortedByDescending { it.downloadedAtEpochMs }
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
        checkActive: () -> Unit,
        onProgress: (Float) -> Unit
    ): Long {
        require(sourceUrl.startsWith("http://", true) || sourceUrl.startsWith("https://", true)) {
            "La dirección del archivo no es válida"
        }
        checkActive()
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
                        checkActive()
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

    private fun updateProgress(owner: AuthSessionLease, videoId: String, progress: Float?) {
        authService.commitForAccountLease(owner) {
            _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
                if (progress == null) remove(videoId) else put(videoId, progress.coerceIn(0f, 1f))
            }
        }
    }

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
