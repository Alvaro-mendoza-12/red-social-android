package conectate.com.data.webrtc

import conectate.com.util.AppDeviceTier

internal data class NativeCameraProfile(val width: Int, val height: Int, val fps: Int)

/** Bounds encoding cost while preserving orientation and a user's lower resolution. */
internal fun nativeCameraProfile(
    tier: AppDeviceTier,
    requestedWidth: Int = 960,
    requestedHeight: Int = 540,
    requestedFps: Int = 24
): NativeCameraProfile {
    val (longEdge, shortEdge, maxFps) = when (tier) {
        AppDeviceTier.LOW -> Triple(640, 360, 24)
        AppDeviceTier.BALANCED -> Triple(960, 540, 24)
        AppDeviceTier.HIGH -> Triple(1280, 720, 30)
    }
    val width = requestedWidth.coerceAtLeast(2)
    val height = requestedHeight.coerceAtLeast(2)
    val portrait = height > width
    val scale = minOf(1.0, (if (portrait) shortEdge else longEdge).toDouble() / width,
        (if (portrait) longEdge else shortEdge).toDouble() / height)
    fun even(value: Double) = ((value.toInt() / 2) * 2).coerceAtLeast(2)
    return NativeCameraProfile(even(width * scale), even(height * scale), requestedFps.coerceIn(10, maxFps))
}

/** A fallback must never increase camera work after the preferred profile failed. */
internal fun nativeCameraFallbacks(profile: NativeCameraProfile): List<NativeCameraProfile> =
    listOf(1.0, 0.75, 0.5).map { scale ->
        fun even(value: Int) = (((value * scale).toInt() / 2) * 2).coerceAtLeast(2)
        NativeCameraProfile(even(profile.width), even(profile.height), profile.fps)
    }.distinct()
