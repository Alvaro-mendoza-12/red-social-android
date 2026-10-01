package conectate.com.util

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import conectate.com.data.localfallback.UbuntuStorageUrl

/** Audio previews reuse the already loaded video bytes and the shared HTTP/2 client. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object SoundPreviewPlayback {
    const val START_BUFFER_MS = 250
    const val REBUFFER_MS = 750

    fun mediaItem(url: String): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setCustomCacheKey(UbuntuStorageUrl.stableCacheKey(url) ?: url)
        .build()

    fun create(context: Context): ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 8_000, START_BUFFER_MS, REBUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build())
        .setMediaSourceFactory(DefaultMediaSourceFactory(VideoCache.getCacheDataSourceFactory(context)))
        .build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }
}
