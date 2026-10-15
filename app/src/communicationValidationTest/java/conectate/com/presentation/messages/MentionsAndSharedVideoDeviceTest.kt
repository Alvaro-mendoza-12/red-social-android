package conectate.com.presentation.messages

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.*
import conectate.com.presentation.components.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class MentionsAndSharedVideoDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun user(id: String, name: String, handle: String) = User(id=id, display_name=name, username=handle, username_origin="chosen")
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "communication-qa/$name")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val node = if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) compose.onNode(isDialog()) else compose.onRoot()
        file.outputStream().use { check(node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun publicationDialogSuggestsAndInsertsMentionBeforePublishing() {
        var published: String? = null
        val candidates=listOf(user("ana", "Ana Pérez", "ana01"))
        compose.setContent { MaterialTheme {
            CreatePostDialog({}, { text,_,_,_,_,_ -> published=text }, mentionCandidates=candidates)
        } }
        compose.onNode(hasSetTextAction()).performTextInput("Yo @an")
        compose.onNodeWithText("@ana01").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("Yo @ana01 ")
        compose.onNodeWithTag("mention_suggestions").assertDoesNotExist()
        screenshot("mention-publication-dialog.png")
        compose.onNode(hasText("Publicar") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals("Yo @ana01 ",published) }
    }
    @Test fun roomSuggestionsDistinguishSameNameAndKeepCaret() {
        val draft=mutableStateOf(TextFieldValue("Yo @ju mañana",TextRange(6)))
        val users=listOf(user("one","Juan","juan01"),user("two","Juan","juan02"))
        compose.setContent { MaterialTheme { Column(Modifier.padding(16.dp)) {
            OutlinedTextField(draft.value,{draft.value=it})
            val active=activeMentionAt(draft.value.text,draft.value.selection.end)
            MentionSuggestions(if(active==null)emptyList() else roomMentionCandidates(users,active.query,"owner"), onSelected={user ->
                val (text,cursor)=insertMention(draft.value.text,active!!,profilePublicHandle(user))
                draft.value=TextFieldValue(text,TextRange(cursor))
            })
        } } }
        compose.onNodeWithText("@juan02").assertIsDisplayed()
        screenshot("mention-room-participants.png")
        compose.onNodeWithText("@juan02").performClick()
        compose.runOnIdle { assertEquals("Yo @juan02  mañana",draft.value.text); assertEquals(11,draft.value.selection.end) }
    }
    private fun destinations(dark: Boolean) {
        val selected=mutableStateOf(setOf<String>())
        val rows=listOf(ShareDestination("chat",ShareDestinationType.CHAT,"Ana Pérez"),
            ShareDestination("group",ShareDestinationType.GROUP,"Mi grupo de la universidad"),
            ShareDestination("community",ShareDestinationType.COMMUNITY,"Comunidad de creadores"))
        compose.setContent { MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme()) { Surface { Column(Modifier.padding(16.dp)) {
            rows.forEach { target -> ShareDestinationRow(target,target.key in selected.value,onSelected={yes ->
                selected.value=if(yes)selected.value+target.key else selected.value-target.key
            }) }
        } } } }
        rows.forEach { compose.onNodeWithText(it.title).performClick(); compose.onNodeWithText(it.title).assertIsDisplayed() }
        compose.runOnIdle { assertEquals(3,selected.value.size) }
        screenshot(if(dark)"video-share-destinations-dark.png" else "video-share-destinations-light.png")
    }
    @Test fun selectedChatGroupAndCommunityRemainReadableInDarkTheme() = destinations(true)
    @Test fun selectedChatGroupAndCommunityRemainReadableInLightTheme() = destinations(false)
    @Test fun sharedVideoCardShowsAuthorAndOpensVideo() {
        var opened=false
        compose.setContent { MaterialTheme { SharedVideoCard(SharedVideoPreview.Available(Video(userName="Creadora",title="Un paseo con mi gente")),{opened=true}) } }
        compose.onNodeWithText("Creadora").assertIsDisplayed()
        compose.onNodeWithText("Un paseo con mi gente").assertIsDisplayed()
        screenshot("shared-video-card.png")
        compose.onNodeWithTag("shared_video_card").performClick()
        compose.runOnIdle { assertTrue(opened) }
    }
    @Test fun unavailableVideoCanRetryWithoutDisplayingTechnicalError() {
        var retried=false
        compose.setContent { MaterialTheme { SharedVideoCard(SharedVideoPreview.Unavailable,{}, {retried=true}) } }
        compose.onNodeWithText("Video no disponible").assertIsDisplayed()
        compose.onNodeWithText("Volver a cargar").performClick()
        compose.runOnIdle { assertTrue(retried) }
    }
}
