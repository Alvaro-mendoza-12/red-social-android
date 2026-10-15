package conectate.com.core.media

import android.content.Context
import android.os.StatFs
import conectate.com.data.localfallback.UbuntuStorageUrl
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Account-scoped local copies of chat media, separate from disposable player caches.
 *
 * Coil and Media3 intentionally use [Context.cacheDir], which Android may clear at
 * any time. This cache lives below filesDir instead, but it is deliberately not a
 * download manager: it only accepts image, audio and video responses and enforces
 * file-size/free-space limits. Speculative prefetch has an LRU budget; a file the
 * person opens is retained until they explicitly clear their offline copies.
 * It must never be used for view-once media.
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
    private val maxRetainedEntryBytes: Long = MAX_RETAINED_ENTRY_BYTES,
    private val minFreeSpaceBytes: Long = MIN_FREE_SPACE_BYTES,
    private val onCachedMedia: (String, String, File) -> Unit = { _, _, _ -> },
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    }
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val fileLock = Any()
    private val accountEpochs = mutableMapOf<String, Long>()
    private val inFlight = mutableMapOf<String, DownloadWork>()

    private data class DownloadWork(
        val accountId: String,
        val sourceKey: String,
        val epoch: Long,
        var retain: Boolean
    )

    /** Returns a file URI only for data belonging to the active account. */
    suspend fun resolveCachedUri(source: String?, retain: Boolean = false): String? = withContext(Dispatchers.IO) {
        val accountId = normalizedAccountId() ?: return@withContext null
        val sourceKey = canonicalSourceKey(source) ?: return@withContext null
        val uri = synchronized(fileLock) { resolveEntry(accountId, sourceKey, retain) }
        if (uri != null) onCachedMedia(accountId, sourceKey, entryFile(accountId, sourceKey))
        uri
    }

    private fun resolveEntry(accountId: String, sourceKey: String, retain: Boolean): String? {
        val file = entryFile(accountId, sourceKey)
        if (!file.isFile || file.length() <= 0L) return null
        if (retain) ChatMediaStoragePolicy.retain(file)
        runCatching { file.setLastModified(nowEpochMs()) }
        return file.toURI().toString()
    }

    /**
     * Queues a bounded, best-effort write for background prefetch.
     * Automatic media preferences apply to this path.
     */
    fun cacheOpenedMedia(source: String?, resolvedUrl: String?, expectedAccountId: String? = null) {
        if (!automaticMediaEnabled()) return
        queueMedia(source, resolvedUrl, retain = false, expectedAccountId = expectedAccountId)
    }

    /**
     * Queues a size-limited, best-effort retained copy after the user opened media.
     * This is intentionally independent of automatic-download preferences: content
     * the user already viewed should remain available to the conversation offline.
     * It never blocks UI rendering and never logs a URL, token, or account id.
     */
    fun cacheExplicitlyOpenedMedia(source: String?, resolvedUrl: String?, expectedAccountId: String? = null) {
        queueMedia(source, resolvedUrl, retain = true, expectedAccountId = expectedAccountId)
    }

    private fun queueMedia(source: String?, resolvedUrl: String?, retain: Boolean, expectedAccountId: String?) {
        val accountId = normalizedAccountId() ?: return
        if (expectedAccountId != null && accountId != expectedAccountId.trim().lowercase()) return
        val sourceKey = canonicalSourceKey(source) ?: return
        val remoteUrl = resolvedUrl?.trim().orEmpty()
        if (!remoteUrl.startsWith("https://", ignoreCase = true) &&
            !remoteUrl.startsWith("http://", ignoreCase = true)
        ) return
        val inFlightKey = "${accountDirectoryName(accountId)}:${digest(sourceKey)}"
        val work = synchronized(fileLock) {
            if (resolveEntry(accountId, sourceKey, retain) != null) return
            inFlight[inFlightKey]?.let {
                it.retain = it.retain || retain
                return
            }
            DownloadWork(accountId, sourceKey, accountEpochs[accountId] ?: 0L, retain).also {
                inFlight[inFlightKey] = it
            }
        }
        ioScope.launch {
            try {
                downloadAndStore(work, remoteUrl)
            } finally {
                synchronized(fileLock) {
                    if (inFlight[inFlightKey] === work) inFlight.remove(inFlightKey)
                }
            }
        }
    }

    /** Reports only copies belonging to the current signed-in account. */
    suspend fun currentAccountUsage(): ChatOfflineMediaUsage? = withContext(Dispatchers.IO) {
        val accountId = normalizedAccountId() ?: return@withContext null
        synchronized(fileLock) {
            ChatMediaStoragePolicy.usage(accountDirectory(accountId), accountDirectoryName(accountId))
        }
    }

    /** Invalidates outstanding writes before removing only the confirmed account's copies. */
    suspend fun clearCurrentAccount(expectedAccountKey: String? = null): Boolean = withContext(Dispatchers.IO) {
        val accountId = normalizedAccountId() ?: return@withContext false
        synchronized(fileLock) {
            if (expectedAccountKey != null && expectedAccountKey != accountDirectoryName(accountId)) {
                return@synchronized false
            }
            val directory = accountDirectory(accountId).canonicalFile
            val root = rootDirectory.canonicalFile
            if (!directory.path.startsWith(root.path + File.separator)) return@synchronized false
            accountEpochs[accountId] = (accountEpochs[accountId] ?: 0L) + 1L
            inFlight.entries.removeAll { it.value.accountId == accountId }
            runCatching { !directory.exists() || directory.deleteRecursively() }.getOrDefault(false)
        }
    }

    /** Visible for local JVM tests; production callers should use the cache methods above. */
    internal suspend fun storeForTest(
        source: String,
        contentType: String?,
        declaredBytes: Long,
        input: InputStream,
        retain: Boolean = false
    ): Result<File> {
        val accountId = normalizedAccountId()
            ?: return Result.failure(IllegalStateException("No active account"))
        val sourceKey = canonicalSourceKey(source)
            ?: return Result.failure(IllegalArgumentException("Invalid media source"))
        return writeMutex.withLock {
            storeInput(
                work = DownloadWork(accountId, sourceKey, accountEpochs[accountId] ?: 0L, retain),
                contentType = contentType,
                declaredBytes = declaredBytes,
                input = input
            )
        }
    }

    private suspend fun downloadAndStore(work: DownloadWork, remoteUrl: String) {
        writeMutex.withLock {
            synchronized(fileLock) {
                if (!isCurrent(work) || resolveEntry(work.accountId, work.sourceKey, work.retain) != null) return
            }
            runCatching {
                val storageRead = conectate.com.core.media.StorageReadSession.isStorageRequest(remoteUrl)
                val storageCredential = if (storageRead) conectate.com.core.media.StorageReadSession.readySnapshot() else null
                val storageIdentity = storageCredential?.identity
                val connection = connectionFactory(URL(remoteUrl)).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = !storageRead
                    requestMethod = "GET"
                    if (storageRead) {
                        conectate.com.core.media.StorageReadSession.requireCurrent(storageIdentity)
                        storageCredential?.accessToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                        setRequestProperty("Cache-Control", "no-store")
                    }
                    setRequestProperty("Accept", "image/*,audio/*,video/*;q=0.9,*/*;q=0.1")
                    UbuntuStorageUrl.publicRequestHeaders.forEach { (name, value) ->
                        setRequestProperty(name, value)
                    }
                }
                try {
                    val status = connection.responseCode
                    if (storageRead) conectate.com.core.media.StorageReadSession.requireCurrent(storageIdentity)
                    // A partial (206) response must not become a supposedly complete offline file.
                    if (status != HttpURLConnection.HTTP_OK) return@runCatching
                    val contentType = connection.contentType
                    val declaredBytes = connection.contentLengthLong
                    if (!isCacheableContentType(contentType, remoteUrl)) return@runCatching
                    conectate.com.core.media.StorageReadSession.guardInputStream(remoteUrl, connection.inputStream, storageIdentity).buffered().use { input ->
                        val file = storeInput(
                            work = work,
                            contentType = contentType,
                            declaredBytes = declaredBytes,
                            input = input
                        ).getOrThrow()
                        if (synchronized(fileLock) { isCurrent(work) }) onCachedMedia(work.accountId, work.sourceKey, file)
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    private fun storeInput(
        work: DownloadWork,
        contentType: String?,
        declaredBytes: Long,
        input: InputStream
    ): Result<File> = runCatching {
        val accountId = work.accountId
        val sourceKey = work.sourceKey
        val entryLimit = synchronized(fileLock) {
            if (work.retain) maxRetainedEntryBytes else maxEntryBytes
        }
        require(isCacheableContentType(contentType, sourceKey)) { "Unsupported media type" }
        require(declaredBytes <= entryLimit || declaredBytes < 0L) {
            "Media exceeds offline cache entry limit"
        }

        val directory = accountDirectory(accountId)
        val target = entryFile(accountId, sourceKey)
        val partial = File(directory, ".${target.name}.${System.nanoTime()}.part")
        synchronized(fileLock) {
            check(isCurrent(work)) { "Offline media request was cleared" }
            directory.mkdirs()
            require(directory.isDirectory) { "Offline media directory is unavailable" }
            if (resolveEntry(accountId, sourceKey, work.retain) != null) return@runCatching target
            val reservation = if (declaredBytes in 1..entryLimit) declaredBytes else entryLimit
            require(availableBytes(directory) >= minFreeSpaceBytes + reservation) {
                "Insufficient storage for offline media"
            }
            cleanupStalePartials(directory)
            // Finish validation before evicting any healthy cached files at commit time.
            check(partial.createNewFile()) { "Offline media staging is unavailable" }
        }
        var copied = 0L
        try {
            partial.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    require(copied <= entryLimit) { "Media exceeds offline cache entry limit" }
                    require(availableBytes(directory) >= minFreeSpaceBytes + read) {
                        "Insufficient storage for offline media"
                    }
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
            require(copied > 0L) { "Downloaded media is empty" }
            require(declaredBytes < 0L || copied == declaredBytes) { "Incomplete media response" }
            synchronized(fileLock) {
                check(isCurrent(work)) { "Offline media request was cleared" }
                if (!work.retain) {
                    require(pruneToFit(directory, copied, target)) { "Offline media quota is full" }
                }
                // Same-directory rename is atomic. Never fall back to exposing a half-written target.
                check(partial.renameTo(target)) { "Could not publish offline media atomically" }
                if (work.retain && !ChatMediaStoragePolicy.retain(target)) {
                    target.delete()
                    error("Could not retain offline media")
                }
                target.setLastModified(nowEpochMs())
            }
            target
        } catch (error: Throwable) {
            partial.delete()
            target.takeIf { it.length() == 0L }?.delete()
            throw error
        }
    }

    private fun isCurrent(work: DownloadWork): Boolean =
        (accountEpochs[work.accountId] ?: 0L) == work.epoch

    private fun pruneToFit(directory: File, incomingBytes: Long, protectedFile: File): Boolean {
        cleanupStalePartials(directory)
        return ChatMediaStoragePolicy.prunePrefetch(directory, incomingBytes, maxAccountCacheBytes, protectedFile)
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
        return canonicalChatMediaSourceKey(value)
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
        private const val MAX_RETAINED_ENTRY_BYTES = 512L * 1024L * 1024L
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
            automaticMediaEnabled: () -> Boolean,
            onCachedMedia: (String, String, File) -> Unit = { _, _, _ -> }
        ): ChatMediaOfflineCache = ChatMediaOfflineCache(
            rootDirectory = File(context.filesDir, ROOT_DIRECTORY),
            accountIdProvider = accountIdProvider,
            automaticMediaEnabled = automaticMediaEnabled,
            onCachedMedia = onCachedMedia
        )
    }
}
