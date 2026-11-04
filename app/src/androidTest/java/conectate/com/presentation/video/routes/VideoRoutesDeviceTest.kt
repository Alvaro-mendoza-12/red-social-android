package conectate.com.presentation.video.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
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
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import conectate.com.presentation.theme.RedSocialTheme
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
                RouteTestContent(
                    state = state.value,
                    onQuery = { state.value = state.value.copy(query = it) },
                    onSize = { state.value = state.value.copy(requestedCount = it) },
                    onPrepare = { prepared = true },
                )
            }
        }
        compose.onNodeWithText("Preparar ruta").assertIsNotEnabled()
        compose.onNodeWithText("¿Qué quieres explorar?").performTextInput("guitarra")
        compose.onNodeWithText("5 videos").performScrollTo().performClick()
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
                RouteTestContent(state = VideoRoutesState(accountId = "alice", route = route, composerVisible = false, loading = false),
                    onOpen = { opened = it })
            }
        }
        saveScreenshot("routes106-route.png")
        compose.onNodeWithText("Me sirvió").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Abrir video").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Video-A", opened) }
        compose.onNodeWithText("Encontramos 1 de los 3 que pediste. Puedes recorrer esta selección o probar otro tema.").performScrollTo().assertIsDisplayed()
    }

    @Test fun leavingWithAnUnsavedConclusionRequiresAnExplicitChoice() {
        var saved = false
        var left = false
        compose.setContent {
            RedSocialTheme {
                RouteTestContent(
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
                    RouteTestContent(
                        state = VideoRoutesState(accountId = "alice", route = enlarged, composerVisible = false, loading = false),
                        modifier = Modifier.requiredWidth(280.dp),
                    )
                }
            }
        }
        compose.onNodeWithText("Terminar ruta").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Opciones de la ruta").performClick()
        compose.onNodeWithText("Borrar ruta guardada").performClick()
        compose.onNodeWithText("¿Borrar esta ruta?").assertIsDisplayed()
        compose.onNodeWithText("Conservar").performClick()
        saveScreenshot("routes106-narrow.png")
    }

    @Test fun searchRetryPreservesTheQueryAndReloadWarnsBeforeDiscardingAConclusion() {
        val state = mutableStateOf(VideoRoutesState(
            accountId = "alice", loading = false, query = "guitarra", error = "No se pudo preparar la ruta.",
        ))
        var prepared = false
        var reloaded = false
        compose.setContent {
            RedSocialTheme {
                RouteTestContent(state.value, onPrepare = { prepared = true }, onReload = { reloaded = true })
            }
        }
        compose.onNodeWithText("Volver a cargar").assertDoesNotExist()
        compose.onNodeWithText("Preparar ruta").performClick()
        compose.runOnIdle {
            assertTrue(prepared)
            assertEquals("guitarra", state.value.query)
            state.value = state.value.copy(route = route, conclusion = "Borrador", composerVisible = false)
        }
        compose.onNodeWithText("Volver a cargar").performClick()
        compose.onNodeWithText("¿Cargar la ruta guardada?").assertIsDisplayed()
        compose.runOnIdle { assertFalse(reloaded) }
        compose.onNodeWithText("Cargar lo guardado").performClick()
        compose.runOnIdle { assertTrue(reloaded) }
    }

    @Test fun suggestedTopicsChooseAQueryWithoutStartingAnUnrequestedSearch() {
        val state = mutableStateOf(VideoRoutesState(accountId = "alice", loading = false))
        var searches = 0
        compose.setContent {
            RedSocialTheme {
                RouteTestContent(state.value, onQuery = { state.value = state.value.copy(query = it) }, onPrepare = { searches++ })
            }
        }
        compose.onNodeWithText("Música").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Música", state.value.query); assertEquals(0, searches) }
        saveScreenshot("routes106-discovery.png")
        compose.onNodeWithText("Preparar ruta").performClick()
        compose.runOnIdle { assertEquals(1, searches) }
    }

    @Test fun conclusionAppearsOnRequestAndClosesOnlyAfterADurableAcknowledgement() {
        val state = mutableStateOf(VideoRoutesState(accountId = "alice", route = route, composerVisible = false, loading = false))
        compose.setContent {
            RedSocialTheme {
                RouteTestContent(
                    state.value,
                    onConclusion = { state.value = state.value.copy(conclusion = it) },
                    onSave = { state.value = state.value.copy(busy = true, error = null) },
                )
            }
        }
        compose.onNodeWithText("Mi conclusión").assertDoesNotExist()
        compose.onNodeWithText("Escribir").performScrollTo().performClick()
        compose.onNodeWithText("Mi conclusión").performTextInput("Practicar acordes")
        compose.onNodeWithText("Guardar conclusión").performScrollTo().performClick()
        compose.onNodeWithText("Guardar conclusión").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(busy = false, error = "No se pudo guardar el cambio.") }
        compose.onNodeWithText("Mi conclusión").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals("", state.value.route?.conclusion) }
        compose.onNodeWithText("Guardar conclusión").performScrollTo().performClick()
        compose.runOnIdle {
            state.value = state.value.copy(busy = false, route = route.copy(conclusion = state.value.conclusion, revision = 1))
        }
        compose.onNodeWithText("Mi conclusión").assertDoesNotExist()
        compose.onNodeWithText("Practicar acordes").performScrollTo().assertIsDisplayed()
    }

    @Test fun darkThemeKeepsTheSelectionAndOutcomeReadable() {
        val selection = route.copy(items = listOf(
            route.items.single().copy(opened = true, useful = true),
            VideoRouteItem("Video-B", "Cambios de acorde", "author-b", "Luis", durationSeconds = 43),
            VideoRouteItem("Video-C", "Tu primer ritmo", "author-c", "Cami", durationSeconds = 75),
        ))
        compose.setContent {
            RedSocialTheme(darkTheme = true) {
                RouteTestContent(VideoRoutesState(accountId = "alice", route = selection, composerVisible = false, loading = false))
            }
        }
        compose.onNodeWithText("Continuar ruta").assertIsDisplayed()
        saveScreenshot("routes106-dark.png")
        compose.onNodeWithText("Terminar ruta").performScrollTo().assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(checkNotNull(context.getExternalFilesDir(null)), name)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        path.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Composable
    private fun RouteTestContent(
        state: VideoRoutesState,
        onQuery: (String) -> Unit = {},
        onSize: (Int) -> Unit = {},
        onPrepare: () -> Unit = {},
        onOpen: (String) -> Unit = {},
        onBack: () -> Unit = {},
        onSaveAndBack: () -> Unit = {},
        onReload: () -> Unit = {},
        onConclusion: (String) -> Unit = {},
        onSave: () -> Unit = {},
        modifier: Modifier = Modifier,
    ) {
        VideoRoutesContent(
            state = state, onBack = onBack, onQueryChange = onQuery, onSizeChange = onSize,
            onPrepare = onPrepare, onNewRoute = {}, onShowRoute = {}, onOpen = onOpen,
            onUseful = {}, onConclusionChange = onConclusion, onSave = onSave, onSaveAndBack = onSaveAndBack,
            onCompleted = {}, onErase = {}, onReload = onReload, modifier = modifier,
        )
    }
}
