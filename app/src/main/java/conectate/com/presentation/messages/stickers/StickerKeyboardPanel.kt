package conectate.com.presentation.messages.stickers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal class StickerKeyboardPanel(
    val panelSize: Modifier,
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
    val windowInfo = LocalWindowInfo.current
    val ime = WindowInsets.ime
    var lastKeyboardHeight by remember { mutableStateOf(280.dp) }
    LaunchedEffect(ime, density, visible) {
        if (!visible) {
            snapshotFlow { ime.getBottom(density) }.collectLatest { height ->
                if (height > 0) {
                    // Capture the settled keyboard without reading its animation in composition.
                    delay(180)
                    lastKeyboardHeight = with(density) { height.toDp() }
                }
            }
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
    val panelSize = remember(windowInfo, ime) {
        Modifier.layout { measurable, constraints ->
            val windowHeight = windowInfo.containerSize.height.toDp()
            val availableHeight = windowHeight - ime.getBottom(this).toDp()
            val preferredHeight = maxOf(lastKeyboardHeight, (windowHeight * 0.48f).coerceIn(300.dp, 480.dp))
            val height = preferredHeight.coerceIn(160.dp, (availableHeight * 0.56f).coerceAtLeast(160.dp))
            val measuredHeight = constraints.constrainHeight(height.roundToPx())
            val placeable = measurable.measure(constraints.copy(minHeight = measuredHeight, maxHeight = measuredHeight))
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }
    return StickerKeyboardPanel(
        panelSize = panelSize,
        inputFocusRequester = requester,
        toggle = { if (visible) showKeyboard() else onVisibleChange(true) },
        showKeyboard = showKeyboard,
        onInputFocused = { onVisibleChange(false) }
    )
}
