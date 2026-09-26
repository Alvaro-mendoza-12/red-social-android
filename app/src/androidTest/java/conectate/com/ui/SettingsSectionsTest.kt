package conectate.com.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import conectate.com.presentation.profile.SettingsSection
import conectate.com.presentation.profile.LocalSettingsDirectory
import conectate.com.presentation.profile.SettingsDirectoryState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class SettingsSectionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsSectionDisplaysCurrentTitleAndContent() {
        composeRule.setContent {
            MaterialTheme {
                SettingsSection(title = "Copia de seguridad") {
                    Text("Guardar copia")
                    Text("Restaurar copia")
                }
            }
        }

        composeRule.onNodeWithText("Copia de seguridad").assertIsDisplayed()
        composeRule.onNodeWithText("Guardar copia").assertIsDisplayed()
        composeRule.onNodeWithText("Restaurar copia").assertIsDisplayed()
    }

    @Test
    fun categoriesCollapseAndSearchRevealsMatchingOptions() {
        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            var expanded by remember { mutableStateOf<String?>(null) }
            MaterialTheme {
                CompositionLocalProvider(LocalSettingsDirectory provides SettingsDirectoryState(
                    query, expanded, { title -> expanded = if (expanded == title) null else title },
                )) {
                    Column {
                        TextField(query, { query = it }, Modifier.testTag("settings-search"))
                        SettingsSection("Copia de seguridad") { Text("Restaurar copia") }
                        SettingsSection("Pantalla") { Text("Elegir idioma") }
                    }
                }
            }
        }
        composeRule.onNodeWithText("Restaurar copia").assertDoesNotExist()
        composeRule.onNodeWithText("Copia de seguridad").performClick()
        composeRule.onNodeWithText("Restaurar copia").assertIsDisplayed()
        composeRule.onNodeWithText("Pantalla").performClick()
        composeRule.onNodeWithText("Restaurar copia").assertDoesNotExist()
        composeRule.onNodeWithText("Elegir idioma").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-search").performTextInput("restaurar")
        composeRule.onNodeWithText("Restaurar copia").assertIsDisplayed()
        composeRule.onNodeWithText("Pantalla").assertDoesNotExist()
    }
}
