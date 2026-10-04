package conectate.com.presentation.social

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.max

internal fun momentExcerptStartAfterDrag(startMs: Long, dragPx: Float, widthPx: Float, viewportMs: Long,
    sourceDurationMs: Long, clipDurationMs: Long): Long {
    if (!dragPx.isFinite() || !widthPx.isFinite() || widthPx <= 0) return startMs
    return (startMs - dragPx / widthPx * viewportMs).toLong().coerceIn(0L, (sourceDurationMs - clipDurationMs).coerceAtLeast(0L))
}

/** Real source time ticks. Move the track beneath a fixed, magnified excerpt window. */
@Composable
internal fun MomentMusicExcerptTimeline(sourceDurationMs: Long, startMs: Long, endMs: Long,
    playbackPositionMs: Long, onStartChanged: (Long) -> Unit) {
    val clipMs = (endMs - startMs).coerceAtLeast(1L)
    val maxStart = (sourceDurationMs - clipMs).coerceAtLeast(0L)
    val viewportMs = max(clipMs * 3, 15_000L).coerceAtMost(sourceDurationMs.coerceAtLeast(1L))
    val latestStart by rememberUpdatedState(startMs)
    val change by rememberUpdatedState(onStartChanged)
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f)
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${excerptTime(startMs)} → ${excerptTime(endMs)}", style = MaterialTheme.typography.titleMedium)
            Text("${clipMs / 1_000} s seleccionados", style = MaterialTheme.typography.labelLarge, color = primary)
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).testTag("moment_music_excerpt_timeline")
            .semantics {
                contentDescription = "Inicio del fragmento"
                progressBarRangeInfo = ProgressBarRangeInfo(startMs.toFloat(), 0f..maxStart.toFloat())
                setProgress { value -> change(value.toLong().coerceIn(0L, maxStart)); true }
            }
            .pointerInput(sourceDurationMs, clipMs, viewportMs) {
                var dragStart = 0L
                var accumulated = 0f
                detectHorizontalDragGestures(onDragStart = { dragStart = latestStart; accumulated = 0f }) { changeEvent, drag ->
                    changeEvent.consume()
                    accumulated += drag
                    change(momentExcerptStartAfterDrag(dragStart, accumulated, size.width.toFloat(), viewportMs, sourceDurationMs, clipMs))
                }
            }) {
            val viewportStart = startMs + clipMs / 2f - viewportMs / 2f
            fun x(time: Float) = (time - viewportStart) / viewportMs * size.width
            drawRoundRect(surface, cornerRadius = CornerRadius(12.dp.toPx()))
            val left = x(startMs.toFloat())
            val right = x(endMs.toFloat())
            drawRoundRect(primary.copy(alpha = .18f), topLeft = Offset(left, 4.dp.toPx()),
                size = Size(right - left, size.height - 8.dp.toPx()), cornerRadius = CornerRadius(8.dp.toPx()))
            val firstTick = (viewportStart.toLong().coerceAtLeast(0L) / 1_000L) * 1_000L
            var time = firstTick
            while (time <= sourceDurationMs && x(time.toFloat()) <= size.width) {
                val xx = x(time.toFloat())
                val major = time % 5_000L == 0L
                val tickHeight = if (major) 20.dp.toPx() else 10.dp.toPx()
                drawLine(if (time in startMs..endMs) primary else inactive,
                    Offset(xx, (size.height - tickHeight) / 2), Offset(xx, (size.height + tickHeight) / 2), 2.dp.toPx())
                time += 1_000L
            }
            listOf(left, right).forEach { xx ->
                drawRoundRect(primary, topLeft = Offset(xx - 2.dp.toPx(), 8.dp.toPx()),
                    size = Size(4.dp.toPx(), size.height - 16.dp.toPx()), cornerRadius = CornerRadius(2.dp.toPx()))
            }
            if (playbackPositionMs in startMs..endMs) drawLine(primary,
                Offset(x(playbackPositionMs.toFloat()), 6.dp.toPx()), Offset(x(playbackPositionMs.toFloat()), size.height - 6.dp.toPx()), 2.dp.toPx())
        }
        Text(if (maxStart > 0) "Desliza la canción bajo el recuadro para elegir tu fragmento." else "Se usará la pista completa.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onStartChanged((startMs - 1_000L).coerceAtLeast(0L)) }, enabled = startMs > 0) {
                Text("−1 s")
            }
            TextButton(onClick = { onStartChanged(0L) }, enabled = startMs > 0) { Text("Al inicio") }
            TextButton(onClick = { onStartChanged((startMs + 1_000L).coerceAtMost(maxStart)) }, enabled = startMs < maxStart) {
                Text("+1 s")
            }
        }
    }
}

private fun excerptTime(ms: Long): String = java.lang.String.format(java.util.Locale.ROOT, "%d:%02d", ms / 60_000L, ms / 1_000L % 60)
