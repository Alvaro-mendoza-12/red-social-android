package conectate.com.presentation.messages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real TextField key dispatch, independent of authentication or a production conversation. */
@RunWith(AndroidJUnit4::class)
class ChatComposerKeyboardDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun enterSendsOnceWithoutAddingNewline() {
        val field = TextFieldState("Hola")
        val sent = mutableListOf<String>()
        compose.setContent {
            BasicTextField(state = field, lineLimits = TextFieldLineLimits.MultiLine(),
                modifier = Modifier.fillMaxWidth().testTag("composer")
                    .chatComposerKeyboard(true, { field.composition != null }) {
                        sent.add(field.text.toString())
                        field.edit { replace(0, length, "") }
                    })
        }
        compose.onNodeWithTag("composer").performClick().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals(listOf("Hola"), sent)
            assertEquals("", field.text.toString())
        }
    }

    @Test fun changingPreferenceAppliesWithoutReopeningTheComposerAndShiftKeepsNewlines() {
        val field = TextFieldState("Hola")
        var enterToSend by mutableStateOf(false)
        val sent = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalChatBehavior provides ChatBehaviorSettings(enterToSend = enterToSend)) {
                val behavior = LocalChatBehavior.current
                BasicTextField(state = field, lineLimits = TextFieldLineLimits.MultiLine(),
                    modifier = Modifier.fillMaxWidth().testTag("composer")
                        .chatComposerKeyboard(behavior.enterToSend, { field.composition != null }) {
                            sent.add(field.text.toString())
                        })
            }
        }
        compose.onNodeWithTag("composer").performClick().performKeyInput { pressKey(Key.Enter) }
        compose.runOnIdle {
            assertEquals("Hola\n", field.text.toString())
            assertEquals(emptyList<String>(), sent)
            enterToSend = true
        }
        compose.onNodeWithTag("composer").performKeyInput {
            keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft)
        }
        compose.runOnIdle { assertEquals("Hola\n\n", field.text.toString()) }
        compose.onNodeWithTag("composer").performKeyInput { pressKey(Key.NumPadEnter) }
        compose.runOnIdle {
            assertEquals(listOf("Hola\n\n"), sent)
            assertEquals("Hola\n\n", field.text.toString())
        }
    }
}
