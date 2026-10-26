package conectate.com.presentation.messages.stickers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal class StickerKeyboardPanel(
    val height: Dp,
    val inputFocusRequester: FocusRequester,
    val toggle: () -> Unit,
    val showKeyboard: () -> Unit,
    val onInputFocused: () -> Unit
)

@Composable
internal fun rememberStickerKeyboardPanel(
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit
): StickerKeyboardPanel {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val imeHeight = WindowInsets.ime.getBottom(density)
    var lastKeyboardHeight by remember { mutableStateOf(280.dp) }
    LaunchedEffect(imeHeight, visible) {
        if (!visible && imeHeight > 0) {
            // Ignore intermediate frames while the keyboard opens/closes.
            delay(180)
            lastKeyboardHeight = with(density) { imeHeight.toDp() }
        }
    }
    LaunchedEffect(visible) {
        if (visible) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    BackHandler(enabled = visible) { onVisibleChange(false) }
    val showKeyboard: () -> Unit = {
        onVisibleChange(false)
        runCatching { requester.requestFocus() }
        keyboard?.show()
    }
    // Keep a useful grid on tall phones/tablets; matching a short keyboard left
    // barely one sticker row after the controls. Still leave the composer visible.
    val availableHeight = windowHeight - if (visible) with(density) { imeHeight.toDp() } else 0.dp
    val preferredHeight = maxOf(lastKeyboardHeight, (windowHeight * 0.48f).coerceIn(300.dp, 480.dp))
    return StickerKeyboardPanel(
        height = preferredHeight.coerceIn(160.dp, (availableHeight * 0.56f).coerceAtLeast(160.dp)),
        inputFocusRequester = requester,
        toggle = { if (visible) showKeyboard() else onVisibleChange(true) },
        showKeyboard = showKeyboard,
        onInputFocused = { onVisibleChange(false) }
    )
}
