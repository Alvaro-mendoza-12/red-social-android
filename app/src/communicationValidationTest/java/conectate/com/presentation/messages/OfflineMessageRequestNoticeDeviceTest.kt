package conectate.com.presentation.messages
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import conectate.com.data.model.DirectMessageRequestState
import org.junit.Rule
import org.junit.Test
class OfflineMessageRequestNoticeDeviceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun unavailableVerificationDoesNotShowABlockingErrorOrRefreshButton() {
        compose.setContent { MaterialTheme { DirectMessageRequestNotice(DirectMessageRequestState("offline"),"Persona",false,false,{},{},{},{}) } }
        compose.onNodeWithText("No se pudo verificar la solicitud").assertDoesNotExist()
        compose.onNodeWithText("Comprueba tu conexión y vuelve a intentarlo.").assertDoesNotExist()
        compose.onNodeWithText("Actualizar").assertDoesNotExist()
    }
    @Test fun firstRequestStillShowsItsConsentAndSingleMessageLimit() {
        compose.setContent { MaterialTheme { DirectMessageRequestNotice(DirectMessageRequestState("new"),"Persona",false,false,{},{},{},{}) } }
        compose.onNodeWithText("Enviar una solicitud de mensaje").assertIsDisplayed()
        compose.onNodeWithText("Continuar").assertIsDisplayed()
    }
}
