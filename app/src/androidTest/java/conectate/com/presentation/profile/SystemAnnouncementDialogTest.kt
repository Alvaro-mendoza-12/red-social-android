package conectate.com.presentation.profile

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.localfallback.SystemAnnouncement
import conectate.com.presentation.common.SystemAnnouncementDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SystemAnnouncementDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun notice(kind: String = "info") = SystemAnnouncement("qa-ui", kind, "Tenemos novedades para ti",
        "Ahora puedes conocer las novedades al abrir Novvin.", Instant.now().plusSeconds(86400).toString())

    @Test fun closeRemovesTheCurrentAnnouncementImmediately() {
        val visible = mutableStateOf(true)
        var dismissed = 0
        compose.setContent { MaterialTheme { if (visible.value) SystemAnnouncementDialog(notice(), {
            dismissed++; visible.value = false
        }, remaining = 3) } }
        compose.onNodeWithText("Quedan 3 avisos").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cerrar aviso").assertIsDisplayed().performClick()
        compose.onNodeWithTag("system_announcement").assertDoesNotExist()
        assertEquals(1, dismissed)
    }
    @Test fun longMessageKeepsTheCloseControlAvailable() {
        compose.setContent { MaterialTheme { SystemAnnouncementDialog(
            notice().copy(title = "Aviso importante para todas las personas que utilizan Novvin en sus dispositivos",
                body = "Información de una novedad. ".repeat(40)), {},
        ) } }
        compose.onNodeWithContentDescription("Cerrar aviso").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Entendido").assertIsDisplayed()
    }
    @Test fun administratorCanWithdrawAnActiveNoticeBeforeItsExpiry() {
        var removed = 0
        compose.setContent { MaterialTheme { SystemAnnouncementAdminCard(notice(), true, false) { removed++ } } }
        compose.onNodeWithText("Retirar aviso").assertIsDisplayed().performClick()
        assertEquals(1, removed)
    }
    @Test fun withdrawnNoticeDoesNotOfferWithdrawalAgain() {
        compose.setContent { MaterialTheme { SystemAnnouncementAdminCard(notice().copy(revokedAt = Instant.now().toString()), true, false) {} } }
        compose.onNodeWithText("Retirado").assertIsDisplayed()
        compose.onNodeWithText("Retirar aviso").assertDoesNotExist()
    }
}
