package conectate.com.presentation.messages

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.community.CommunityQuestion
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs with the real IME; repeat with the device's narrow width and larger font. */
@RunWith(AndroidJUnit4::class)
class QuestionAnswerSheetDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aReplyCanBeSentWhileTheKeyboardOccupiesTheWindow() {
        val keyboardVisible = AtomicBoolean()
        var showKeyboard: () -> Unit = {}
        var received: String? = null
        val question = CommunityQuestion(
            id = "keyboard-question", prompt = "¿Qué propuesta compartirías para ayudar a otra persona de la comunidad?",
            maxLength = 80, canRespond = true,
        )
        compose.setContent {
            MaterialTheme {
                val controller = LocalSoftwareKeyboardController.current
                val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
                SideEffect { keyboardVisible.set(keyboardHeight > 0); showKeyboard = { controller?.show() } }
                QuestionAnswerSheet(question,question.prompt,true,"qa-account",false,null,
                    onClose = {}, onAnswer = { reply,saved -> received = reply;saved() },
                    onVote = { _,_ -> throw AssertionError("The fixture has no answers to vote on") },
                    onMore = { throw AssertionError("The fixture has no other pages") })
            }
        }
        val reply = "Podemos preparar una guía breve."
        compose.onNode(hasSetTextAction()).performClick().performTextInput(reply)
        compose.runOnIdle { showKeyboard() }
        compose.waitUntil(10_000) { keyboardVisible.get() }
        val send = compose.onNode(hasText("Enviar respuesta") and hasClickAction())
        if (!send.isDisplayed()) send.performScrollTo()
        send.assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(reply,received) }
    }
}
