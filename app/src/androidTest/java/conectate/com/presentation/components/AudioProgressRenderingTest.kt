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
    @Test fun idleRecordingShowsItsDurationWithoutStartingPlayback() {
        compose.setContent { MaterialTheme {
            MessageBubble(Message(id="duration",content="0:37",content_type="audio"),isFromCurrentUser=false)
        } }
        compose.onNodeWithText("0:37").assertIsDisplayed()
        compose.onNodeWithText("0:00").assertDoesNotExist()
    }
    @Test fun playbackShowsElapsedAndTotalThenKeepsTotalWhenStopped() {
        val active=androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MaterialTheme {
            MessageBubble(Message(id="duration",content="voice.webm",content_type="audio"),
                isFromCurrentUser=false,isAudioActive=active.value,duration="0:08",totalAudioDurationMs=37_000)
        } }
        compose.onNodeWithText("0:08 / 0:37").assertIsDisplayed()
        compose.runOnIdle { active.value=false }
        compose.onNodeWithText("0:37").assertIsDisplayed()
    }
}
