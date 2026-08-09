package conectate.com.core.media

import android.content.Context
import android.os.StatFs
import conectate.com.data.localfallback.UbuntuStorageUrl
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Account-scoped durable cache for image, audio and video opened in a chat.
 *
 * It lives in filesDir instead of the volatile Coil/Media3 cache, enforces a
 * quota and never accepts view-once media. URLs, tokens and account ids are not
 * used as file names or written to logs.
 */
internal class ChatMediaOfflineCache(
    private val rootDirectory: File,
    private val accountIdProvider: () -> String?,
    private val automaticMediaEnabled: () -> Boolean,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val availableBytes: (File) -> Long = { directory ->
        runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(0L)
    },
    private val maxAccountCacheBytes: Long = MAX_ACCOUNT_CACHE_BYTES,
    private val maxEntryBytes: Long = MAX_ENTRY_BYTES,
    private val minFreeSpaceBytes: Long = MIN_FREE_SPACE_BYTES,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()

    fun resolveCachedUri(source: String?): String? {
        val accountId = normalizedAccountId() ?: return null
        val sourceKey = canonicalSourceKey(source) ?: return null
        val file = entryFile(accountId, sourceKey)
        if (!file.isFile || file.length() <= 0L) return null
        runCatching { file.setLastModified(nowEpochMs()) }
        return file.toURI().toString()
    }

    fun cacheOpenedMedia(source: String?, resolvedUrl: String?) {
        if (!automaticMediaEnabled()) return
        val accountId = normalizedAccountId() ?: return
        val sourceKey = canonicalSourceKey(source) ?: return
        val remoteUrl = resolvedUrl?.trim().orEmpty()
        if (!remoteUrl.startsWith("https://", ignoreCase = true) &&
            !remoteUrl.startsWith("http://", ignoreCase = true)
        ) return
        if (entryFile(accountId, sourceKey).let { it.isFile && it.length() > 0L }) return

        val inFlightKey = "${accountDirectoryName(accountId)}:${digest(sourceKey)}"
        if (!inFlightKeys.add(inFlightKey)) return
        ioScope.launch {
            try {
                downloadAndStore(accountId, sourceKey, remoteUrl)
            } finally {
                inFlightKeys.remove(inFlightKey)
            }
        }
    }

    suspend fun clearCurrentAccount(): Boolean {
        val accountId = normalizedAccountId() ?: return false
        return writeMutex.withLock {
            val directory = accountDirectory(accountId).canonicalFile
            val root = rootDirectory.canonicalFile
            if (!directory.path.startsWith(root.path + File.separator)) return@withLock false
            runCatching { directory.deleteRecursively() }.getOrDefault(false)
        }
    }

    internal suspend fun storeForTest(
        source: String,
        contentType: String?,
        declaredBytes: Long,
        input: InputStream
    ): Result<File> {
        val accountId = normalizedAccountId()
            ?: return Result.failure(IllegalStateException("No active account"))
        val sourceKey = canonicalSourceKey(source)
            ?: return Result.failure(IllegalArgumentException("Invalid media source"))
        return writeMutex.withLock {
            storeInput(accountId, sourceKey, contentType, declaredBytes, input)
        }
    }

    private suspend fun downloadAndStore(accountId: String, sourceKey: String, remoteUrl: String) {
        writeMutex.withLock {
            if (entryFile(accountId, sourceKey).let { it.isFile && it.length() > 0L }) return@withLock
            runCatching {
                val connection = connectionFactory(URL(remoteUrl)).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept", "image/*,audio/*,video/*;q=0.9,*/*;q=0.1")
                    UbuntuStorageUrl.publicRequestHeaders.forEach { (name, value) ->
                        setRequestProperty(name, value)
                    }
                }
                try {
                    val status = connection.responseCode
                    if (status !in 200..299) return@runCatching
                    val contentType = connection.contentType
                    val declaredBytes = connection.contentLengthLong
                    if (!isCacheableContentType(contentType, remoteUrl)) return@runCatching
                    connection.inputStream.buffered().use { input ->
                        storeInput(accountId, sourceKey, contentType, declaredBytes, input).getOrThrow()
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    private fun storeInput(
        accountId: String,
        sourceKey: String,
        contentType: String?,
        declaredBytes: Long,
        input: InputStream
    ): Result<File> = runCatching {
        require(isCacheableContentType(contentType, sourceKey)) { "Unsupported media type" }
        require(declaredBytes <= maxEntryBytes || declaredBytes < 0L) {
            "Media exceeds offline cache entry limit"
        }

        val directory = accountDirectory(accountId).apply { mkdirs() }
        require(directory.isDirectory) { "Offline media directory is unavailable" }
        val target = entryFile(accountId, sourceKey)
        if (target.isFile && target.length() > 0L) {
            target.setLastModified(nowEpochMs())
            return@runCatching target
        }

        val reservation = if (declaredBytes in 1..maxEntryBytes) declaredBytes else maxEntryBytes
        require(availableBytes(directory) >= minFreeSpaceBytes + reservation) {
            "Insufficient storage for offline media"
        }
        require(pruneToFit(directory, reservation, target)) { "Offline media quota is full" }

        val partial = File(directory, ".${target.name}.${System.nanoTime()}.part")
        partial.delete()
        var copied = 0L
        try {
            partial.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    require(copied <= maxEntryBytes) { "Media exceeds offline cache entry limit" }
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
            require(copied > 0L) { "Downloaded media is empty" }
            require(pruneToFit(directory, copied, target)) { "Offline media quota is full" }
            if (!partial.renameTo(target)) {
                partial.inputStream().use { source ->
                    target.outputStream().use { destination -> source.copyTo(destination) }
                }
                partial.delete()
            }
            target.setLastModified(nowEpochMs())
            target
        } catch (error: Throwable) {
            partial.delete()
            target.takeIf { it.length() == 0L }?.delete()
            throw error
        }
    }

    private fun pruneToFit(directory: File, incomingBytes: Long, protectedFile: File): Boolean {
        cleanupStalePartials(directory)
        var currentBytes = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.sumOf { it.length() }
            ?: 0L
        if (currentBytes + incomingBytes <= maxAccountCacheBytes) return true

        val candidates = directory.listFiles()
            ?.filter { it.isFile && it != protectedFile && !it.name.endsWith(".part") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        for (candidate in candidates) {
            val length = candidate.length()
            if (candidate.delete()) currentBytes -= length
            if (currentBytes + incomingBytes <= maxAccountCacheBytes) return true
        }
        return currentBytes + incomingBytes <= maxAccountCacheBytes
    }

    private fun cleanupStalePartials(directory: File) {
        val staleBefore = nowEpochMs() - PARTIAL_FILE_TTL_MS
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".part") && it.lastModified() <= staleBefore }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun accountDirectory(accountId: String): File =
        File(rootDirectory, "v1/${accountDirectoryName(accountId)}")

    private fun entryFile(accountId: String, sourceKey: String): File =
        File(accountDirectory(accountId), "${digest(sourceKey)}.media")

    private fun normalizedAccountId(): String? = accountIdProvider()
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

    private fun canonicalSourceKey(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val rewritten = UbuntuStorageUrl.rewrite(raw) ?: raw
        return UbuntuStorageUrl.stableCacheKey(rewritten)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun accountDirectoryName(accountId: String): String = digest("account:$accountId")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun isCacheableContentType(contentType: String?, source: String): Boolean {
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (normalized.startsWith("image/") || normalized.startsWith("audio/") || normalized.startsWith("video/")) {
            return true
        }
        if (normalized.isNotBlank() && normalized != "application/octet-stream") return false
        val path = source.substringBefore('?').substringBefore('#').lowercase()
        return MEDIA_EXTENSIONS.any(path::endsWith)
    }

    companion object {
        private const val ROOT_DIRECTORY = "chat-media-offline"
        private const val MAX_ACCOUNT_CACHE_BYTES = 192L * 1024L * 1024L
        private const val MAX_ENTRY_BYTES = 64L * 1024L * 1024L
        private const val MIN_FREE_SPACE_BYTES = 64L * 1024L * 1024L
        private const val PARTIAL_FILE_TTL_MS = 6L * 60L * 60L * 1000L
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val MEDIA_EXTENSIONS = setOf(
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".heic",
            ".mp4", ".webm", ".mkv", ".3gp", ".m4a", ".mp3", ".ogg", ".wav"
        )

        fun from(
            context: Context,
            accountIdProvider: () -> String?,
            automaticMediaEnabled: () -> Boolean
        ): ChatMediaOfflineCache = ChatMediaOfflineCache(
            rootDirectory = File(context.filesDir, ROOT_DIRECTORY),
            accountIdProvider = accountIdProvider,
            automaticMediaEnabled = automaticMediaEnabled
        )
    }
}
