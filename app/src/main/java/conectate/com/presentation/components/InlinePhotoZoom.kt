package conectate.com.presentation.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/** Two fingers zoom within the feed; single-finger scrolling remains available. */
fun Modifier.inlinePhotoZoom(identity: String): Modifier = composed {
    var zoom by remember(identity) { mutableFloatStateOf(1f) }
    var pan by remember(identity) { mutableStateOf(Offset.Zero) }
    clipToBounds().pointerInput(identity) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            try {
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2 || zoom > 1f) {
                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                        val nextPan = pan + event.calculatePan()
                        val xLimit = size.width * (zoom - 1f) / 2f
                        val yLimit = size.height * (zoom - 1f) / 2f
                        pan = Offset(nextPan.x.coerceIn(-xLimit, xLimit), nextPan.y.coerceIn(-yLimit, yLimit))
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            } finally { zoom = 1f; pan = Offset.Zero }
        }
    }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
}
