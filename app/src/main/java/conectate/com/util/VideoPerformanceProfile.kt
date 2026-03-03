package conectate.com.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.max

enum class VideoDeviceTier {
    LOW,
    BALANCED,
    HIGH
}

data class VideoPerformanceProfile(
    val tier: VideoDeviceTier,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playBufferMs: Int,
    val rebufferMs: Int,
    val feedBeyondBoundsPageCount: Int,
    val cacheSizeBytes: Long,
    val editorOverlayLimit: Int,
    val editorPixelsPerSecond: Float,
    val autoExportHeight: Int,
    val autoExportBitrate: Int,
    val editorMaxSpeed: Float,
    val enableRealtimeEffectsPreview: Boolean,
    val label: String
)

object VideoPerformanceProfiles {
    fun detect(context: Context): VideoPerformanceProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val totalRamGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val memoryClassMb = max(activityManager.memoryClass, 0)
        val cores = max(Runtime.getRuntime().availableProcessors(), 1)

        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()

        val isInfinix = manufacturer.contains("infinix") || brand.contains("infinix")
        val isInfinixG30Family = isInfinix && (
            model.contains("g30") || model.contains("x663") || device.contains("x663")
        )

        val lowTier = isInfinixG30Family ||
            totalRamGb <= 4.0 ||
            memoryClassMb <= 192 ||
            cores <= 4

        val highTier = !lowTier &&
            totalRamGb >= 8.0 &&
            memoryClassMb >= 256 &&
            cores >= 8 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        return when {
            lowTier -> VideoPerformanceProfile(
                tier = VideoDeviceTier.LOW,
                minBufferMs = 1_000,
                maxBufferMs = 10_000,
                playBufferMs = 350,
                rebufferMs = 700,
                feedBeyondBoundsPageCount = 1,
                cacheSizeBytes = 128L * 1024L * 1024L,
                editorOverlayLimit = 4,
                editorPixelsPerSecond = 14f,
                autoExportHeight = 720,
                autoExportBitrate = 2_600_000,
                editorMaxSpeed = 2.0f,
                enableRealtimeEffectsPreview = false,
                label = "Rendimiento"
            )

            highTier -> VideoPerformanceProfile(
                tier = VideoDeviceTier.HIGH,
                minBufferMs = 500,
                maxBufferMs = 20_000,
                playBufferMs = 150,
                rebufferMs = 350,
                feedBeyondBoundsPageCount = 3,
                cacheSizeBytes = 512L * 1024L * 1024L,
                editorOverlayLimit = 16,
                editorPixelsPerSecond = 28f,
                autoExportHeight = 1080,
                autoExportBitrate = 8_000_000,
                editorMaxSpeed = 4.0f,
                enableRealtimeEffectsPreview = true,
                label = "Alta calidad"
            )

            else -> VideoPerformanceProfile(
                tier = VideoDeviceTier.BALANCED,
                minBufferMs = 800,
                maxBufferMs = 15_000,
                playBufferMs = 250,
                rebufferMs = 500,
                feedBeyondBoundsPageCount = 2,
                cacheSizeBytes = 256L * 1024L * 1024L,
                editorOverlayLimit = 8,
                editorPixelsPerSecond = 20f,
                autoExportHeight = 1080,
                autoExportBitrate = 4_600_000,
                editorMaxSpeed = 3.0f,
                enableRealtimeEffectsPreview = true,
                label = "Equilibrado"
            )
        }
    }
}
