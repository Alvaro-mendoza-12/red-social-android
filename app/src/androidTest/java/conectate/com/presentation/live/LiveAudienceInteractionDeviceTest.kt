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
import androidx.compose.ui.test.junit4.StateRestorationTester
import conectate.com.data.model.LiveAudienceItem
import conectate.com.data.model.LiveAudienceState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveAudienceInteractionDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun questionDraftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val submissions = mutableListOf<String>()
        restoration.setContent {
            MaterialTheme {
                LiveAudienceContent(LiveAudienceState(), null, false, 0, "viewer",
                    {}, {}, { _, _, _ -> }, { submissions.add(it) }, { _, _ -> }, { _, _, _, _ -> })
            }
        }
        compose.onNodeWithText("Preguntas").performClick()
        compose.onNodeWithText("Tu pregunta").performTextInput("¿Seguimos después de girar la pantalla?")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Enviar pregunta").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("¿Seguimos después de girar la pantalla?"), submissions) }
    }

    @Test fun pollDraftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        var recorded: Triple<String, List<String>, Int>? = null
        restoration.setContent {
            MaterialTheme {
                LiveAudienceContent(LiveAudienceState(canModerate = true), null, false, 0, "host",
                    {}, {}, { content, options, seconds -> recorded = Triple(content, options, seconds) },
                    {}, { _, _ -> }, { _, _, _, _ -> })
            }
        }
        compose.onNodeWithText("Crear encuesta").performClick()
        compose.onNodeWithText("Pregunta de la encuesta").performTextInput("¿Siguiente actividad?")
        compose.onNodeWithText("Respuesta 1").performTextInput("Dibujar")
        compose.onNodeWithText("Respuesta 2").performTextInput("Conversar")
        compose.onNodeWithText("10 min").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Publicar encuesta").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple("¿Siguiente actividad?", listOf("Dibujar", "Conversar"), 600), recorded) }
    }

    @Test fun moderatorReplyDraftSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        val state = LiveAudienceState(canModerate = true, items = listOf(
            LiveAudienceItem("question", "question", "¿Cuándo volvemos?", authorId = "viewer"),
        ))
        var recorded: String? = null
        restoration.setContent {
            MaterialTheme {
                LiveAudienceContent(state, null, false, 0, "host",
                    {}, {}, { _, _, _ -> }, {}, { _, _ -> }, { _, _, answer, _ -> recorded = answer })
            }
        }
        compose.onNodeWithText("Preguntas").performClick()
        compose.onNodeWithText("Responder").performScrollTo().performClick()
        compose.onNodeWithText("Respuesta del anfitrión").performScrollTo().performTextInput("Mañana a las diez.")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Publicar respuesta").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Mañana a las diez.", recorded) }
    }

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
