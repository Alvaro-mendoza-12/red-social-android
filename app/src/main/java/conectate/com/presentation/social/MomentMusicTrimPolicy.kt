package conectate.com.presentation.social

import kotlin.math.abs
import kotlin.math.min

/** Song coordinates and clip length are different: a 15s video can use 2:00–2:15. */
internal class MomentMusicTrimPolicy(trackDurationMs: Long, contentDurationMs: Long?) {
    val sourceDurationMs = trackDurationMs.coerceAtLeast(1_000L)
    val maxClipMs = minOf(sourceDurationMs, contentDurationMs?.takeIf { it > 0L }?.coerceAtLeast(1_000L) ?: 60_000L, 60_000L)
    val minClipMs = minOf(5_000L, maxClipMs)

    fun initialRange(contentDurationMs: Long?): ClosedFloatingPointRange<Float> =
        0f..minOf(maxClipMs, contentDurationMs?.takeIf { it > 0L }?.coerceAtLeast(1_000L) ?: 6_000L).toFloat()

    fun normalize(range: ClosedFloatingPointRange<Float>): ClosedFloatingPointRange<Float> {
        val start = range.start.takeIf(Float::isFinite)?.toLong()?.coerceAtLeast(0L) ?: 0L
        val end = range.endInclusive.takeIf(Float::isFinite)?.toLong() ?: (start + minClipMs)
        val length = (end - start).coerceIn(minClipMs, maxClipMs)
        val boundedStart = start.coerceAtMost(sourceDurationMs - length)
        return boundedStart.toFloat()..(boundedStart + length).toFloat()
    }

    fun moveStart(startMs: Float, previous: ClosedFloatingPointRange<Float>): ClosedFloatingPointRange<Float> {
        val valid = normalize(previous)
        val length = valid.endInclusive - valid.start
        val start = (startMs.takeIf(Float::isFinite) ?: valid.start).coerceIn(0f, sourceDurationMs - length)
        return start..(start + length)
    }

    fun resize(
        candidate: ClosedFloatingPointRange<Float>,
        previous: ClosedFloatingPointRange<Float>,
    ): ClosedFloatingPointRange<Float> {
        val valid = normalize(previous)
        val movingStart = abs(candidate.start - valid.start) > abs(candidate.endInclusive - valid.endInclusive)
        return if (movingStart) {
            val end = valid.endInclusive
            candidate.start.coerceIn((end - maxClipMs).coerceAtLeast(0f), end - minClipMs)..end
        } else {
            val start = valid.start
            start..candidate.endInclusive.coerceIn(start + minClipMs, min(sourceDurationMs.toFloat(), start + maxClipMs))
        }
    }

    fun fadeLimitMs(range: ClosedFloatingPointRange<Float>): Long {
        val valid = normalize(range)
        return minOf(5_000L, ((valid.endInclusive - valid.start) / 2f).toLong())
    }
}
