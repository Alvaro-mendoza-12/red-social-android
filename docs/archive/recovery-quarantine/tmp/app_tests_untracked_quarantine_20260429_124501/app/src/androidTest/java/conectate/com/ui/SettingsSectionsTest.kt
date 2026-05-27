package conectate.com.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import conectate.com.data.local.prefs.AppFontOption
import conectate.com.presentation.profile.AppearanceSettingsCard
import conectate.com.presentation.profile.LegalSettingsSection
import conectate.com.presentation.profile.ProfileAccessSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class SettingsSectionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsSectionsPropagateActions() {
        var selectedFont = AppFontOption.DEFAULT
        var acceptedPolicy = false
        var openedPolicy = false
        var openedReports = false
        var openedEditProfile = false
        var openedNotifications = false
        var openedPassword = false

        composeRule.setContent {
            MaterialTheme {
                Column {
                    ProfileAccessSection(
                        onEditProfile = { openedEditProfile = true },
                        onOpenNotifications = { openedNotifications = true },
                        onChangePassword = { openedPassword = true },
                        onOpenReports = { openedReports = true }
                    )
                    AppearanceSettingsCard(
                        isDarkMode = false,
                        fontOption = selectedFont,
                        textScale = 1f,
                        onDarkModeChanged = {},
                        onFontOptionChanged = { selectedFont = it },
                        onTextScaleChanged = {}
                    )
                    LegalSettingsSection(
                        privacyAccepted = false,
                        onOpenPolicy = { openedPolicy = true },
                        onAcceptPolicy = { acceptedPolicy = true },
                        onOpenReports = { openedReports = true }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Editar perfil").performClick()
        composeRule.onNodeWithText("Notificaciones").performClick()
        composeRule.onNodeWithText("Contraseña").performClick()
        composeRule.onNodeWithText("Serif").performClick()
        composeRule.onNodeWithText("Aceptar políticas de privacidad").performClick()
        composeRule.onNodeWithText("Políticas y normas de la comunidad").performClick()
        composeRule.onNodeWithText("Ver mis reportes").performClick()
        composeRule.onNodeWithText("Privacidad y legales").assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(AppFontOption.SERIF, selectedFont)
            assertTrue(acceptedPolicy)
            assertTrue(openedPolicy)
            assertTrue(openedEditProfile)
            assertTrue(openedNotifications)
            assertTrue(openedPassword)
            assertTrue(openedReports)
        }
    }
}
