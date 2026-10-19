package conectate.com.presentation.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Selection taps precede media buttons and links, while drags can still scroll the list. */
fun Modifier.messageSelectionTap(enabled: Boolean, onTap: () -> Unit): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    if (!enabled) this else pointerInput(enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down.consume()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                if (!change.pressed) {
                    change.consume(); tap(); return@awaitEachGesture
                }
            } while (event.changes.any { it.pressed })
        }
    }
}
