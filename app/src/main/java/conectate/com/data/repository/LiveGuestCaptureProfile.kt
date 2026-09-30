package conectate.com.data.repository

import conectate.com.data.livekit.liveCollaborationQualityProfile
import conectate.com.data.model.LiveQuality
import conectate.com.util.AppDeviceTier

internal data class LiveGuestCaptureProfile(val width: Int, val height: Int, val fps: Int)

/** Secondary guest capture follows the device budget instead of always opening 720p. */
internal fun nativeLiveGuestCaptureProfile(tier: AppDeviceTier, requested: LiveQuality): LiveGuestCaptureProfile {
    val budget = liveCollaborationQualityProfile(tier)
    val scale = minOf(1.0, budget.width.toDouble() / requested.width, budget.height.toDouble() / requested.height)
    fun even(value: Int) = (value / 2) * 2
    return LiveGuestCaptureProfile(even((requested.width * scale).toInt()), even((requested.height * scale).toInt()),
        budget.maxFps)
}
