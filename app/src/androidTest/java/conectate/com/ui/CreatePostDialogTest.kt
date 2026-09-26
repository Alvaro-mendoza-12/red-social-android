package conectate.com.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import conectate.com.presentation.components.CreatePostDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class CreatePostDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun publishRequiresContentAndSendsSelectedVisibility() {
        var publishedText: String? = null
        var publishedVisibility: String? = null

        composeRule.setContent {
            MaterialTheme {
                CreatePostDialog(
                    onDismiss = {},
                    onPostCreated = { text, _, visibility, _, _, _ ->
                        publishedText = text
                        publishedVisibility = visibility
                    }
                )
            }
        }

        composeRule.onNodeWithText("Publicar").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextInput("Hola comunidad")
        composeRule.onNodeWithText("Privado").performClick()
        composeRule.onNodeWithText("Vista previa para solo ti").performClick()
        composeRule.onNodeWithText("Privada · solo tú podrás verla").assertExists()
        composeRule.onNodeWithText("Volver a editar").performClick()
        composeRule.onNodeWithText("Publicar").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertEquals("Hola comunidad", publishedText)
            assertEquals("private", publishedVisibility)
        }
    }
}
