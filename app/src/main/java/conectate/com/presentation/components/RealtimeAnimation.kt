package conectate.com.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * Progress based on elapsed wall-clock frame time instead of Android's animator duration scale.
 *
 * This keeps essential UI feedback visible for the same amount of time on every device, including
 * phones where system animations are shortened or disabled for accessibility or performance.
 */
@Composable
fun rememberRealtimeProgress(
    key: Any?,
    durationMillis: Long,
    running: Boolean = true,
    repeat: Boolean = false,
): Float {
    var progress by remember(key) { mutableFloatStateOf(0f) }

    LaunchedEffect(key, durationMillis, running, repeat) {
        progress = 0f
        if (!running) return@LaunchedEffect

        val safeDurationNanos = durationMillis.coerceAtLeast(1L) * 1_000_000L
        val startedAt = withFrameNanos { it }
        do {
            withFrameNanos { frameTime ->
                val elapsed = (frameTime - startedAt).coerceAtLeast(0L)
                progress = if (repeat) {
                    (elapsed % safeDurationNanos).toFloat() / safeDurationNanos.toFloat()
                } else {
                    (elapsed.toFloat() / safeDurationNanos.toFloat()).coerceIn(0f, 1f)
                }
            }
        } while (repeat || progress < 1f)
    }

    return progress
}
