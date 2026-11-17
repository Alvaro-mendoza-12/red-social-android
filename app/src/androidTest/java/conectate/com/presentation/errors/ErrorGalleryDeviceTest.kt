package conectate.com.presentation.errors

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import conectate.com.core.result.NovvinErrors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ErrorGalleryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun allCodesShowTheirPreviewAndKeepBackNavigation() {
        var exits = 0
        compose.setContent { ErrorGalleryScreen(onBack = { exits++ }) }
        NovvinErrors.catalog.forEach { problem ->
            compose.onNodeWithText(checkNotNull(problem.code).toString()).performScrollTo().performClick()
            compose.onNodeWithText(problem.title).assertIsDisplayed()
            compose.onNodeWithText("Escuchar").assertIsEnabled()
            compose.onNodeWithText("Detener").assertIsNotEnabled()
        }
        compose.onNodeWithContentDescription("Atrás").performClick()
        compose.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun theOfflineSurfacePreservesBothRecoveryActions() {
        var retries = 0
        var messages = 0
        compose.setContent {
            PersonifiedErrorState(
                problem = NovvinErrors.offline,
                onRetry = { retries++ },
                secondaryLabel = "Continuar en Mensajes",
                onSecondary = { messages++ },
            )
        }
        compose.onNodeWithText(NovvinErrors.offline.title).assertIsDisplayed()
        compose.onNodeWithText("Reintentar").performScrollTo().performClick()
        compose.onNodeWithText("Continuar en Mensajes").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, messages)
        }
    }
}
