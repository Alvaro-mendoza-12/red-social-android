package conectate.com.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.max

enum class AppDeviceTier {
    LOW,
    BALANCED,
    HIGH
}

data class AppPerformanceProfile(
    val tier: AppDeviceTier,
    val label: String,
    val memoryCachePercent: Double,
    val diskCachePercent: Double,
    val reduceMotion: Boolean,
    val preferCompactUi: Boolean,
    val voiceGridMinSizeDp: Int,
    val voiceMaxFloatingReactions: Int,
    val voiceCameraWidth: Int,
    val voiceCameraHeight: Int,
    val voiceCameraFps: Int,
    val voiceScreenWidth: Int,
    val voiceScreenHeight: Int,
    val voiceScreenFps: Int,
    val warmUpCinemaCatalog: Boolean
)

object AppPerformanceProfiles {
    fun detect(context: Context): AppPerformanceProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val totalRamGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val memoryClassMb = max(activityManager.memoryClass, 0)
        val cores = max(Runtime.getRuntime().availableProcessors(), 1)
        val isLowRamDevice = activityManager.isLowRamDevice

        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()

        val isInfinix = manufacturer.contains("infinix") || brand.contains("infinix")
        val isInfinixG30Family = isInfinix && (
            model.contains("g30") || model.contains("x663") || device.contains("x663")
        )

        val lowTier = isInfinixG30Family ||
            isLowRamDevice ||
            totalRamGb <= 4.0 ||
            memoryClassMb <= 192 ||
            cores <= 4

        val highTier = !lowTier &&
            totalRamGb >= 8.0 &&
            memoryClassMb >= 256 &&
            cores >= 8 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        return when {
            lowTier -> AppPerformanceProfile(
                tier = AppDeviceTier.LOW,
                label = "Rendimiento",
                memoryCachePercent = 0.08,
                diskCachePercent = 0.02, // v22.4: Reduced from 0.05 to 0.02 to prevent storage exhaustion
                reduceMotion = true,
                preferCompactUi = true,
                voiceGridMinSizeDp = 112,
                voiceMaxFloatingReactions = 4,
                voiceCameraWidth = 640,
                voiceCameraHeight = 360,
                voiceCameraFps = 15,
                voiceScreenWidth = 960,
                voiceScreenHeight = 540,
                voiceScreenFps = 12,
                warmUpCinemaCatalog = false
            )

            highTier -> AppPerformanceProfile(
                tier = AppDeviceTier.HIGH,
                label = "Alta calidad",
                memoryCachePercent = 0.18,
                diskCachePercent = 0.08,
                reduceMotion = false,
                preferCompactUi = false,
                voiceGridMinSizeDp = 148,
                voiceMaxFloatingReactions = 8,
                voiceCameraWidth = 1280,
                voiceCameraHeight = 720,
                voiceCameraFps = 30,
                voiceScreenWidth = 1440,
                voiceScreenHeight = 810,
                voiceScreenFps = 18,
                warmUpCinemaCatalog = true
            )

            else -> AppPerformanceProfile(
                tier = AppDeviceTier.BALANCED,
                label = "Equilibrado",
                memoryCachePercent = 0.16,
                diskCachePercent = 0.07,
                reduceMotion = false,
                preferCompactUi = false,
                voiceGridMinSizeDp = 132,
                voiceMaxFloatingReactions = 6,
                voiceCameraWidth = 960,
                voiceCameraHeight = 540,
                voiceCameraFps = 24,
                voiceScreenWidth = 1280,
                voiceScreenHeight = 720,
                voiceScreenFps = 15,
                warmUpCinemaCatalog = true
            )
        }
    }
}


