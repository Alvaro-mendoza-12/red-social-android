package conectate.com.presentation.posts
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import conectate.com.data.model.Post
import conectate.com.data.model.PostReactionUser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
class PostReactionsContentDeviceTest {
    @get:Rule val compose=createComposeRule()
    private val post=Post(id="p",userId="owner",userName="Owner",likeCount=2,reactionCounts=mapOf("LIKE" to 1,"LOVE" to 1))
    @Test fun tappingARealPersonOpensThatProfile() {
        var opened:String?=null
        compose.setContent { MaterialTheme { PostReactionUsersContent(post,
            PostReactionUsersState("p",listOf(PostReactionUser("first","Primera persona",reactionType="LIKE"),PostReactionUser("second","Segunda persona",reactionType="LOVE"))),null,{},{},{opened=it}) } }
        compose.onNodeWithText("Primera persona").assertIsDisplayed();compose.onNodeWithText("Segunda persona").performClick();assertEquals("second",opened)
    }
    @Test fun failedReadHasAWorkingRetry() {
        var retries=0;compose.setContent { MaterialTheme { PostReactionUsersContent(post,PostReactionUsersState("p",error="No se pudieron cargar las reacciones."),null,{}, {retries++},{}) } }
        compose.onNodeWithText("Reintentar").performClick();assertEquals(1,retries);compose.onNodeWithText("Todavía no hay reacciones visibles.").assertDoesNotExist()
    }
    @Test fun filterPassesTheSelectedReactionType() {
        var selected:String?=null;compose.setContent { MaterialTheme { PostReactionUsersContent(post,PostReactionUsersState("p"),null,{selected=it},{},{}) } }
        compose.onNodeWithText("❤️ 1").performClick();assertEquals("LOVE",selected)
    }
}
