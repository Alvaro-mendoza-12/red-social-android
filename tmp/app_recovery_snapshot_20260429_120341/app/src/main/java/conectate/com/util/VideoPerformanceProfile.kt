package conectate.com.util

import android.content.Context

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
        return when (AppPerformanceProfiles.detect(context).tier) {
            AppDeviceTier.LOW -> VideoPerformanceProfile(
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

            AppDeviceTier.HIGH -> VideoPerformanceProfile(
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

            AppDeviceTier.BALANCED -> VideoPerformanceProfile(
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
