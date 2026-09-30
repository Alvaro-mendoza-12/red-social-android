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
    AppDeviceTier.LOW -> LiveCollaborationQualityProfile(640, 360, 60, 900_000, false)
    AppDeviceTier.BALANCED -> LiveCollaborationQualityProfile(960, 540, 60, 1_800_000, true)
    AppDeviceTier.HIGH -> LiveCollaborationQualityProfile(1280, 720, 60, 3_400_000, true)
}
