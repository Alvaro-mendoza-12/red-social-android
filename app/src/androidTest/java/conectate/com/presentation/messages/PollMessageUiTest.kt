package conectate.com.presentation.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.Message
import conectate.com.data.model.MessagePollOptionState
import conectate.com.data.model.PollVoteUser
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PollMessageUiTest {
    @get:Rule val compose = createComposeRule()
    private val message = Message(content = "Encuesta QA", content_type = "poll")
    private val option = MessagePollOptionState("poll", "option", "Opción", voteCount = 1, totalVotes = 1)
    private fun render(own: Boolean, selected: Boolean = false, onVote: (String, Boolean) -> Unit = { _, _ -> }) {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme(
            primary = Color.White, primaryContainer = Color.White, onPrimaryContainer = Color.Black,
            surfaceVariant = Color(0xFF18181B), onSurface = Color.White,
        )) { Box(Modifier.width(360.dp)) {
            PollMessageCard(message, listOf(option.copy(votedByMe = selected)), own, onVote)
        } } }
    }
    private fun assertButtonInk(text: String, dark: Boolean) {
        val bounds = compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        var contrastingPixels = 0
        for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(image.width))
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(image.height)) {
                val pixel = image.getPixel(x, y)
                val brightness = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel)) / 765f
                if (if (dark) brightness < .25f else brightness > .8f) contrastingPixels++
            }
        assertTrue("Vote label must contrast with its bubble", contrastingPixels > 10)
    }
    @Test fun sentWhitePollHasReadableVoteAction() { render(true); assertButtonInk("Votar", true) }
    @Test fun receivedDarkPollHasReadableVoteAction() { render(false); assertButtonInk("Votar", false) }
    @Test fun removingOwnVoteCallsTheActionWithFalse() {
        var removed = false
        render(true, selected = true) { id, selected -> removed = id == "option" && !selected }
        assertButtonInk("Quitar voto", true)
        compose.onNodeWithText("Quitar voto").performClick()
        compose.runOnIdle { assertTrue(removed) }
        compose.onNodeWithText("1 voto").assertIsDisplayed()
    }
    @Test fun voterDialogShowsNameAndHandleWithoutAForcedTallEmptyArea() {
        compose.setContent { MaterialTheme { PollOptionVotersDialog("Opción", 1,
            listOf(PollVoteUser("qa", "qa_nombre", "Nombre QA")), false, {}) } }
        compose.onNodeWithText("Nombre QA").assertIsDisplayed()
        compose.onNodeWithText("@qa_nombre").assertIsDisplayed()
        compose.onNodeWithText("Opción (1 voto)").assertIsDisplayed()
    }
}
