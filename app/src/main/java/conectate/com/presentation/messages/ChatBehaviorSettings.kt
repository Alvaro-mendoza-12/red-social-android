package conectate.com.presentation.messages

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

internal data class ChatBehaviorSettings(
    val enterToSend: Boolean = false,
    val showTypingIndicator: Boolean = true,
)

internal val LocalChatBehavior = staticCompositionLocalOf { ChatBehaviorSettings() }

/** Intercept before TextField inserts a newline, using the same submit callback as the IME. */
internal fun Modifier.chatComposerKeyboard(
    enterToSend: Boolean,
    hasComposition: () -> Boolean,
    onSend: () -> Unit,
): Modifier = onPreviewKeyEvent { event ->
    handleChatComposerKey(event, enterToSend, hasComposition(), onSend)
}

internal fun handleChatComposerKey(
    event: KeyEvent,
    enterToSend: Boolean,
    hasComposition: Boolean,
    onSend: () -> Unit,
): Boolean {
    if (!enterToSend || hasComposition || (event.key != Key.Enter && event.key != Key.NumPadEnter) ||
        event.isShiftPressed || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
    return when (event.type) {
        KeyEventType.KeyDown -> {
            // Holding Enter, or focusing a new conversation while it is held,
            // must not dispatch another message for every repeat event.
            if (event.nativeKeyEvent.repeatCount == 0) onSend()
            true
        }
        KeyEventType.KeyUp -> true
        else -> false
    }
}

/** An empty edit is still an edit; the send/microphone button must not start recording. */
internal fun submitChatComposerDraft(
    text: String, editing: Boolean, canSend: Boolean = true,
    onEdit: () -> Unit, onSend: () -> Unit,
): Boolean = when {
    !canSend -> false
    editing -> { onEdit(); true }
    text.isNotBlank() -> { onSend(); true }
    else -> false
}
