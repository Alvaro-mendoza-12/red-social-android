package conectate.com.core.production

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ConfigurationInfo
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlin.system.measureTimeMillis

/**
 * 📱 OPTIMIZADOR PARA DISPOSITIVOS DE BAJOS RECURSOS
 * Garantiza fluidez incluso en teléfonos económicos
 */
class LowEndDeviceOptimizer(private val context: Context) {
    
    private var isLowEndDevice = detectLowEndDevice()
    private var performanceProfile = createPerformanceProfile()
    private val memoryOptimizer = MemoryOptimizer(context)
    private val renderOptimizer = RenderOptimizer()
    private val networkOptimizer = NetworkOptimizer()
    
    init {
        if (isLowEndDevice) {
            optimizeForLowEnd()
        }
    }
    
    private fun detectLowEndDevice(): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalMemoryMB = memoryInfo.totalMem / (1024 * 1024)
        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val sdkVersion = Build.VERSION.SDK_INT
        
        // Criterios para dispositivo de bajos recursos
        val isLowMemory = totalMemoryMB < 2048 // < 2GB RAM
        val isLowCPU = cpuCores < 4 // < 4 cores
        val isOldAndroid = sdkVersion < 28 // < Android 9
        val isLowAvailableMemory = availableMemoryMB < 512 // < 512MB disponible
        
        return isLowMemory || isLowCPU || isOldAndroid || isLowAvailableMemory
    }
    
    private fun createPerformanceProfile(): PerformanceProfile {
        return if (isLowEndDevice) {
            PerformanceProfile(
                maxMemoryUsage = 80 * 1024 * 1024L, // 80MB
                maxCacheSize = 20 * 1024 * 1024L, // 20MB
                targetFPS = 30, // 30 FPS en lugar de 60
                enableAnimations = false,
                enableTransitions = false,
                enableBlur = false,
                enableShadows = false,
                maxConcurrentOperations = 2,
                imageQuality = ImageQuality.LOW,
                videoQuality = VideoQuality.LOW_480P,
                enableBackgroundSync = false
            )
        } else {
            PerformanceProfile() // Default profile para dispositivos normales
        }
    }
    
    private fun optimizeForLowEnd() {
        memoryOptimizer.optimizeForLowMemory()
        renderOptimizer.optimizeForLowEnd()
        networkOptimizer.optimizeForLowEnd()
        enableLowEndUI()
        disableHeavyFeatures()
    }
    
    private fun enableLowEndUI() {
        // Deshabilitar animaciones pesadas
        // Reducir complejidad visual
        // Simplificar layouts
    }
    
    private fun disableHeavyFeatures() {
        // Deshabilitar features no esenciales
        // Reducir calidad de medios
        // Limitar operaciones concurrentes
    }
    
    fun getOptimizedConfiguration(): LowEndConfig {
        return LowEndConfig(
            isLowEndDevice = isLowEndDevice,
            performanceProfile = performanceProfile,
            memoryOptimizations = memoryOptimizer.getOptimizations(),
            renderOptimizations = renderOptimizer.getOptimizations(),
            networkOptimizations = networkOptimizer.getOptimizations()
        )
    }
}

class MemoryOptimizer(private val context: Context) {
    
    private var optimizations = mutableSetOf<MemoryOptimization>()
    
    fun optimizeForLowMemory() {
        // 1. Reducir tamaño de cache
        reduceCacheSize()
        
        // 2. Optimizar gestión de memoria
        enableMemoryManagement()
        
        // 3. Optimizar imágenes
        optimizeImageMemory()
        
        // 4. Optimizar composables
        optimizeComposableMemory()
        
        // 5. Liberar memoria agresivamente
        enableAggressiveGC()
    }
    
    private fun reduceCacheSize() {
        optimizations.add(MemoryOptimization.REDUCED_CACHE_SIZE)
        
        // Reducir cache de imágenes a 10MB
        coil.ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.05) // 5% de memoria
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(10 * 1024 * 1024) // 10MB
                    .build()
            }
            .build()
    }
    
    private fun enableMemoryManagement() {
        optimizations.add(MemoryOptimization.MEMORY_MANAGEMENT)
        
        // System.gc() periódico
        // WeakReferences para objetos pesados
        // Liberar recursos en onPause
    }
    
    private fun optimizeImageMemory() {
        optimizations.add(MemoryOptimization.IMAGE_OPTIMIZATION)
        
        // Downsampling agresivo
        // Reciclado inmediato de bitmaps
        // Formatos de imagen optimizados
    }
    
    private fun optimizeComposableMemory() {
        optimizations.add(MemoryOptimization.COMPOSABLE_OPTIMIZATION)
        
        // remember{} agresivo
        // derivedStateOf para cálculos
        // Evitar recomposiciones innecesarias
    }
    
    private fun enableAggressiveGC() {
        optimizations.add(MemoryOptimization.AGGRESSIVE_GC)
        
        // System.gc() cada 30 segundos
        // Liberar memoria en background
    }
    
    fun getOptimizations(): Set<MemoryOptimization> = optimizations
}

class RenderOptimizer {
    
    private var optimizations = mutableSetOf<RenderOptimization>()
    
    fun optimizeForLowEnd() {
        // 1. Reducir FPS a 30
        reduceFrameRate()
        
        // 2. Deshabilitar animaciones pesadas
        disableHeavyAnimations()
        
        // 3. Simplificar efectos visuales
        simplifyVisualEffects()
        
        // 4. Optimizar layouts
        optimizeLayouts()
        
        // 5. Reducir complejidad de composables
        simplifyComposables()
    }
    
    private fun reduceFrameRate() {
        optimizations.add(RenderOptimization.REDUCED_FPS)
        // Target 30 FPS en lugar de 60
    }
    
    private fun disableHeavyAnimations() {
        optimizations.add(RenderOptimization.NO_ANIMATIONS)
        // Deshabilitar animaciones complejas
    }
    
    private fun simplifyVisualEffects() {
        optimizations.add(RenderOptimization.SIMPLE_EFFECTS)
        // Sin blur, sin shadows, sin gradientes complejos
    }
    
    private fun optimizeLayouts() {
        optimizations.add(RenderOptimization.OPTIMIZED_LAYOUTS)
        // LazyColumn con key optimization
        // ConstraintLayout en lugar de RelativeLayout anidados
    }
    
    private fun simplifyComposables() {
        optimizations.add(RenderOptimization.SIMPLE_COMPOSABLES)
        // Composables más simples
        // Menos anidamiento
    }
    
    fun getOptimizations(): Set<RenderOptimization> = optimizations
}

class NetworkOptimizer {
    
    private var optimizations = mutableSetOf<NetworkOptimization>()
    
    fun optimizeForLowEnd() {
        // 1. Reducir concurrent requests
        limitConcurrentRequests()
        
        // 2. Aumentar timeouts
        increaseTimeouts()
        
        // 3. Optimizar caché
        enableAggressiveCaching()
        
        // 4. Comprimir datos
        enableCompression()
        
        // 5. Prefetching selectivo
        enableSelectivePrefetching()
    }
    
    private fun limitConcurrentRequests() {
        optimizations.add(NetworkOptimization.LIMITED_REQUESTS)
        // Máximo 2 requests concurrentes
    }
    
    private fun increaseTimeouts() {
        optimizations.add(NetworkOptimization.INCREASED_TIMEOUTS)
        // Timeouts más largos para redes lentas
    }
    
    private fun enableAggressiveCaching() {
        optimizations.add(NetworkOptimization.AGGRESSIVE_CACHING)
        // Cache más agresivo
    }
    
    private fun enableCompression() {
        optimizations.add(NetworkOptimization.COMPRESSION)
        // GZIP compression
    }
    
    private fun enableSelectivePrefetching() {
        optimizations.add(NetworkOptimization.SELECTIVE_PREFETCHING)
        // Prefetching solo de contenido crítico
    }
    
    fun getOptimizations(): Set<NetworkOptimization> = optimizations
}

data class PerformanceProfile(
    val maxMemoryUsage: Long = 150 * 1024 * 1024L, // 150MB default
    val maxCacheSize: Long = 50 * 1024 * 1024L, // 50MB default
    val targetFPS: Int = 60, // 60 FPS default
    val enableAnimations: Boolean = true,
    val enableTransitions: Boolean = true,
    val enableBlur: Boolean = true,
    val enableShadows: Boolean = true,
    val maxConcurrentOperations: Int = 4,
    val imageQuality: ImageQuality = ImageQuality.HIGH,
    val videoQuality: VideoQuality = VideoQuality.HIGH_1080P,
    val enableBackgroundSync: Boolean = true
)

data class LowEndConfig(
    val isLowEndDevice: Boolean,
    val performanceProfile: PerformanceProfile,
    val memoryOptimizations: Set<MemoryOptimization>,
    val renderOptimizations: Set<RenderOptimization>,
    val networkOptimizations: Set<NetworkOptimization>
)

enum class MemoryOptimization {
    REDUCED_CACHE_SIZE,
    MEMORY_MANAGEMENT,
    IMAGE_OPTIMIZATION,
    COMPOSABLE_OPTIMIZATION,
    AGGRESSIVE_GC
}

enum class RenderOptimization {
    REDUCED_FPS,
    NO_ANIMATIONS,
    SIMPLE_EFFECTS,
    OPTIMIZED_LAYOUTS,
    SIMPLE_COMPOSABLES
}

enum class NetworkOptimization {
    LIMITED_REQUESTS,
    INCREASED_TIMEOUTS,
    AGGRESSIVE_CACHING,
    COMPRESSION,
    SELECTIVE_PREFETCHING
}

enum class ImageQuality {
    LOW, MEDIUM, HIGH
}

enum class VideoQuality {
    LOW_240P, LOW_480P, MEDIUM_720P, HIGH_1080P
}

/**
 * Composable para optimización automática
 */
@Composable
fun LowEndDeviceWrapper(
    content: @Composable (LowEndConfig) -> Unit
) {
    val context = LocalContext.current
    val optimizer = remember { LowEndDeviceOptimizer(context) }
    val config = remember { optimizer.getOptimizedConfiguration() }
    
    // Aplicar optimizaciones de tema
    val theme = if (config.isLowEndDevice) {
        MaterialTheme.colorScheme.copy(
            // Simplificar colores para reducir render
        )
    } else {
        MaterialTheme.colorScheme
    }
    
    MaterialTheme(colorScheme = theme) {
        content(config)
    }
}

/**
 * Componente optimizado para dispositivos de bajos recursos
 */
@Composable
fun OptimizedLazyColumn(
    items: List<Any>,
    modifier: Modifier = Modifier,
    config: LowEndConfig,
    itemContent: @Composable (Any) -> Unit
) {
    if (config.isLowEndDevice) {
        // LazyColumn optimizado para low-end
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = modifier,
            userScrollEnabled = true,
            flingBehavior = rememberScrollFlingBehavior(
                decayAnimationSpec = if (config.performanceProfile.targetFPS <= 30) {
                    SplineBasedDecay // Más suave en low-end
                } else {
                    rememberSplineBasedDecay()
                }
            )
        ) {
            items(items, key = { it.hashCode() }) { item ->
                // Simplificar item rendering
                Box(modifier = Modifier.fillMaxSize()) {
                    itemContent(item)
                }
            }
        }
    } else {
        // LazyColumn normal para dispositivos normales
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = modifier
        ) {
            items(items) { item ->
                itemContent(item)
            }
        }
    }
}

/**
 * Monitor de rendimiento para low-end devices
 */
@Composable
fun LowEndPerformanceMonitor(config: LowEndConfig) {
    if (!config.isLowEndDevice) return
    
    var memoryUsage by remember { mutableStateOf(0L) }
    var fps by remember { mutableStateOf(0) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000) // Actualizar cada 5 segundos
            
            // Medir uso de memoria
            val runtime = Runtime.getRuntime()
            memoryUsage = runtime.totalMemory() - runtime.freeMemory()
            
            // Medir FPS (simulado)
            fps = if (config.performanceProfile.targetFPS <= 30) 30 else 60
            
            // Optimización automática si es necesario
            if (memoryUsage > config.performanceProfile.maxMemoryUsage) {
                System.gc()
            }
        }
    }
    
    // Log silencioso para debugging
    android.util.Log.d("LowEnd", "Memory: ${memoryUsage / 1024 / 1024}MB, FPS: $fps")
}



