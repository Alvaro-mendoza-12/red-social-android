package conectate.com.presentation.raffles

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.raffles.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RaffleDrawPresentationUiTest {
    @get:Rule val compose = createComposeRule()
    private val people = List(6) { RaffleParticipant("visual-qa-$it", name = "Persona $it") }
    private fun round(count: Int = 0, id: String = "visual-r1", status: String = "running") =
        RaffleRound(id, 1, status, people, List(count) { RaffleResult(it + 1, people[it], "now") }, "qa", startedAt = "now")

    private fun capture(name: String, tag: String) {
        val bitmap = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir
        File(cache, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun wheelKeepsRedBlueGreenEvenWhenThemeIsMonochrome() {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color.Gray, secondary = Color.Gray, tertiary = Color.Gray)) {
                RaffleWheel(people)
            }
        }
        val bitmap = compose.onNodeWithTag("raffle_wheel").captureToImage().asAndroidBitmap()
        for (color in raffleWheelColors.take(3)) {
            var matches = 0
            for (y in 0 until bitmap.height step 3) for (x in 0 until bitmap.width step 3) {
                if (bitmap.getPixel(x, y) == color.toArgb()) matches++
            }
            assertTrue("Missing vivid wheel sector $color", matches > 100)
        }
        capture("qa-raffle-vivid-wheel.png", "raffle_wheel")
    }

    @Test fun newWinnerIsRevealedOnlyAfterRotationAndGetsConfetti() {
        var current by mutableStateOf(round())
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { RaffleDrawPresentation(current, people) } }
        compose.runOnIdle { current = round(1) }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("raffle_spinning").assertIsDisplayed()
        compose.onNodeWithTag("raffle_winner").assertDoesNotExist()
        val before = compose.onNodeWithTag("raffle_wheel").captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(1_000)
        val during = compose.onNodeWithTag("raffle_wheel").captureToImage().asAndroidBitmap()
        assertFalse("Wheel did not rotate between frames", before.sameAs(during))
        capture("qa-raffle-spinning.png", "raffle_wheel")
        compose.mainClock.advanceTimeBy(3_050)
        compose.onNodeWithTag("raffle_winner").assertIsDisplayed()
        compose.onNodeWithText("Persona 0").assertIsDisplayed()
        compose.onNodeWithTag("raffle_confetti").assertExists()
        capture("qa-raffle-confetti.png", "raffle_confetti")
        compose.mainClock.advanceTimeBy(1_300)
        compose.onNodeWithTag("raffle_confetti").assertDoesNotExist()
    }

    @Test fun refreshDuringSpinQueuesNextWinnerWithoutInterruptingTheFirst() {
        var current by mutableStateOf(round())
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { RaffleDrawPresentation(current, people) } }
        compose.runOnIdle { current = round(1) }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { current = round(2) }
        compose.mainClock.advanceTimeBy(2_250)
        compose.onNodeWithText("Persona 0").assertIsDisplayed()
        compose.onNodeWithText("Persona 1").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_350)
        compose.onNodeWithTag("raffle_spinning").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4_100)
        compose.onNodeWithText("Persona 1").assertIsDisplayed()
        compose.onNodeWithTag("raffle_confetti").assertExists()
    }

    @Test fun completedHistoryIsShownWithoutSpinningOrCelebratingAgain() {
        compose.setContent { MaterialTheme { RaffleDrawPresentation(round(6, status = "completed"), people) } }
        compose.onNodeWithText("Persona 5").assertIsDisplayed()
        compose.onNodeWithTag("raffle_spinning").assertDoesNotExist()
        compose.onNodeWithTag("raffle_confetti").assertDoesNotExist()
    }

    @Test fun scrollingToHistoryDoesNotCancelPresentationOrReplayTheWinner() {
        var current by mutableStateOf(round())
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme {
            val session = rememberRaffleDrawPresentationSession(current)
            LazyColumn(Modifier.fillMaxSize()) {
                item { RaffleDrawPresentation(current, people, session) }
                items(40) { Text("Historial $it") }
                item { TextButton(onClick = {}, enabled = session.state.active == null) { Text("Siguiente giro QA") } }
            }
        } }
        compose.runOnIdle { current = round(1) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(41)
        compose.mainClock.advanceTimeBy(5_600)
        compose.onNodeWithText("Siguiente giro QA").assertIsEnabled()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Persona 0").assertIsDisplayed()
        compose.onNodeWithTag("raffle_spinning").assertDoesNotExist()
        compose.onNodeWithTag("raffle_confetti").assertDoesNotExist()
    }

    @Test fun replacingRoundDuringSpinAndCelebrationCancelsOnlyTheOldPresentation() {
        var current by mutableStateOf(round(1))
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { RaffleDrawPresentation(current, people) } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnIdle { current = round(1, "r2").copy(results = listOf(RaffleResult(1, people[2], "now"))) }
        compose.mainClock.advanceTimeBy(2_250)
        compose.onNodeWithTag("raffle_winner").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Persona 2").assertIsDisplayed()
        compose.onNodeWithTag("raffle_confetti").assertExists()
        compose.runOnIdle { current = round(1, "r3").copy(results = listOf(RaffleResult(1, people[3], "now"))) }
        compose.mainClock.advanceTimeBy(250)
        compose.onNodeWithTag("raffle_winner").assertDoesNotExist()
        compose.onNodeWithTag("raffle_confetti").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithText("Persona 3").assertIsDisplayed()
        compose.onNodeWithTag("raffle_confetti").assertExists()
    }
}
