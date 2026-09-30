package conectate.com.data.livekit

import conectate.com.util.AppDeviceTier

internal data class LiveCollaborationQualityProfile(
    val width: Int,
    val height: Int,
    val maxFps: Int,
    val maxBitrate: Int,
    val simulcast: Boolean
)

/** Small devices should not encode three simultaneous 720p streams by default. */
internal fun liveCollaborationQualityProfile(tier: AppDeviceTier) = when (tier) {
    AppDeviceTier.LOW -> LiveCollaborationQualityProfile(640, 360, 24, 450_000, false)
    AppDeviceTier.BALANCED -> LiveCollaborationQualityProfile(960, 540, 24, 900_000, true)
    AppDeviceTier.HIGH -> LiveCollaborationQualityProfile(1280, 720, 30, 1_700_000, true)
}
