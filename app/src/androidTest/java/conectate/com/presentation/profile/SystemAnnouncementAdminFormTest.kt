package conectate.com.presentation.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.localfallback.PublishSystemAnnouncement
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemAnnouncementAdminFormTest {
    @get:Rule val compose = createComposeRule()
    @Test fun invalidDraftCannotPublish() {
        compose.setContent { MaterialTheme { SystemAnnouncementAdminForm(true, false, {}) } }
        compose.onNodeWithText("Revisar antes de publicar").assertIsNotEnabled()
    }
    @Test fun noticeRequiresPreviewAndPreservesDraftUntilConfirmedByServer() {
        var published: PublishSystemAnnouncement? = null
        val reset = mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme { SystemAnnouncementAdminForm(true, false, { published = it }, reset.value) } }
        compose.onNodeWithText("Título").performTextInput("  Novedad QA  ")
        compose.onNodeWithText("Mensaje para los usuarios").performTextInput("  Mensaje de prueba privada  ")
        compose.onNodeWithText("Revisar antes de publicar").performClick()
        assertNull(published)
        compose.onNodeWithText("Publicar aviso").performClick()
        compose.runOnIdle {
            assertEquals("Novedad QA", published?.title)
            assertEquals("Mensaje de prueba privada", published?.body)
        }
        compose.onNodeWithText("  Novedad QA  ").assertExists()
        compose.runOnIdle { reset.value = published!!.id }
        compose.onNodeWithText("  Novedad QA  ").assertDoesNotExist()
        compose.onNodeWithText("Revisar antes de publicar").assertIsNotEnabled()
    }
    @Test fun missingAdminPermissionDisablesPublication() {
        compose.setContent { MaterialTheme { SystemAnnouncementAdminForm(false, false, {}) } }
        compose.onNodeWithText("Revisar antes de publicar").assertIsNotEnabled()
    }
}
