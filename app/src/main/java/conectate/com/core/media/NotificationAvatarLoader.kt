package conectate.com.core.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Resolve the actual photo before the first post, shared by realtime and FCM. */
internal object NotificationAvatarLoader {
    private data class Entry(val bitmap: Bitmap, val atMs: Long)
    private val cache = linkedMapOf<String, Entry>()
    private var cacheIdentity: StorageReadIdentity? = null
    private const val TTL_MS = 5 * 60_000L
    private const val MAX_CACHE_BYTES = 4 * 1024 * 1024

    suspend fun load(context: Context, url: String?): Bitmap? = withContext(Dispatchers.IO) {
        val source = MediaImageRequestFactory.avatarUrl(url) ?: return@withContext null
        val uri = runCatching { java.net.URI(source) }.getOrNull() ?: return@withContext null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return@withContext null
        val identity = StorageReadSession.snapshot()?.identity
        try {
            StorageReadSession.requireCurrent(identity)
            val cached = synchronized(cache) {
                if (cacheIdentity != identity) { cache.clear(); cacheIdentity = identity }
                val now = android.os.SystemClock.elapsedRealtime()
                cache.entries.removeAll { now - it.value.atMs > TTL_MS || it.value.bitmap.isRecycled }
                cache[source]?.bitmap
            }
            if (cached != null) {
                StorageReadSession.requireCurrent(identity)
                return@withContext cached
            }
            // Inbox/profile rendering already keeps a bounded, account-owned
            // identity image. Reuse it before waiting on a slow socket.
            val saved = identity?.let { ProfileIdentityImageStore.find(context, url, it.accountId) }
                ?.takeIf { it.length() in 1..NotificationAvatarDecoder.MAX_BYTES }
            val bytes = saved?.readBytes() ?: StorageReadDownloads.readBytes(source, maxBytes = NotificationAvatarDecoder.MAX_BYTES, timeoutMillis = 2500)
            StorageReadSession.requireCurrent(identity)
            val bitmap = NotificationAvatarDecoder.decode(context, bytes) ?: return@withContext null
            StorageReadSession.requireCurrent(identity)
            synchronized(cache) {
                StorageReadSession.requireCurrent(identity)
                if (cacheIdentity != identity) { cache.clear(); cacheIdentity = identity }
                cache.remove(source)
                cache[source] = Entry(bitmap, android.os.SystemClock.elapsedRealtime())
                while (cache.size > 16 || cache.values.sumOf { it.bitmap.allocationByteCount.toLong() } > MAX_CACHE_BYTES) {
                    cache.remove(cache.keys.first())
                }
            }
            bitmap
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /** A photo is optional; a failed Room lookup must not suppress text. */
    suspend fun <T> optionalValue(block: suspend () -> T): T? = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    suspend fun senderPhoto(
        context: Context, users: conectate.com.data.repository.UserRepository,
        senderId: String?, suppliedUrl: String? = null,
    ): Bitmap? = withContext(Dispatchers.IO) {
        suppliedUrl?.takeIf { it.isNotBlank() }?.let { return@withContext load(context, it) }
        val id = senderId?.trim()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            ?: return@withContext null
        val cached = optionalValue { users.getCachedUserById(id) }
        val sender = cached?.takeIf { !it.avatar_url.isNullOrBlank() }
            ?: kotlinx.coroutines.withTimeoutOrNull(1200) { optionalValue { users.getUserById(id, forceNetwork = true).getOrNull() } }
            ?: cached
        load(context, sender?.avatar_url)
    }

    fun loadBlocking(context: Context, url: String?): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return runBlocking { load(context, url) }
    }
}
