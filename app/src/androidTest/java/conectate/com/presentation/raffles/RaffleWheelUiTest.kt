package conectate.com.presentation.raffles

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.raffles.RaffleParticipant
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic participants are test inputs only. No auth, preferences or production data are touched. */
@RunWith(AndroidJUnit4::class)
class RaffleWheelUiTest {
    @get:Rule val compose = createComposeRule()
    private fun participants(count: Int) = List(count) { RaffleParticipant("qa-$it", name = "Persona $it") }

    @Test fun supportsTwoFiveTenTwentyFiftyAndLargeGroupsWithAccessibleLabels() {
        var count by mutableIntStateOf(2)
        compose.setContent { MaterialTheme { RaffleWheel(participants(count)) } }
        for (next in listOf(2, 5, 10, 20, 50, 101, 1000)) {
            compose.runOnIdle { count = next }
            compose.onNodeWithContentDescription("Ruleta con $next participantes. El servidor determina los resultados.").assertIsDisplayed()
            if (next in listOf(5, 20)) {
                val picture = compose.onNodeWithContentDescription("Ruleta con $next participantes. El servidor determina los resultados.").captureToImage().asAndroidBitmap()
                java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "qa-raffle-wheel-$next.png").outputStream().use {
                    picture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            if (next > 12) compose.onNodeWithText("Todos los segmentos tienen la misma probabilidad. Consulta los nombres en la lista.").assertIsDisplayed()
        }
    }

    @Test fun animationFinishesOnceAndDoesNotModifyServerParticipantList() {
        val original = participants(5)
        var finished = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { RaffleWheel(original, original[3].id, true) { finished++ } } }
        compose.mainClock.advanceTimeBy(4500)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, finished); assertEquals(5, original.size); assertEquals("qa-3", original[3].id) }
    }

    @Test fun emptyStateDarkThemeAndLargeFontRemainUsable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme()) { RaffleWheel(emptyList()) }
            }
        }
        compose.onNodeWithContentDescription("Ruleta con 0 participantes. El servidor determina los resultados.").assertIsDisplayed()
    }

    @Test fun socialCardOpensRaffleAndShowsCreatorAndStoredTitle() {
        var opened = 0
        compose.setContent { MaterialTheme { RaffleMessageCard("Elegir equipos\n40 participantes", "Álvaro") { opened++ } } }
        compose.onNodeWithText("Creado por Álvaro").assertIsDisplayed()
        compose.onNodeWithText("Elegir equipos\n40 participantes").assertIsDisplayed()
        compose.onNodeWithText("Ver sorteo y resultados").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun creationImportsMembersExcludesOneAddsManualAndSubmitsSelectedSnapshot() {
        val members = listOf(RaffleParticipant("member-1", userId = "member-1", name = "Álvaro"),
            RaffleParticipant("member-2", userId = "member-2", name = "Carlos"))
        val submitted = AtomicReference<List<String>?>()
        val manual = AtomicReference<List<String>?>()
        compose.setContent { MaterialTheme {
            CreateRaffleForm(RafflesUiState(members = members, totalMembers = 2, membersComplete = true, canManage = false, canCreate = true), onCreate = { name, _, selected, manuals, start ->
                assertEquals("Equipos QA", name); assertFalse(start); submitted.set(selected); manual.set(manuals)
            })
        } }
        compose.onNodeWithText("Nombre del sorteo").performTextInput("Equipos QA")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("2 seleccionados · 2 miembros disponibles. Máximo 1.000 participantes.").assertIsDisplayed()
        compose.onNode(hasText("Carlos") and isToggleable()).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Ruleta con 1 participantes. El servidor determina los resultados.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Participante manual").performScrollTo().performTextInput("Pedro")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("+ Agregar participante").performScrollTo().performClick()
        // A prefetched lazy item has semantics before it is placed. Scroll using its stable key.
        compose.onNode(hasScrollToIndexAction()).performScrollToKey("manual:Pedro")
        compose.waitForIdle()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        java.io.File(cache, "qa-raffle-form-tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
        java.io.File(cache, "qa-raffle-form.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Pedro · Manual").assertIsDisplayed()
        compose.onNodeWithText("Crear preparado para comenzar luego").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("member-1"), submitted.get()); assertEquals(listOf("Pedro"), manual.get()) }
    }
}
