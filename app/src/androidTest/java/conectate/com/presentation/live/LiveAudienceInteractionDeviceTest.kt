package conectate.com.presentation.live

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import conectate.com.data.model.LiveAudienceState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveAudienceInteractionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun hostPublishesTheEnteredPoll() {
        var recorded: Triple<String, List<String>, Int>? = null
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    LiveAudienceContent(LiveAudienceState(canModerate = true), null, false, 0, "host",
                        {}, {}, { content, options, seconds -> recorded = Triple(content, options, seconds) },
                        {}, { _, _ -> }, { _, _, _, _ -> })
                }
            }
        }
        compose.onNodeWithText("Crear encuesta").performClick()
        compose.onNodeWithText("Pregunta de la encuesta").performTextInput("¿Qué hacemos?")
        compose.onNodeWithText("Respuesta 1").performTextInput("Dibujar")
        compose.onNodeWithText("Respuesta 2").performTextInput("Conversar")
        compose.onNodeWithText("Publicar encuesta").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple("¿Qué hacemos?", listOf("Dibujar", "Conversar"), 300), recorded) }
    }

    @Test fun failedQuestionKeepsItsDraftAndConfirmedCreationClearsIt() {
        val created = mutableIntStateOf(0)
        val error = mutableStateOf<String?>(null)
        val submissions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    LiveAudienceContent(LiveAudienceState(), error.value, false, created.intValue, "viewer",
                        {}, {}, { _, _, _ -> }, { submissions.add(it) }, { _, _ -> }, { _, _, _, _ -> })
                }
            }
        }
        compose.onNodeWithText("Preguntas").performClick()
        compose.onNodeWithText("Tu pregunta").performTextInput("¿Cuándo empieza la siguiente ronda?")
        compose.onNodeWithText("Enviar pregunta").performClick()
        compose.runOnIdle { error.value = "Revisa tu conexión y vuelve a intentarlo." }
        compose.onNodeWithText("Enviar pregunta").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("¿Cuándo empieza la siguiente ronda?", "¿Cuándo empieza la siguiente ronda?"), submissions)
            error.value = null
            created.intValue++
        }
        compose.onNodeWithText("Enviar pregunta").assertIsNotEnabled()
        val editable = compose.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals("", editable.config[SemanticsProperties.EditableText].text)
    }
}
