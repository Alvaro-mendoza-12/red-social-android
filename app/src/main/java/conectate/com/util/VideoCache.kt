package conectate.com.util

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import java.io.File

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object VideoCache {
    private var simpleCache: SimpleCache? = null
    private var databaseProvider: DatabaseProvider? = null

    @Synchronized
    fun getInstance(context: Context): SimpleCache {
        if (simpleCache == null) {
            val cacheSize = VideoPerformanceProfiles.detect(context).cacheSizeBytes
            val cacheEvictor = LeastRecentlyUsedCacheEvictor(cacheSize)
            databaseProvider = StandaloneDatabaseProvider(context)
            
            // Create cache directory
            val cacheDir = File(context.cacheDir, "media")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            simpleCache = SimpleCache(cacheDir, cacheEvictor, databaseProvider!!)
        }
        return simpleCache!!
    }

    fun getCacheDataSourceFactory(context: Context): CacheDataSource.Factory {
        val cache = getInstance(context)
        
        // Upstream factory (HTTP)
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            
        // Sink (File/Cache)
        val cacheSink = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            
        return cacheSink
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
