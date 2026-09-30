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
    val (longEdge, shortEdge) = when (tier) {
        AppDeviceTier.LOW -> 640 to 360
        AppDeviceTier.BALANCED -> 960 to 540
        AppDeviceTier.HIGH -> 1280 to 720
    }
    val width = requestedWidth.coerceAtLeast(2)
    val height = requestedHeight.coerceAtLeast(2)
    val portrait = height > width
    val scale = minOf(1.0, (if (portrait) shortEdge else longEdge).toDouble() / width,
        (if (portrait) longEdge else shortEdge).toDouble() / height)
    fun even(value: Double) = ((value.toInt() / 2) * 2).coerceAtLeast(2)
    return NativeCameraProfile(even(width * scale), even(height * scale), requestedFps.coerceIn(10, 60))
}

internal data class NativeCameraFormat(val width: Int, val height: Int, val maxFps: Int)

/** Prefer a 60 FPS format within the encoding budget; never advertise unsupported cadence. */
internal fun supportedNativeCameraProfile(
    requested: NativeCameraProfile,
    formats: List<NativeCameraFormat>
): NativeCameraProfile {
    val valid = formats.filter { it.width > 0 && it.height > 0 && it.maxFps > 0 }
    if (valid.isEmpty()) return requested.copy(fps = minOf(requested.fps, 30))
    val targetLong = maxOf(requested.width, requested.height)
    val targetShort = minOf(requested.width, requested.height)
    val fast = valid.filter { it.maxFps >= requested.fps &&
        maxOf(it.width, it.height) <= targetLong && minOf(it.width, it.height) <= targetShort }
    val chosen = (fast.ifEmpty { valid }).minBy {
        kotlin.math.abs(maxOf(it.width, it.height) - targetLong) +
            kotlin.math.abs(minOf(it.width, it.height) - targetShort)
    }
    val longEdge = minOf(targetLong, maxOf(chosen.width, chosen.height))
    val shortEdge = minOf(targetShort, minOf(chosen.width, chosen.height))
    return if (requested.height > requested.width) {
        NativeCameraProfile(shortEdge / 2 * 2, longEdge / 2 * 2, minOf(requested.fps, chosen.maxFps))
    } else {
        NativeCameraProfile(longEdge / 2 * 2, shortEdge / 2 * 2, minOf(requested.fps, chosen.maxFps))
    }
}

/** A fallback must never increase camera work after the preferred profile failed. */
internal fun nativeCameraFallbacks(profile: NativeCameraProfile): List<NativeCameraProfile> =
    listOf(1.0, 0.75, 0.5).map { scale ->
        fun even(value: Int) = (((value * scale).toInt() / 2) * 2).coerceAtLeast(2)
        NativeCameraProfile(even(profile.width), even(profile.height), profile.fps)
    }.distinct()
