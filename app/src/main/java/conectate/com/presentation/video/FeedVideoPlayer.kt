package conectate.com.presentation.video

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import conectate.com.util.VideoCache
import conectate.com.util.VideoPerformanceProfile

@androidx.annotation.OptIn(UnstableApi::class)
internal fun createFeedVideoPlayer(context: Context, profile: VideoPerformanceProfile): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(profile.minBufferMs, profile.maxBufferMs, profile.playBufferMs, profile.rebufferMs)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    return ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(DefaultMediaSourceFactory(VideoCache.feedDataSourceFactory(context)))
        .build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = false
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
        }
}
