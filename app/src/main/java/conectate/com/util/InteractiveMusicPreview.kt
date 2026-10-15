package conectate.com.util

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl

/** A short window in memory; no persistent music downloads or autoplay. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class InteractiveMusicPreview(context: Context) {
    companion object {
        const val MAX_PRELOADED_TRACKS = 3
        const val PRELOAD_DURATION_MS = 1_500L
        const val PRELOAD_BUFFER_BYTES = 1_048_576
    }

    private var preloadAllowed = false
    private val builder = DefaultPreloadManager.Builder(context,
        TargetPreloadStatusControl<Int, DefaultPreloadManager.PreloadStatus> {
            if (preloadAllowed) DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(PRELOAD_DURATION_MS) else DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_NOT_PRELOADED
        })
        .setMediaSourceFactory(DefaultMediaSourceFactory(MediaPlaybackDataSources.interactiveAudioFactory(context)))
        .setLoadControl(DefaultLoadControl.Builder()
            .setBufferDurationsMs(InteractiveAudioBuffer.MIN_MS, InteractiveAudioBuffer.MAX_MS,
                InteractiveAudioBuffer.START_MS, InteractiveAudioBuffer.REBUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setPlayerTargetBufferBytes("preload", PRELOAD_BUFFER_BYTES)
            .build())
    private val preloadManager = builder.build()
    val player: ExoPlayer = builder.buildExoPlayer(ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true))
    private var preparedItems = emptyList<MediaItem>()

    fun preload(items: List<MediaItem>) {
        val bounded = items.distinct().take(MAX_PRELOADED_TRACKS)
        preloadAllowed = true
        if (bounded == preparedItems) { preloadManager.invalidate(); return }
        preparedItems.filterNot { it in bounded }.forEach(preloadManager::remove)
        bounded.forEachIndexed { index, item ->
            if (item !in preparedItems) preloadManager.add(item, index)
        }
        preparedItems = bounded
        preloadManager.setCurrentPlayingIndex(0)
        preloadManager.invalidate()
    }

    fun mediaSource(item: MediaItem): MediaSource? =
        if (item in preparedItems) preloadManager.getMediaSource(item) else null

    fun prioritizePlayback() {
        preloadAllowed = false
        preloadManager.invalidate()
    }

    fun clearPreload() {
        preloadAllowed = false
        preparedItems = emptyList()
        preloadManager.reset()
    }

    fun release() {
        player.release()
        preloadManager.release()
    }
}
