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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

    /** Start uncached feed ranges with the authorized GET and reuse admitted preload bytes. */
    internal fun feedDataSourceFactory(context: Context): DataSource.Factory =
        createCacheDataSourceFactory(context, requireStorageCacheAdmission = false, preferDirectOnMiss = true)

    private fun createCacheDataSourceFactory(
        context: Context,
        requireStorageCacheAdmission: Boolean,
        upstreamDataSourceFactory: DataSource.Factory = MediaPlaybackDataSources.streamingFactory(context),
        preferDirectOnMiss: Boolean = false,
    ): DataSource.Factory {
        // ChatMediaOfflineCache and pending uploads return file:/ or content:/ URIs.
        // An HTTP-only upstream fails as soon as the playback cache misses those local bytes.
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
        val existingCache = if (preferDirectOnMiss) {
            { getOrCreateCache(context.applicationContext) }
        } else null
        // Cache reuse checks current permissions and ETag. Prefetch can still
        // populate the cache after admission so the next video can reuse it.
        return DataSource.Factory {
            val cacheNetworkRead = if (preferDirectOnMiss) {
                { spec: DataSpec, headers: Map<String, List<String>> ->
                    try {
                        getOrCreateCache(context.applicationContext)?.let { admittedVideoCacheSink(it, spec, headers) }
                    } catch (_: java.io.IOException) { null }
                }
            } else null
            StorageReadDataSource(upstreamDataSourceFactory, cachedFactory, requireStorageCacheAdmission, cacheNetworkRead) { spec ->
                PlaybackCacheAdmission.key(spec, sourceFactory = upstreamDataSourceFactory, existingCache = existingCache)
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
    ) {
        try {
            withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) {
                    prefetchSlots.withPermit {
                        val resolvedUrl = UbuntuStorageUrl.rewrite(url) ?: return@withPermit
                        val cacheKey = UbuntuStorageUrl.stableCacheKey(resolvedUrl) ?: return@withPermit
                        if (getOrCreateCache(context.applicationContext) == null) return@withPermit
                        val network = MediaPlaybackDataSources.prefetchFactory(context, timeoutMs)
                        val dataSource = createCacheDataSourceFactory(
                            context.applicationContext,
                            requireStorageCacheAdmission = true,
                            upstreamDataSourceFactory = network,
                        ).createDataSource()
                        val spec = DataSpec.Builder()
                            .setUri(resolvedUrl)
                            .setLength(byteCount)
                            .setKey(cacheKey)
                            .build()

                        supervisorScope {
                            val loader = async(Dispatchers.IO) {
                                try {
                                    currentCoroutineContext().ensureActive()
                                    dataSource.open(spec)
                                    val scratch = ByteArray(32 * 1024)
                                    var remaining = byteCount
                                    while (remaining > 0L && currentCoroutineContext().isActive) {
                                        val count = dataSource.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
                                        if (count < 0) break
                                        remaining -= count
                                    }
                                } finally {
                                    dataSource.close()
                                }
                            }
                            try {
                                loader.await()
                            } finally {
                                // Interrupt the owned HTTP calls, leaving close() to the loader.
                                if (!currentCoroutineContext().isActive) {
                                    (network as? PrefetchNetworkCancellation)?.cancelNetworkRequests()
                                }
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            throw error
        } catch (error: Exception) {
            throw java.io.IOException("No se pudo precargar el vídeo.", error)
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



