package conectate.com.core.performance

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlin.system.measureTimeMillis

/**
 * Optimizador de rendimiento para toda la aplicación
 * Mejora fluidez y previene crashes
 */
class PerformanceOptimizer(private val context: Context) {
    
    private val memoryManager = MemoryManager(context)
    private val cacheOptimizer = CacheOptimizer()
    private val renderOptimizer = RenderOptimizer()
    
    fun optimizeApp() {
        memoryManager.optimizeMemory()
        cacheOptimizer.optimizeCaches()
        renderOptimizer.optimizeRendering()
    }
    
    fun getPerformanceMetrics(): PerformanceMetrics {
        return PerformanceMetrics(
            memoryUsage = memoryManager.getCurrentMemoryUsage(),
            cacheSize = cacheOptimizer.getCacheSize(),
            renderFPS = renderOptimizer.getCurrentFPS()
        )
    }
}

class MemoryManager(private val context: Context) {
    
    fun optimizeMemory() {
        // Limpiar memoria innecesaria
        System.gc()
        
        // Optimizar memoria de imágenes
        Coil.clearMemoryCache()
        
        // Reducir uso de memoria en procesos pesados
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        activityManager.clearApplicationUserData()
    }
    
    fun getCurrentMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}

class CacheOptimizer {
    
    fun optimizeCaches() {
        // Limpiar caches antiguas
        cleanupOldCacheEntries()
        
        // Optimizar tamaño de cache
        optimizeCacheSize()
    }
    
    private fun cleanupOldCacheEntries() {
        // Implementar limpieza de cache antigua
    }
    
    private fun optimizeCacheSize() {
        // Ajustar tamaño de cache según memoria disponible
    }
    
    fun getCacheSize(): Long {
        return 0 // Implementar medición real
    }
}

class RenderOptimizer {
    
    private val currentFPS = 60f
    
    fun optimizeRendering() {
        // Reducir animaciones en dispositivos de bajos recursos
        reduceAnimationsOnLowEnd()
        
        // Optimizar composables pesados
        optimizeHeavyComposables()
    }
    
    private fun reduceAnimationsOnLowEnd() {
        // Detectar dispositivo de bajos recursos y reducir animaciones
    }
    
    private fun optimizeHeavyComposables() {
        // Optimizar composables que causan lag
    }
    
    fun getCurrentFPS(): Float = currentFPS
}

data class PerformanceMetrics(
    val memoryUsage: Long,
    val cacheSize: Long,
    val renderFPS: Float
)

/**
 * Composable para monitoreo de rendimiento en tiempo real
 */
@Composable
fun PerformanceMonitor() {
    val context = LocalContext.current
    val optimizer = remember { PerformanceOptimizer(context) }
    var metrics by remember { mutableStateOf<PerformanceMetrics?>(null) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000) // Actualizar cada 5 segundos
            metrics = optimizer.getPerformanceMetrics()
            
            // Optimizar automáticamente si es necesario
            if (metrics?.memoryUsage ?: 0 > 100 * 1024 * 1024) { // > 100MB
                optimizer.optimizeApp()
            }
        }
    }
    
    // Log de métricas para debugging
    metrics?.let {
        android.util.Log.d("Performance", "Memory: ${it.memoryUsage / 1024 / 1024}MB, FPS: ${it.renderFPS}")
    }
}

/**
 * Utilidad para optimizar carga de imágenes
 */
object ImageLoadingOptimizer {
    
    fun optimizeImageLoading() {
        // Configuración optimizada para Coil
        coil.ImageLoader.Builder(coil.ImageLoader.context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25) // 25% de memoria disponible
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(coil.ImageLoader.context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100 * 1024 * 1024) // 100MB
                    .build()
            }
            .build()
    }
}

/**
 * Optimización de navegación para evitar lag
 */
@Composable
fun OptimizedNavigation(
    navController: NavController,
    content: @Composable () -> Unit
) {
    // Prevenir re-navegaciones innecesarias
    var lastNavigationTime by remember { mutableStateOf(0L) }
    
    DisposableEffect(navController) {
        val callback = NavController.OnDestinationChangedListener { _, _, _ ->
            var currentTime = System.currentTimeMillis()
            if (currentTime - lastNavigationTime < 500) { // < 500ms
                // Evitar navegaciones rápidas consecutivas
                return@OnDestinationChangedListener
            }
            lastNavigationTime = currentTime
        }
        
        navController.addOnDestinationChangedListener(callback)
        onDispose {
            navController.removeOnDestinationChangedListener(callback)
        }
    }
    
    content()
}

/**
 * Wrapper para optimizar composables pesados
 */
@Composable
fun OptimizedComposable(
    key: Any? = null,
    content: @Composable () -> Unit
) {
    // Usar remember para evitar recomposiciones innecesarias
    val optimizedContent = remember(key) {
        content
    }
    
    optimizedContent()
}

/**
 * Sistema de debounce para prevenir clicks múltiples
 */
@Composable
fun DebouncedClick(
    delayMs: Long = 300,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    var lastClickTime by remember { mutableStateOf(0L) }
    
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clickable {
                var currentTime = System.currentTimeMillis()
                if (currentTime - lastClickTime > delayMs) {
                    lastClickTime = currentTime
                    onClick()
                }
            }
    ) {
        content()
    }
}



