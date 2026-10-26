package conectate.com.presentation.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.Message
import conectate.com.presentation.components.MessageBubble
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
class StickerMessageGestureDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tapOpensOptionsLongPressSelectsAndSelectionTapDoesNotReopenOptions() {
        val selection = mutableStateOf(false)
        var options = 0
        var selections = 0
        compose.setContent {
            MaterialTheme {
                MessageBubble(
                    Message(id = "gesture-sticker", sender_id = "other", content = "Estrella", content_type = "sticker"),
                    isFromCurrentUser = false,
                    selectionMode = selection.value,
                    onStickerClick = { options++ },
                    onLongClick = { selections++; selection.value = true },
                    onClick = { selections++ },
                )
            }
        }
        compose.onNodeWithText("Estrella").assertDoesNotExist()
        compose.onNodeWithText("⭐").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, options); assertEquals(0, selections) }
        compose.onNodeWithText("⭐").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, options); assertEquals(1, selections) }
        compose.onNodeWithText("⭐").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, options); assertEquals(2, selections) }
    }
}
