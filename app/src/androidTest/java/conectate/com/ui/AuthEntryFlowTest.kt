package conectate.com.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
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
        val forgotPassword = composeRule.activity.getString(conectate.com.R.string.auth_forgot_password)
        val register = composeRule.activity.getString(conectate.com.R.string.register)
        var initialFailure: Throwable? = null
        try { composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching {
                composeRule.onAllNodesWithText("Iniciar sesión", substring = true).onFirst().assertExists()
                true
            }.onFailure { initialFailure = it }.getOrDefault(false)
        } } catch (error: Throwable) {
            val tree = runCatching { composeRule.onRoot().printToString() }.getOrElse { it.toString() }
            throw AssertionError("Initial auth screen was not found: ${initialFailure?.message}\n$tree", error)
        }
        composeRule.onAllNodesWithText("Iniciar sesión", substring = true).onFirst().performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithText(forgotPassword).assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText(forgotPassword).assertIsDisplayed()
        composeRule.onNodeWithText(register, substring = true).performScrollTo().performClick()
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
        composeRule.onNodeWithText("Continuar como invitado").performScrollTo().performClick()
        composeRule.waitUntil(30_000) {
            runCatching { composeRule.onNodeWithText("Explorar Novvin").assertExists(); true }.getOrDefault(false)
        }
        composeRule.onNodeWithContentDescription("Perfil", useUnmergedTree = true).performClick()
        composeRule.waitUntil(30_000) {
            runCatching { composeRule.onNodeWithText("Modo invitado").assertExists(); true }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Salir del modo invitado").performScrollTo().performClick()
        composeRule.waitUntil(15_000) {
            runCatching { composeRule.onNodeWithText("Continuar como invitado").assertExists(); true }.getOrDefault(false)
        }
    }
}
