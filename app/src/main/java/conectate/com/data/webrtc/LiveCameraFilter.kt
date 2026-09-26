package conectate.com.data.webrtc

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Camera-only looks for a live broadcast. Screen sharing intentionally bypasses
 * this pipeline so text and UI colors remain faithful to the shared screen.
 */
enum class LiveCameraFilter(val wireValue: String) {
    NONE("none"),
    WARM("warm"),
    COOL("cool"),
    VIVID("vivid"),
    SOFT("soft"),
    MONO("mono");

    companion object {
        fun fromWireValue(value: String?): LiveCameraFilter =
            values().firstOrNull { it.wireValue == value?.trim()?.lowercase() } ?: NONE
    }
}

data class LiveCameraAdjustments(
    val enabled: Boolean = false,
    val smooth: Float = 0.35f,
    val brightness: Float = 0.45f,
    val warmth: Float = 0.50f,
) {
    fun normalized(): LiveCameraAdjustments = copy(
        smooth = smooth.coerceIn(0f, 1f),
        brightness = brightness.coerceIn(0f, 1f),
        warmth = warmth.coerceIn(0f, 1f),
    )

    val hasVisibleEffect: Boolean
        get() = enabled && (
            smooth > 0.01f ||
                kotlin.math.abs(brightness - 0.5f) > 0.01f ||
                kotlin.math.abs(warmth - 0.5f) > 0.01f
            )
}

internal object LiveCameraFilterColorMath {
    fun luma(
        value: Int,
        filter: LiveCameraFilter,
        adjustments: LiveCameraAdjustments = LiveCameraAdjustments(enabled = false),
    ): Int {
        val filtered = when (filter) {
        LiveCameraFilter.WARM -> value + 3
        LiveCameraFilter.COOL -> value
        LiveCameraFilter.VIVID -> contrast(value, 1.14f, 3)
        LiveCameraFilter.SOFT -> contrast(value, 0.90f, 8)
        LiveCameraFilter.MONO,
        LiveCameraFilter.NONE -> value
        }
        if (!adjustments.enabled) return filtered.coerceIn(0, 255)
        val smoothMultiplier = 1f - (adjustments.smooth.coerceIn(0f, 1f) * 0.12f)
        val brightnessLift = ((adjustments.brightness.coerceIn(0f, 1f) - 0.5f) * 48f).roundToInt()
        return contrast(filtered, smoothMultiplier, brightnessLift).coerceIn(0, 255)
    }

    fun chroma(
        value: Int,
        filter: LiveCameraFilter,
        isUPlane: Boolean,
        adjustments: LiveCameraAdjustments = LiveCameraAdjustments(enabled = false),
    ): Int {
        val filtered = when (filter) {
        LiveCameraFilter.WARM -> value + if (isUPlane) -12 else 18
        LiveCameraFilter.COOL -> value + if (isUPlane) 18 else -10
        LiveCameraFilter.VIVID -> scaleChroma(value, 1.22f)
        LiveCameraFilter.SOFT -> scaleChroma(value, 0.88f)
        LiveCameraFilter.MONO -> 128
        LiveCameraFilter.NONE -> value
        }
        if (!adjustments.enabled || filter == LiveCameraFilter.MONO) return filtered.coerceIn(0, 255)
        val warmthOffset = ((adjustments.warmth.coerceIn(0f, 1f) - 0.5f) * 28f).roundToInt()
        return (filtered + if (isUPlane) -warmthOffset else warmthOffset).coerceIn(0, 255)
    }

    private fun contrast(value: Int, multiplier: Float, lift: Int): Int =
        ((value - 128) * multiplier + 128 + lift).roundToInt()

    private fun scaleChroma(value: Int, multiplier: Float): Int =
        ((value - 128) * multiplier + 128).roundToInt()
}

/**
 * Runtime state of the camera filter pipeline. This is surfaced to the host
 * so a device can degrade the decorative effect without silently changing the
 * broadcaster's selected filter.
 */
enum class LiveFilterPerformanceState {
    NATURAL,
    FULL,
    REDUCED,
    TEMPORARILY_PAUSED,
    NOT_APPLIED_TO_SCREEN
}

/**
 * Applies inexpensive I420 color transforms before WebRTC publishes a camera
 * frame. The processor fails open: unsupported frames, memory pressure or a
 * sustained slow device send the original frame instead of interrupting LIVE.
 */
class LiveCameraFilterProcessor(
    initialFilter: LiveCameraFilter = LiveCameraFilter.NONE,
    initialAdjustments: LiveCameraAdjustments = LiveCameraAdjustments(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val onPerformanceStateChanged: (LiveFilterPerformanceState) -> Unit = {}
) : VideoProcessor {

    private val selected = AtomicReference(initialFilter)
    private val adjustments = AtomicReference(initialAdjustments.normalized())

    @Volatile
    private var sink: VideoSink? = null

    @Volatile
    private var slowFrameStreak = 0

    @Volatile
    private var bypassUntilNs = 0L

    @Volatile
    private var performanceState = (initialFilter to initialAdjustments.normalized()).performanceState()

    @Volatile
    private var reducedFrameToggle = false

    @Volatile
    private var fastRecoveryStreak = 0

    init {
        notifyPerformanceState(performanceState)
    }

    val filter: LiveCameraFilter
        get() = selected.get()

    val currentPerformanceState: LiveFilterPerformanceState
        get() = performanceState

    fun select(filter: LiveCameraFilter) {
        selected.set(filter)
        slowFrameStreak = 0
        bypassUntilNs = 0L
        reducedFrameToggle = false
        fastRecoveryStreak = 0
        updatePerformanceState((filter to adjustments.get()).performanceState())
    }

    fun selectAdjustments(next: LiveCameraAdjustments) {
        adjustments.set(next.normalized())
        slowFrameStreak = 0
        bypassUntilNs = 0L
        fastRecoveryStreak = 0
        updatePerformanceState((selected.get() to adjustments.get()).performanceState())
    }

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) = Unit

    override fun onCapturerStopped() = Unit

    override fun onFrameCaptured(frame: VideoFrame) {
        val target = sink ?: return
        val activeFilter = selected.get()
        val activeAdjustments = adjustments.get()
        val startedAt = nanoTime()
        if (activeFilter == LiveCameraFilter.NONE && !activeAdjustments.hasVisibleEffect) {
            updatePerformanceState(LiveFilterPerformanceState.NATURAL)
            target.onFrame(frame)
            return
        }

        if (startedAt < bypassUntilNs) {
            updatePerformanceState(LiveFilterPerformanceState.TEMPORARILY_PAUSED)
            target.onFrame(frame)
            return
        }

        if (performanceState == LiveFilterPerformanceState.TEMPORARILY_PAUSED) {
            // The device has had a short recovery window. Try the full effect
            // again and let the measured frame time decide whether to reduce.
            updatePerformanceState(LiveFilterPerformanceState.FULL)
            slowFrameStreak = 0
            fastRecoveryStreak = 0
        }

        // Do not alternate filtered and unfiltered frames in REDUCED mode.
        // That old optimisation looked like the selected filter was switching
        // itself off every other frame. WebRTC can naturally drop late video
        // frames while audio keeps flowing; keeping a stable look is less
        // disruptive than visibly flashing between two colour profiles.

        val filteredFrame = runCatching {
            createFilteredFrame(frame, activeFilter, activeAdjustments)
        }.getOrNull()
        if (filteredFrame == null) {
            target.onFrame(frame)
            return
        }

        try {
            target.onFrame(filteredFrame)
        } finally {
            filteredFrame.release()
        }

        val elapsedNs = nanoTime() - startedAt
        val budgetNs = PROCESSING_BUDGET_MS * NANOS_PER_MILLISECOND
        if (elapsedNs > budgetNs) {
            slowFrameStreak += 1
            fastRecoveryStreak = 0
        } else {
            slowFrameStreak = 0
            fastRecoveryStreak += 1
        }
        if (slowFrameStreak >= REDUCED_SLOW_FRAMES) {
            updatePerformanceState(LiveFilterPerformanceState.REDUCED)
        } else if (
            performanceState == LiveFilterPerformanceState.REDUCED &&
            fastRecoveryStreak >= FAST_RECOVERY_FRAMES
        ) {
            reducedFrameToggle = false
            updatePerformanceState(LiveFilterPerformanceState.FULL)
        }
        if (slowFrameStreak >= MAX_SLOW_FRAMES) {
            // Camera effects are decorative. Prefer stable video/audio over
            // retaining an effect that overloads a low-end or overheated phone,
            // while exposing this state to the host instead of hiding it.
            bypassUntilNs = nanoTime() + THERMAL_BYPASS_MS * NANOS_PER_MILLISECOND
            slowFrameStreak = 0
            fastRecoveryStreak = 0
            updatePerformanceState(LiveFilterPerformanceState.TEMPORARILY_PAUSED)
        }
    }

    private fun updatePerformanceState(next: LiveFilterPerformanceState) {
        if (performanceState == next) return
        performanceState = next
        notifyPerformanceState(next)
    }

    private fun notifyPerformanceState(state: LiveFilterPerformanceState) {
        runCatching { onPerformanceStateChanged(state) }
    }

    private fun createFilteredFrame(
        frame: VideoFrame,
        filter: LiveCameraFilter,
        adjustments: LiveCameraAdjustments,
    ): VideoFrame? {
        val input = frame.buffer.toI420() ?: return null
        return try {
            val output = JavaI420Buffer.allocate(input.width, input.height)
            try {
                transformLuma(
                    source = input.dataY,
                    sourceStride = input.strideY,
                    target = output.dataY,
                    targetStride = output.strideY,
                    width = input.width,
                    height = input.height,
                    filter = filter,
                    adjustments = adjustments,
                )
                val chromaWidth = (input.width + 1) / 2
                val chromaHeight = (input.height + 1) / 2
                transformChroma(
                    source = input.dataU,
                    sourceStride = input.strideU,
                    target = output.dataU,
                    targetStride = output.strideU,
                    width = chromaWidth,
                    height = chromaHeight,
                    filter = filter,
                    isUPlane = true,
                    adjustments = adjustments,
                )
                transformChroma(
                    source = input.dataV,
                    sourceStride = input.strideV,
                    target = output.dataV,
                    targetStride = output.strideV,
                    width = chromaWidth,
                    height = chromaHeight,
                    filter = filter,
                    isUPlane = false,
                    adjustments = adjustments,
                )
                VideoFrame(output, frame.rotation, frame.timestampNs)
            } catch (failure: Throwable) {
                output.release()
                throw failure
            }
        } finally {
            input.release()
        }
    }

    private fun transformLuma(
        source: ByteBuffer,
        sourceStride: Int,
        target: ByteBuffer,
        targetStride: Int,
        width: Int,
        height: Int,
        filter: LiveCameraFilter,
        adjustments: LiveCameraAdjustments,
    ) {
        for (row in 0 until height) {
            val sourceRow = row * sourceStride
            val targetRow = row * targetStride
            for (column in 0 until width) {
                val original = source.get(sourceRow + column).unsigned()
                val adjusted = LiveCameraFilterColorMath.luma(original, filter, adjustments)
                target.put(targetRow + column, adjusted.toByte())
            }
        }
    }

    private fun transformChroma(
        source: ByteBuffer,
        sourceStride: Int,
        target: ByteBuffer,
        targetStride: Int,
        width: Int,
        height: Int,
        filter: LiveCameraFilter,
        isUPlane: Boolean,
        adjustments: LiveCameraAdjustments,
    ) {
        for (row in 0 until height) {
            val sourceRow = row * sourceStride
            val targetRow = row * targetStride
            for (column in 0 until width) {
                val original = source.get(sourceRow + column).unsigned()
                val adjusted = LiveCameraFilterColorMath.chroma(original, filter, isUPlane, adjustments)
                target.put(targetRow + column, adjusted.toByte())
            }
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xFF

    private companion object {
        // A 24 ms budget and a three-frame cutoff disabled effects in roughly
        // one tenth of a second on entry-level phones. These thresholds require
        // sustained pressure before a short thermal recovery window, while the
        // selected filter remains active during ordinary frame-time spikes.
        const val PROCESSING_BUDGET_MS = 40L
        const val REDUCED_SLOW_FRAMES = 12
        const val MAX_SLOW_FRAMES = 90
        const val FAST_RECOVERY_FRAMES = 12
        const val THERMAL_BYPASS_MS = 750L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private fun LiveCameraFilter.performanceState(): LiveFilterPerformanceState =
    if (this == LiveCameraFilter.NONE) {
        LiveFilterPerformanceState.NATURAL
    } else {
        LiveFilterPerformanceState.FULL
    }

private fun Pair<LiveCameraFilter, LiveCameraAdjustments>.performanceState(): LiveFilterPerformanceState =
    if (first == LiveCameraFilter.NONE && !second.hasVisibleEffect) {
        LiveFilterPerformanceState.NATURAL
    } else {
        LiveFilterPerformanceState.FULL
    }
