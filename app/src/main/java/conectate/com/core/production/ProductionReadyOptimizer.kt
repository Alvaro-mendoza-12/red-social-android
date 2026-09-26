package conectate.com.core.production

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optimizador sobrio para producción.
 *
 * Mantiene una base segura para app start, presión de memoria y chequeos
 * básicos del proceso sin depender de módulos experimentales.
 */
class ProductionReadyOptimizer(private val context: Context) {

    private val initialized = AtomicBoolean(false)
    private val lastStatus = java.util.concurrent.atomic.AtomicReference(
        ProductionStatus()
    )

    fun initializeForProduction() {
        if (!initialized.compareAndSet(false, true)) return
        warmCriticalPaths()
        trimTransientCaches()
        lastStatus.set(captureStatus())
    }

    fun onTrimMemory(level: Int) {
        // Android 14 dejó de emitir los antiguos niveles RUNNING_* y Android 15
        // los deprecó. UI_HIDDEN/BACKGROUND siguen siendo señales estables y,
        // además, evitan desalojar recursos mientras el usuario reproduce video.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) trimForMemoryPressure()
    }

    fun onLowMemory() {
        trimForMemoryPressure()
    }

    fun getProductionStatus(): ProductionStatus {
        val status = captureStatus()
        lastStatus.set(status)
        return status
    }

    private fun warmCriticalPaths() {
        runCatching {
            context.cacheDir.mkdirs()
            context.filesDir.mkdirs()
        }
    }

    private fun trimTransientCaches() {
        // Nunca borres la raíz completa de cacheDir: allí pueden coexistir un
        // export de video todavía en subida, un audio pendiente o una lectura
        // de Media3. Solo los productores que opten por este directorio
        // exclusivo pueden ser desalojados por presión de memoria.
        runCatching {
            context.cacheDir
                .resolve("novvin-transient")
                .listFiles()
                ?.forEach { file -> file.takeIf { it.isFile }?.delete() }
        }
    }

    private fun trimForMemoryPressure() {
        trimTransientCaches()
        lastStatus.set(captureStatus())
    }

    private fun captureStatus(): ProductionStatus {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory().coerceAtLeast(1L)
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val memoryUsagePercent = ((usedMemory.toDouble() / maxMemory.toDouble()) * 100.0)
            .coerceIn(0.0, 100.0)
            .toFloat()

        val nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024f * 1024f)
        val cacheReadable = context.cacheDir.exists() && context.cacheDir.canRead()
        val filesReadable = context.filesDir.exists() && context.filesDir.canRead()

        val isStable = cacheReadable && filesReadable && memoryUsagePercent < 92f
        val performanceScore = (100f - (memoryUsagePercent * 0.55f) - (nativeHeapMb * 0.08f))
            .coerceIn(0f, 100f)

        return ProductionStatus(
            isStable = isStable,
            memoryUsage = memoryUsagePercent,
            crashRate = 0f,
            performanceScore = performanceScore,
            isReadyForProduction = isStable && performanceScore >= 60f
        )
    }
}

data class ProductionStatus(
    val isStable: Boolean = true,
    val memoryUsage: Float = 0f,
    val crashRate: Float = 0f,
    val performanceScore: Float = 100f,
    val isReadyForProduction: Boolean = true
)
