package conectate.com.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import conectate.com.presentation.components.ContentReportDialog
import conectate.com.presentation.videos.VideoReportDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ContentReportDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun contentReportRequiresReasonAndExtraDetailsForOther() {
        var capturedReason: String? = null
        var capturedDetails: String? = null

        composeRule.setContent {
            MaterialTheme {
                ContentReportDialog(
                    title = "Reportar contenido",
                    onDismiss = {},
                    onSubmit = { reason, details, _ ->
                        capturedReason = reason
                        capturedDetails = details
                    },
                    reasonOptions = listOf("Spam", "Otro")
                )
            }
        }

        composeRule.onNodeWithText("Enviar reporte").assertIsNotEnabled()
        composeRule.onNodeWithText("Elegir").performClick()
        composeRule.onNodeWithText("Otro").performClick()
        composeRule.onNodeWithText("Enviar reporte").assertIsNotEnabled()
        composeRule.onAllNodes(hasSetTextAction())[1].performTextInput("Necesita revisión urgente")
        composeRule.onNodeWithText("Enviar reporte").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("Otro", capturedReason)
            assertEquals("Necesita revisión urgente", capturedDetails)
        }
    }

    @Test
    fun videoReportAlreadyReportedShowsLockedState() {
        composeRule.setContent {
            MaterialTheme {
                VideoReportDialog(
                    alreadyReported = true,
                    onDismiss = {},
                    onSubmit = { _, _ -> }
                )
            }
        }

        composeRule.onNodeWithText("Video reportado").assertIsDisplayed()
        composeRule.onNodeWithText("Ya enviaste un reporte para este video.").assertIsDisplayed()
    }
}
