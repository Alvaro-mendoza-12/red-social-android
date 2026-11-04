package conectate.com.presentation.video.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import conectate.com.presentation.theme.RedSocialTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoRoutesDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val route = VideoRoute("route", "Guitarra", 3, listOf(
        VideoRouteItem("Video-A", "Acordes para empezar", "author", "Ana"),
    ))

    @Test fun preparationKeepsItsInputAndCallsTheRequestedSize() {
        val state = mutableStateOf(VideoRoutesState(accountId = "alice", loading = false))
        var prepared = false
        compose.setContent {
            RedSocialTheme {
                content(
                    state = state.value,
                    onQuery = { state.value = state.value.copy(query = it) },
                    onSize = { state.value = state.value.copy(requestedCount = it) },
                    onPrepare = { prepared = true },
                )
            }
        }
        compose.onNodeWithText("Preparar ruta").assertIsNotEnabled()
        compose.onNodeWithText("¿Qué quieres explorar?").performTextInput("guitarra")
        compose.onNodeWithText("5 videos").performClick()
        compose.onNodeWithText("Preparar ruta").performClick()
        compose.runOnIdle {
            assertEquals("guitarra", state.value.query)
            assertEquals(5, state.value.requestedCount)
            assertTrue(prepared)
        }
    }

    @Test fun playbackUsesTheSelectedIdAndUsefulnessWaitsForAnOpening() {
        var opened: String? = null
        compose.setContent {
            RedSocialTheme {
                content(state = VideoRoutesState(accountId = "alice", route = route, composerVisible = false, loading = false),
                    onOpen = { opened = it })
            }
        }
        compose.onNodeWithText("Me sirvió").assertIsNotEnabled()
        compose.onNodeWithText("Abrir video").performClick()
        compose.runOnIdle { assertEquals("Video-A", opened) }
        compose.onNodeWithText("Encontramos 1 de los 3 que pediste. Puedes recorrer esta selección o probar otro tema.").assertIsDisplayed()
    }

    @Test fun leavingWithAnUnsavedConclusionRequiresAnExplicitChoice() {
        var saved = false
        var left = false
        compose.setContent {
            RedSocialTheme {
                content(
                    state = VideoRoutesState(accountId = "alice", route = route, conclusion = "Practicar acordes", composerVisible = false, loading = false),
                    onBack = { left = true }, onSaveAndBack = { saved = true },
                )
            }
        }
        compose.onNodeWithContentDescription("Volver").performClick()
        compose.onNodeWithText("¿Guardar tu conclusión?").assertIsDisplayed()
        compose.onNodeWithText("Guardar y salir").performClick()
        compose.runOnIdle { assertTrue(saved); assertFalse(left) }
    }

    @Test fun narrowScreenWithLargeTextCanReachTheConclusionAndEraseControls() {
        val enlarged = route.copy(items = listOf(route.items.single().copy(
            title = "Una explicación larga para aprender acordes con ambas manos y practicar en casa",
            authorName = "Una creadora con nombre largo",
            opened = true,
        )))
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.6f)) {
                RedSocialTheme {
                    content(
                        state = VideoRoutesState(accountId = "alice", route = enlarged, composerVisible = false, loading = false),
                        modifier = Modifier.requiredWidth(280.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Terminar ruta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Borrar ruta guardada").performScrollTo().performClick()
        compose.onNodeWithText("¿Borrar esta ruta?").assertIsDisplayed()
        compose.onNodeWithText("Conservar").performClick()
    }

    @Composable
    private fun content(
        state: VideoRoutesState,
        onQuery: (String) -> Unit = {},
        onSize: (Int) -> Unit = {},
        onPrepare: () -> Unit = {},
        onOpen: (String) -> Unit = {},
        onBack: () -> Unit = {},
        onSaveAndBack: () -> Unit = {},
        modifier: Modifier = Modifier,
    ) {
        VideoRoutesContent(
            state = state, onBack = onBack, onQueryChange = onQuery, onSizeChange = onSize,
            onPrepare = onPrepare, onNewRoute = {}, onShowRoute = {}, onOpen = onOpen,
            onUseful = {}, onConclusionChange = {}, onSave = {}, onSaveAndBack = onSaveAndBack,
            onCompleted = {}, onErase = {}, onReload = {}, modifier = modifier,
        )
    }
}
