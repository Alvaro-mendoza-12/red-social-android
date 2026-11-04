package conectate.com.core.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** A profile animation plays once; returning from the background does not seek it to the start. */
internal fun Player.useSingleProfilePlayback() { repeatMode = Player.REPEAT_MODE_OFF }

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun profileAvatarPreviewItem(source: String, startMs: Long, endMs: Long): MediaItem {
    require(startMs >= 0 && endMs > startMs)
    return MediaItem.Builder().setUri(source).setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
    ).build()
}
