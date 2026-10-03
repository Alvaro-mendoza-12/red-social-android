package conectate.com.presentation.messages

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceRoomSearchDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchRequestsCodeWithoutDuplicatingPasswordPrompt() {
        var attempts = 0
        compose.setContent { MaterialTheme {
            VoiceRoomSearchDialog("100031", VoiceRoomSearchState.Idle, {}, { attempts++ }, {})
        } }
        compose.onNodeWithText("Código de sala").assertExists()
        compose.onNodeWithText("Contraseña (si es privada)").assertDoesNotExist()
        compose.onNodeWithText("Buscar sala").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, attempts) }
    }

    @Test fun searchingBlocksRepeatedRequestsAndAllowsCancel() {
        compose.setContent { MaterialTheme {
            VoiceRoomSearchDialog("100031", VoiceRoomSearchState.Searching, {}, {}, {})
        } }
        compose.onNodeWithText("Buscando…").assertIsNotEnabled()
        compose.onNodeWithText("Cancelar").assertIsEnabled()
    }

    @Test fun errorIsVisibleAndRetryRemainsAvailable() {
        compose.setContent { MaterialTheme {
            VoiceRoomSearchDialog("100031", VoiceRoomSearchState.Failed("Revisa el código."), {}, {}, {})
        } }
        compose.onNodeWithText("Revisa el código.").assertExists()
        compose.onNodeWithText("Buscar sala").assertIsEnabled()
    }
}
