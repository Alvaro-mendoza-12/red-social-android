package conectate.com.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.Message
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
class AudioProgressRenderingTest {
    @get:Rule val compose=createComposeRule()
    @Test fun unknownDurationCannotCrashTheAudioSliderAndFileNameIsHidden() {
        compose.setContent { MaterialTheme {
            MessageBubble(Message(id="local-audio",sender_id="local",content="recording_123.webm",content_type="audio"),
                isFromCurrentUser=true,isAudioActive=true,progress=Float.NaN)
        } }
        compose.onNodeWithContentDescription("Posición del audio").assertIsDisplayed()
        compose.onNodeWithText("recording_123.webm").assertDoesNotExist()
        compose.onNodeWithText("Audio").assertIsDisplayed()
    }
}
