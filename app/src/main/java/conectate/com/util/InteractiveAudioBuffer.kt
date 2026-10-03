package conectate.com.util

import androidx.media3.exoplayer.DefaultLoadControl

/** Start short previews promptly while keeping a larger reservoir once playing. */
internal object InteractiveAudioBuffer {
    const val START_MS = 300
    const val REBUFFER_MS = 1_000
    const val MIN_MS = 10_000
    const val MAX_MS = 60_000

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun loadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(MIN_MS, MAX_MS, START_MS, REBUFFER_MS)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
}
