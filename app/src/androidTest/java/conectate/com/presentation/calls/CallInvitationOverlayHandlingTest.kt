package conectate.com.presentation.calls

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.CallInvite
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallInvitationOverlayHandlingTest {
    @get:Rule val compose = createComposeRule()
    private val invite = CallInvite("qa-call", "Prueba", "qa-caller", "Auditoría", contextType = "dm")

    @Test fun pendingPermissionsOrAcceptanceDisableBothTerminalActions() {
        var actions = 0
        compose.setContent { MaterialTheme {
            CallInvitationOverlay(invite, { actions++ }, { actions++ }, isHandling = true)
        } }
        compose.onNodeWithText("Aceptar").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Rechazar").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun actionableInvitationOffersAcceptAndDecline() {
        var accepted = 0
        compose.setContent { MaterialTheme {
            CallInvitationOverlay(invite, { accepted++ }, {}, isHandling = false)
        } }
        compose.onNodeWithText("Rechazar").assertIsEnabled()
        compose.onNodeWithText("Aceptar").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, accepted) }
    }
}
