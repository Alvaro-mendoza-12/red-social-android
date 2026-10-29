package conectate.com.presentation.social

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import conectate.com.data.model.Post
import conectate.com.data.model.User
import conectate.com.data.model.Video
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RankingNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun touchingAVideoOpensItsActualId() {
        var opened: String? = null
        compose.setContent { MaterialTheme { VideoRanking(listOf(Video(id = "video-id", title = "Video real"))) { opened = it.id } } }
        compose.onNodeWithText("Video real").performClick()
        compose.runOnIdle { assertEquals("video-id", opened) }
    }
    @Test fun touchingAProfileOpensItsActualId() {
        var opened: String? = null
        compose.setContent { MaterialTheme { UserRanking(listOf(User(id = "user-id", username = "Persona"))) { opened = it.id } } }
        compose.onNodeWithText("Persona").performClick()
        compose.runOnIdle { assertEquals("user-id", opened) }
    }
    @Test fun touchingAPostOpensItsActualId() {
        var opened: String? = null
        compose.setContent { MaterialTheme { PostRanking(listOf(Post(id = "post-id", userId = "author", userName = "Publicación"))) { opened = it.id } } }
        compose.onNodeWithText("Publicación").performClick()
        compose.runOnIdle { assertEquals("post-id", opened) }
    }
}
