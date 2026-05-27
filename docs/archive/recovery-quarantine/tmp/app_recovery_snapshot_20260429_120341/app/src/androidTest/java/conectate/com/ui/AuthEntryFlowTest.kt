package conectate.com.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import conectate.com.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class AuthEntryFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun emailEntry_canOpenRegisterAndPrivacyPolicy() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithText("Iniciar sesión con Email").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Iniciar sesión con Email").performClick()
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
                composeRule.onNodeWithText("Crear Cuenta").assertExists()
                true
            }.getOrDefault(false)
        }
        composeRule.onNodeWithText("Crear Cuenta").assertIsDisplayed()
        composeRule.onNodeWithText("Leer").performClick()
        composeRule.onNodeWithText("Políticas y normas").assertIsDisplayed()
    }
}
