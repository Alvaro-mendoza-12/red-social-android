package conectate.com.presentation.videos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import conectate.com.data.model.VideoComment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class VideoCommentsLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowReplyKeepsReplyAndReactionAvailableAndMenuKeepsOtherActions() {
        val actions = mutableListOf<String>()
        val comment = VideoComment(
            id = "comment-test",
            content = "Una respuesta con texto de varias líneas",
            created_at = Instant.now().minusSeconds(120).toString(),
        )
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                MaterialTheme {
                    Box(Modifier.width(248.dp)) {
                        VideoCommentItem(
                            comment = comment,
                            isReply = true,
                            canDelete = true,
                            onReplyClick = { actions += "reply" },
                            onReaction = { actions += it.orEmpty() },
                            onDelete = { actions += "delete" },
                            onReport = { actions += "report" },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Responder").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Me gusta").assertIsDisplayed().performClick()
        fun openOptions() {
            compose.onNodeWithContentDescription("Más opciones del comentario").assertIsDisplayed().performClick()
        }
        openOptions()
        compose.onNodeWithText("Me encanta").performClick()
        openOptions()
        compose.onNodeWithText("Eliminar").performClick()
        openOptions()
        compose.onNodeWithText("Reportar").performClick()
        compose.runOnIdle { assertEquals(listOf("reply", "LIKE", "LOVE", "delete", "report"), actions) }
    }
}
