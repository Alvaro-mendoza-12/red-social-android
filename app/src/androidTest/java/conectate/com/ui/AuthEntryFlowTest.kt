package conectate.com.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AuthEntryFlowTest {

    // Run on a fresh/logged-out QA installation. Never clear an existing
    // account merely to satisfy this test's logged-out precondition.
    @get:Rule
    val composeRule = createEntryComposeRule()

    @Test
    fun allEntryPaths_keepActivityAlive_andGuestCanLeave() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Iniciar sesión").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Iniciar sesión").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithText("¿Olvidaste tu contraseña?").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("¿Olvidaste tu contraseña?").assertIsDisplayed()
        composeRule.onNodeWithText("Registrarse", substring = true).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithText("¿Cómo te llamas?").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Nombre visible").performTextReplacement("Novvin QA")
        composeRule.onNodeWithText("Continuar").performClick()
        composeRule.onNodeWithText("Elige tu @usuario").assertIsDisplayed()
        composeRule.onNodeWithText("Identificador único")
            .performTextReplacement("qa_${java.util.UUID.randomUUID().toString().take(12).replace("-", "")}")
        composeRule.waitUntil(30_000) {
            runCatching { composeRule.onNodeWithText("Continuar").assertIsEnabled(); true }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Continuar").performClick()
        composeRule.onNodeWithText("Crea tu espacio").assertIsDisplayed()
        composeRule.onNodeWithText("Leer").performScrollTo().performClick()
        composeRule.onNodeWithText("Políticas y normas").assertIsDisplayed()
        // Leave the wizard without submitting registration or sending email.
        repeat(6) {
            val welcome = runCatching {
                composeRule.onNodeWithText("Continuar como invitado").assertExists(); true
            }.getOrDefault(false)
            if (!welcome) { pressBack(); composeRule.waitForIdle() }
        }
        composeRule.onNodeWithText("Continuar como invitado").performClick()
        composeRule.waitUntil(30_000) {
            runCatching { composeRule.onNodeWithText("Explorar Novvin").assertExists(); true }.getOrDefault(false)
        }
        composeRule.onNodeWithContentDescription("Perfil").performClick()
        composeRule.waitUntil(30_000) {
            runCatching { composeRule.onNodeWithText("Modo invitado").assertExists(); true }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Salir del modo invitado").performScrollTo().performClick()
        composeRule.waitUntil(15_000) {
            runCatching { composeRule.onNodeWithText("Continuar como invitado").assertExists(); true }.getOrDefault(false)
        }
    }
}
