package conectate.com.data.repository

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

/**
 * 🔥 FIX CRÍTICO: Optimización de VideoRepository
 * Resuelve problemas de cache, timeouts y performance
 */
class OptimizedVideoRepository(
    private val baseRepository: VideoRepository,
    private val httpClient: HttpClient
) {
    
    // 🔥 MUTEX PARA OPERACIONES SEGURAS
    private val videoMutex = Mutex()
    private val cacheMutex = Mutex()
    
    // 🔥 CACHE INTELIGENTE CON TTL
    private val videoCache = mutableMapOf<String, CachedVideo>()
    private val cacheTimeout = 5 * 60 * 1000L // 5 minutos
    
    // 🔥 CONFIGURACIÓN DE TIMEOUTS
    private val connectTimeoutMs = 10_000L
    private val requestTimeoutMs = 30_000L
    private val socketTimeoutMs = 15_000L
    
    /**
     * 🔥 OBTENER VIDEOS CON CACHE INTELIGENTE
     */
    suspend fun getVideosOptimized(
        limit: Int = 20,
        offset: Int = 0,
        random: Boolean = false,
        category: String? = null
    ): Result<List<Video>> = videoMutex.withLock {
        try {
            var cacheKey = "videos_${limit}_${offset}_${random}_${category ?: "all"}"
            var currentTime = System.currentTimeMillis()
            
            // 🔥 VERIFICAR CACHE
            var cached = videoCache[cacheKey]
            if (cached != null && currentTime - cached.timestamp < cacheTimeout) {
                return Result.success(cached.videos)
            }
            
            // 🔥 MEDIR TIEMPO DE CARGA
            val loadTime = measureTimeMillis {
                var result = baseRepository.getVideos(limit, offset, random, category)
                
                result.onSuccess { videos ->
                    // 🔥 ACTUALIZAR CACHE
                    videoCache[cacheKey] = CachedVideo(videos, currentTime)
                    
                    // 🔥 LIMPIAR CACHE ANTIGUO
                    cleanupOldCache(currentTime)
                }
                
                result
            }
            
            android.util.Log.d("VideoRepository", "Videos loaded in ${loadTime}ms")
            
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error loading videos", e)
            Result.failure(Exception("Error cargando videos", e))
        }
    }
    
    /**
     * 🔥 SUBIR VIDEO CON TIMEOUTS ROBUSTOS
     */
    suspend fun uploadVideoOptimized(
        title: String,
        description: String,
        videoUri: android.net.Uri,
        thumbnailUri: android.net.Uri?,
        tags: List<String> = emptyList()
    ): Result<Video> = videoMutex.withLock {
        try {
            // 🔥 VALIDACIONES
            if (title.isBlank()) {
                return Result.failure(Exception("El título es requerido"))
            }
            
            if (title.length > 100) {
                return Result.failure(Exception("El título no puede exceder 100 caracteres"))
            }
            
            if (description.length > 500) {
                return Result.failure(Exception("La descripción no puede exceder 500 caracteres"))
            }
            
            // 🔥 SUBIR CON TIMEOUT
            var result = withTimeout(requestTimeoutMs) {
                baseRepository.uploadVideo(title, description, videoUri, thumbnailUri, tags)
            }
            
            // 🔥 INVALIDAR CACHE RELEVANTE
            invalidateVideoCache()
            
            result
            
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Timeout subiendo video. Intenta con un video más pequeño o verifica tu conexión."))
        } catch (e: Exception) {
            android.util.Log.e("VideoRepository", "Error uploading video", e)
            Result.failure(Exception("Error subiendo video", e))
        }
    }
    
    /**
     * 🔥 OBTENER VIDEO POR ID CON CACHE
     */
    suspend fun getVideoByIdOptimized(videoId: String): Result<Video> = videoMutex.withLock {
        try {
            var cacheKey = "video_$videoId"
            var currentTime = System.currentTimeMillis()
            
            // 🔥 VERIFICAR CACHE
            var cached = videoCache[cacheKey]
            if (cached != null && currentTime - cached.timestamp < cacheTimeout) {
                cached.videos.firstOrNull()?.let { video ->
                    return Result.success(video)
                }
            }
            
            // 🔥 CARGAR DESDE RED
            var result = withTimeout(requestTimeoutMs) {
                baseRepository.getVideoById(videoId)
            }
            
            result.onSuccess { video ->
                // 🔥 ACTUALIZAR CACHE
                videoCache[cacheKey] = CachedVideo(listOf(video), currentTime)
            }
            
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Timeout cargando video"))
        } catch (e: Exception) {
            Result.failure(Exception("Error cargando video", e))
        }
    }
    
    /**
     * 🔥 BUSCAR VIDEOS CON OPTIMIZACIÓN
     */
    suspend fun searchVideosOptimized(
        query: String,
        limit: Int = 20,
        offset: Int = 0
    ): Result<List<Video>> = videoMutex.withLock {
        try {
            if (query.isBlank()) {
                return Result.failure(Exception("La búsqueda no puede estar vacía"))
            }
            
            if (query.length < 2) {
                return Result.failure(Exception("La búsqueda debe tener al menos 2 caracteres"))
            }
            
            var cacheKey = "search_${query}_${limit}_${offset}"
            var currentTime = System.currentTimeMillis()
            
            // 🔥 VERIFICAR CACHE DE BÚSQUEDA
            var cached = videoCache[cacheKey]
            if (cached != null && currentTime - cached.timestamp < cacheTimeout) {
                return Result.success(cached.videos)
            }
            
            // 🔥 EJECUTAR BÚSQUEDA
            var result = withTimeout(requestTimeoutMs) {
                baseRepository.searchVideos(query, limit, offset)
            }
            
            result.onSuccess { videos ->
                // 🔥 GUARDAR EN CACHE
                videoCache[cacheKey] = CachedVideo(videos, currentTime)
            }
            
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Timeout en búsqueda"))
        } catch (e: Exception) {
            Result.failure(Exception("Error en búsqueda", e))
        }
    }
    
    /**
     * 🔥 OBTENER VIDEOS DE USUARIO SEGUIDO
     */
    suspend fun getFollowingVideosOptimized(
        limit: Int = 20,
        offset: Int = 0
    ): Result<List<Video>> = videoMutex.withLock {
        try {
            var cacheKey = "following_${limit}_${offset}"
            var currentTime = System.currentTimeMillis()
            
            // 🔥 CACHE PARA VIDEOS DE SEGUIDOS
            var cached = videoCache[cacheKey]
            if (cached != null && currentTime - cached.timestamp < cacheTimeout) {
                return Result.success(cached.videos)
            }
            
            // 🔥 CARGAR CON TIMEOUT
            var result = withTimeout(requestTimeoutMs) {
                baseRepository.getFollowingVideos(limit, offset)
            }
            
            result.onSuccess { videos ->
                videoCache[cacheKey] = CachedVideo(videos, currentTime)
            }
            
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Timeout cargando videos de seguidos"))
        } catch (e: Exception) {
            Result.failure(Exception("Error cargando videos de seguidos", e))
        }
    }
    
    /**
     * 🔥 ELIMINAR VIDEO CON LIMPIEZA DE CACHE
     */
    suspend fun deleteVideoOptimized(videoId: String): Result<Unit> = videoMutex.withLock {
        try {
            var result = baseRepository.deleteVideo(videoId)
            
            result.onSuccess {
                // 🔥 LIMPIAR CACHE RELACIONADO
                invalidateVideoCache()
                removeVideoFromCache(videoId)
            }
            
        } catch (e: Exception) {
            Result.failure(Exception("Error eliminando video", e))
        }
    }
    
    /**
     * 🔥 LIMPIEZA DE CACHE ANTIGUO
     */
    private suspend fun cleanupOldCache(currentTime: Long) = cacheMutex.withLock {
        val expiredKeys = videoCache.filter { (_, cached) ->
            currentTime - cached.timestamp > cacheTimeout
        }.keys
        
        expiredKeys.forEach { key ->
            videoCache.remove(key)
        }
        
        android.util.Log.d("VideoRepository", "Cleaned ${expiredKeys.size} expired cache entries")
    }
    
    /**
     * 🔥 INVALIDAR CACHE COMPLETO
     */
    private suspend fun invalidateVideoCache() = cacheMutex.withLock {
        videoCache.clear()
    }
    
    /**
     * 🔥 REMOVER VIDEO ESPECÍFICO DEL CACHE
     */
    private suspend fun removeVideoFromCache(videoId: String) = cacheMutex.withLock {
        val keysToRemove = videoCache.filter { (_, cached) ->
            cached.videos.any { it.id == videoId }
        }.keys
        
        keysToRemove.forEach { key ->
            videoCache.remove(key)
        }
    }
    
    /**
     * 🔥 OBTENER ESTADÍSTICAS DE CACHE
     */
    suspend fun getCacheStats(): VideoCacheStats = cacheMutex.withLock {
        VideoCacheStats(
            cacheSize = videoCache.size,
            totalVideosCached = videoCache.values.sumOf { it.videos.size },
            oldestEntry = videoCache.values.minOfOrNull { it.timestamp } ?: 0L,
            newestEntry = videoCache.values.maxOfOrNull { it.timestamp } ?: 0L
        )
    }
    
    /**
     * 🔥 PRE-CARGA POPULAR VIDEOS
     */
@Composable
@Composable
    suspend fun preloadPopularVideos(): Result<Unit> = videoMutex.withLock {
        try {
            // Cargar videos populares en background
            withContext(Dispatchers.IO) {
                getVideosOptimized(limit = 10, random = true)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Error en pre-carga", e))
        }
    }
}

/**
 * 🔥 DATOS DE CACHE
 */
data class CachedVideo(
    val videos: List<Video>,
    val timestamp: Long
)

data class VideoCacheStats(
    val cacheSize: Int,
    val totalVideosCached: Int,
    val oldestEntry: Long,
    val newestEntry: Long
)

/**
 * 🔥 WRAPPER PARA VideoRepository OPTIMIZADO
 */
class OptimizedVideoRepositoryWrapper(
    private val optimizedRepo: OptimizedVideoRepository
) : VideoRepository by optimizedRepo.baseRepository {
    
    override suspend fun getVideos(
        limit: Int,
        offset: Int,
        random: Boolean,
        category: String?
    ): Result<List<Video>> {
        return optimizedRepo.getVideosOptimized(limit, offset, random, category)
    }
    
    override suspend fun uploadVideo(
        title: String,
        description: String,
        videoUri: android.net.Uri,
        thumbnailUri: android.net.Uri?,
        tags: List<String>
    ): Result<Video> {
        return optimizedRepo.uploadVideoOptimized(title, description, videoUri, thumbnailUri, tags)
    }
    
    override suspend fun getVideoById(videoId: String): Result<Video> {
        return optimizedRepo.getVideoByIdOptimized(videoId)
    }
    
    override suspend fun searchVideos(
        query: String,
        limit: Int,
        offset: Int
    ): Result<List<Video>> {
        return optimizedRepo.searchVideosOptimized(query, limit, offset)
    }
    
    override suspend fun getFollowingVideos(limit: Int, offset: Int): Result<List<Video>> {
        return optimizedRepo.getFollowingVideosOptimized(limit, offset)
    }
    
    override suspend fun deleteVideo(videoId: String): Result<Unit> {
        return optimizedRepo.deleteVideoOptimized(videoId)
    }
}

/**
 * 🔥 MONITOR DE PERFORMANCE DE VIDEOS
 */
class VideoPerformanceMonitor {
    
    private val performanceMetrics = mutableMapOf<String, PerformanceMetric>()
    private val metricsMutex = Mutex()
    
    suspend fun recordLoadTime(
        operation: String,
        loadTimeMs: Long,
        success: Boolean
    ) = metricsMutex.withLock {
        val metric = performanceMetrics.getOrPut(operation) {
            PerformanceMetric(operation)
        }
        
        metric.recordLoad(loadTimeMs, success)
    }
    
    suspend fun getMetrics(): Map<String, PerformanceMetric> = metricsMutex.withLock {
        performanceMetrics.toMap()
    }
    
    suspend fun clearMetrics() = metricsMutex.withLock {
        performanceMetrics.clear()
    }
}

data class PerformanceMetric(
    val operation: String
) {
    private val loadTimes = mutableListOf<Long>()
    private val successCount = 0
    private val failureCount = 0
    
    fun recordLoad(loadTimeMs: Long, success: Boolean) {
        loadTimes.add(loadTimeMs)
        if (success) {
            successCount++
        } else {
            failureCount++
        }
    }
    
    val averageLoadTime: Long get() = if (loadTimes.isNotEmpty()) loadTimes.average().toLong() else 0L
    val successRate: Float get() = if (successCount + failureCount > 0) successCount.toFloat() / (successCount + failureCount) else 0f
    val totalOperations: Int get() = successCount + failureCount
}





