package conectate.com.util

import android.content.Context
import android.util.Log
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import conectate.com.data.localfallback.UbuntuStorageUrl
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object VideoCache {
    private val prefetchSlots = Semaphore(2)
    private var simpleCache: SimpleCache? = null
    private var databaseProvider: DatabaseProvider? = null

    @Synchronized
    private fun getOrCreateCache(context: Context): SimpleCache? {
        simpleCache?.let { return it }

        return try {
            val cacheSize = VideoPerformanceProfiles.detect(context).cacheSizeBytes
            val cacheEvictor = LeastRecentlyUsedCacheEvictor(cacheSize)
            var provider = StandaloneDatabaseProvider(context)

            val cacheDir = File(context.cacheDir, "media").apply { mkdirs() }
            SimpleCache(cacheDir, cacheEvictor, provider).also { cache ->
                simpleCache = cache
                databaseProvider = provider
            }
        } catch (e: Exception) {
            Log.e("VideoCache", "Cache init failed. Falling back to non-cached DataSource.", e)
            runCatching { simpleCache?.release() }
            simpleCache = null
            databaseProvider = null
            null
        }
    }

    fun getCacheDataSourceFactory(context: Context): DataSource.Factory =
        createCacheDataSourceFactory(context, requireStorageCacheAdmission = false)

    private fun createCacheDataSourceFactory(
        context: Context,
        requireStorageCacheAdmission: Boolean,
        prefetchTimeoutMs: Long? = null,
    ): DataSource.Factory {
        // ChatMediaOfflineCache and pending uploads return file:/ or content:/ URIs.
        // An HTTP-only upstream fails as soon as the playback cache misses those local bytes.
        val upstreamDataSourceFactory = if (prefetchTimeoutMs == null) MediaPlaybackDataSources.streamingFactory(context)
            else MediaPlaybackDataSources.prefetchFactory(context, prefetchTimeoutMs)

        // StorageReadDataSource selects this factory from open() on Media3's
        // loading thread. Building a player in Compose must not open SQLite or
        // scan/create the disk cache on the main thread.
        val cachedFactory = DataSource.Factory {
            val cache = getOrCreateCache(context.applicationContext)
            if (cache != null) CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            else upstreamDataSourceFactory.createDataSource()
        }
        // Every private cache open still checks current server permissions and
        // ETag. Prefetch must stop if those bytes could not be reused by playback.
        return DataSource.Factory {
            StorageReadDataSource(upstreamDataSourceFactory, cachedFactory, requireStorageCacheAdmission) { spec ->
                PlaybackCacheAdmission.key(spec, sourceFactory = upstreamDataSourceFactory)
            }
        }
    }

    /**
     * Drops the bytes for one media object after ExoPlayer reports a source
     * failure. A cancelled Range request can otherwise leave a partial span
     * under the stable storage key and every retry reuses the same bad prefix.
     */
    @Synchronized
    fun removeResource(context: Context, url: String?) {
        val resolvedUrl = UbuntuStorageUrl.rewrite(url) ?: return
        val guarded = conectate.com.core.media.StorageReadSession.isStorageRequest(resolvedUrl)
        val identity = if (guarded) conectate.com.core.media.StorageReadSession.snapshot()?.identity else null
        val cache = getOrCreateCache(context.applicationContext) ?: return
        runCatching {
            if (guarded) conectate.com.core.media.StorageReadSession.requireCurrent(identity)
            val keys = videoCacheKeysToRemove(resolvedUrl, cache.keys)
            keys.forEach { key ->
                if (guarded) conectate.com.core.media.StorageReadSession.requireCurrent(identity)
                cache.removeResource(key)
            }
        }
            .onFailure { Log.w("VideoCache", "Unable to evict failed media resource", it) }
    }

    /**
     * Warm only a bounded prefix of a neighbouring feed video. Own-storage
     * bytes are reusable only with fresh server admission and the same ETag.
     */
    suspend fun preloadStart(
        context: Context,
        url: String,
        byteCount: Long = 2L * 1024L * 1024L,
        timeoutMs: Long = 2_000L,
    ) = withContext(Dispatchers.IO) {
      prefetchSlots.withPermit { coroutineScope {
        val resolvedUrl = UbuntuStorageUrl.rewrite(url) ?: return@coroutineScope
        val cacheKey = UbuntuStorageUrl.stableCacheKey(resolvedUrl) ?: return@coroutineScope
        val cache = getOrCreateCache(context.applicationContext) ?: return@coroutineScope
        val dataSource = createCacheDataSourceFactory(context.applicationContext,
            requireStorageCacheAdmission = true, prefetchTimeoutMs = timeoutMs).createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(resolvedUrl)
            .setPosition(0)
            .setLength(byteCount)
            .setKey(cacheKey)
            .build()
        val task = async(Dispatchers.IO) {
            try {
                dataSource.open(dataSpec)
                val scratch = ByteArray(32 * 1024)
                var read = 0L
                while (read < byteCount && kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val count = dataSource.read(scratch, 0, minOf(scratch.size.toLong(), byteCount - read).toInt())
                    if (count < 0) break
                    read += count
                }
            } finally { dataSource.close() }
        }
        var completedWithinBudget = false
        try {
            // Prefetch is strictly best-effort. On a slow mobile network it
            // must yield instead of competing indefinitely with the visible
            // video's Range request.
            completedWithinBudget = withTimeoutOrNull(timeoutMs) {
                task.await()
                true
            } == true
        } finally {
            // CacheWriter is blocking. Cancelling only the coroutine can leave
            // an old prefetch consuming the same mobile connection as the
            // visible player after a fast swipe or recycled chat row.
            if (!completedWithinBudget) {
                task.cancel()
                runCatching { dataSource.close() }
            }
        }
      } }
    }

    fun release() {
        try {
            simpleCache?.release()
            simpleCache = null
            databaseProvider = null
        } catch (e: Exception) {
            Log.w("VideoCache", "Video cache release failed", e)
        }
    }
}



