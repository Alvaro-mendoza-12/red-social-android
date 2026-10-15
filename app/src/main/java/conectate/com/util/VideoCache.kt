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

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object VideoCache {
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

    fun getCacheDataSourceFactory(context: Context): DataSource.Factory {
        // ChatMediaOfflineCache and pending uploads return file:/ or content:/ URIs.
        // An HTTP-only upstream fails as soon as the playback cache misses those local bytes.
        val upstreamDataSourceFactory = MediaPlaybackDataSources.streamingFactory(context)

        val cache = getOrCreateCache(context)
        val cachedFactory = if (cache != null) {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            upstreamDataSourceFactory
        }
        // Cached bytes cannot prove current membership. Own storage always uses
        // the authenticated upstream; explicit file:/ offline copies are preserved.
        return DataSource.Factory { StorageReadDataSource(upstreamDataSourceFactory, cachedFactory) { spec -> PlaybackCacheAdmission.key(spec) } }
    }

    /**
     * Drops the bytes for one media object after ExoPlayer reports a source
     * failure. A cancelled Range request can otherwise leave a partial span
     * under the stable storage key and every retry reuses the same bad prefix.
     */
    @Synchronized
    fun removeResource(context: Context, url: String?) {
        val resolvedUrl = UbuntuStorageUrl.rewrite(url) ?: return
        val cacheKey = UbuntuStorageUrl.stableCacheKey(resolvedUrl) ?: return
        val cache = getOrCreateCache(context.applicationContext) ?: return
        runCatching { cache.removeResource(cacheKey) }
            .onFailure { Log.w("VideoCache", "Unable to evict failed media resource", it) }
    }

    /**
     * Adelanta únicamente el inicio del video que aparece en un chat. El
     * reproductor usa la misma cache key, así que al tocarlo no vuelve a
     * comenzar la descarga desde cero ni consume el archivo completo.
     */
    suspend fun preloadStart(
        context: Context,
        url: String,
        byteCount: Long = 2L * 1024L * 1024L,
        timeoutMs: Long = 2_000L,
    ) = coroutineScope {
        val resolvedUrl = UbuntuStorageUrl.rewrite(url) ?: return@coroutineScope
        val cacheKey = UbuntuStorageUrl.stableCacheKey(resolvedUrl) ?: return@coroutineScope
        val cache = getOrCreateCache(context.applicationContext) ?: return@coroutineScope
        val dataSource = getCacheDataSourceFactory(context.applicationContext).createDataSource()
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



