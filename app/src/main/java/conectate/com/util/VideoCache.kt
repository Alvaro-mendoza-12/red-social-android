package conectate.com.util

import android.content.Context
import android.util.Log
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import conectate.com.data.localfallback.UbuntuStorageUrl
import java.io.File

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
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(UbuntuStorageUrl.publicRequestHeaders)

        var cache = getOrCreateCache(context)
        return if (cache != null) {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            DefaultDataSource.Factory(context, httpDataSourceFactory)
        }
    }

    /**
     * Adelanta únicamente el inicio del video que aparece en un chat. El
     * reproductor usa la misma cache key, así que al tocarlo no vuelve a
     * comenzar la descarga desde cero ni consume el archivo completo.
     */
    fun preloadStart(context: Context, url: String, byteCount: Long = 2L * 1024L * 1024L) {
        val resolvedUrl = UbuntuStorageUrl.rewrite(url) ?: return
        val cacheKey = UbuntuStorageUrl.stableCacheKey(resolvedUrl) ?: return
        val dataSource = getCacheDataSourceFactory(context.applicationContext)
            .createDataSource() as? CacheDataSource ?: return
        val dataSpec = DataSpec.Builder()
            .setUri(resolvedUrl)
            .setPosition(0)
            .setLength(byteCount)
            .setKey(cacheKey)
            .build()
        CacheWriter(dataSource, dataSpec, null, null).cache()
    }

    fun release() {
        try {
            simpleCache?.release()
            simpleCache = null
            databaseProvider = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}



