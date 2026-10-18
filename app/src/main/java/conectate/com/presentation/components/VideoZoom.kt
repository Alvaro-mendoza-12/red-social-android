package conectate.com.presentation.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/** A pinch changes only the viewport; it never closes the room or changes the media track. */
@Composable
internal fun Modifier.videoZoom(enabled: Boolean, source: Any? = null): Modifier {
    if (!enabled) return this
    var scale by remember(source) { mutableStateOf(1f) }
    var translation by remember(source) { mutableStateOf(Offset.Zero) }
    return this.clipToBounds()
        .pointerInput(source) {
            detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2f; translation = Offset.Zero })
        }
        .pointerInput(source) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val next = (scale * zoom).coerceIn(1f, 4f)
                val focus = centroid - Offset(size.width / 2f, size.height / 2f)
                val moved = focus - (focus - translation) * (next / scale) + pan
                val limitX = size.width * (next - 1f) / 2f
                val limitY = size.height * (next - 1f) / 2f
                translation = Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY))
                scale = next
            }
        }
        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = translation.x; translationY = translation.y }
}
