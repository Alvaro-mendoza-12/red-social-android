package conectate.com.util

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** Same bounded cache, network stack and device budget for secondary video surfaces. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
object CachedVideoPlayback {
    fun create(context: Context): ExoPlayer {
        val profile = VideoPerformanceProfiles.detect(context)
        return ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(profile.minBufferMs, profile.maxBufferMs, profile.playBufferMs, profile.rebufferMs)
                .setPrioritizeTimeOverSizeThresholds(true).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(VideoCache.getCacheDataSourceFactory(context)))
            .build()
    }
}
