package conectate.com.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** Retain explicit one-shot playback for viewers that opt out of repetition. */
internal fun Player.useSingleProfilePlayback() { repeatMode = Player.REPEAT_MODE_OFF }

/** Visible avatar clips repeat through the player timeline, without polling or seek timers. */
internal fun Player.useLoopingProfilePlayback() { repeatMode = Player.REPEAT_MODE_ONE }

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun profileAvatarPreviewItem(source: String, startMs: Long, endMs: Long): MediaItem {
    require(startMs >= 0 && endMs > startMs)
    return MediaItem.Builder().setUri(source).setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
    ).build()
}
