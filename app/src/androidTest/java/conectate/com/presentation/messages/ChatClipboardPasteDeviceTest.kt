package conectate.com.presentation.messages

import android.content.ClipData
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalFoundationApi::class)
class ChatClipboardPasteDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var pastedImages by mutableStateOf<List<Pair<Uri, String>>>(emptyList())

    @Before fun resetState() {
        pastedImages = emptyList()
    }

    @After fun clearClipboard() {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).clearPrimaryClip()
    }

    private fun showComposer() {
        compose.setContent {
            MaterialTheme {
                var draft by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
                val state = rememberChatComposerFieldState(draft) { draft = it }
                TextField(
                    state = state,
                    modifier = Modifier.testTag("clipboard_chat_input")
                        .contentReceiver(rememberChatImagePasteReceiver { pastedImages = it }),
                    placeholder = { Text("Mensaje") },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun showClipboardImagePasteAction() {
        compose.setContent {
            MaterialTheme {
                ClipboardImagePasteAction(maxItems = 30) { pastedImages = it }
            }
        }
        compose.waitForIdle()
    }

    private fun paste(clip: ClipData) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(clip)
        compose.onNodeWithTag("clipboard_chat_input").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_PASTE)
    }

    @Test fun plainTextClipboardPasteUpdatesTheChatDraft() {
        showComposer()
        paste(ClipData.newPlainText("qa", "Texto pegado desde el portapapeles"))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Texto pegado desde el portapapeles").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("clipboard_chat_input").assertTextContains("Texto pegado desde el portapapeles")
    }

    @Test fun clipboardImageIsRoutedToTheExistingMediaComposerCallback() {
        showClipboardImagePasteAction()
        val image = createChatCaptureFile(context, video = false)
        try {
            val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.MAGENTA)
            }
            image.outputStream().use { output ->
                try { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
                finally { bitmap.recycle() }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", image)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            // The emulator's host clipboard bridge can overwrite a URI clip
            // with text between instrumentation/UI threads. Publish and paste
            // on the focused UI thread, as one explicit user action.
            compose.onNodeWithTag("clipboard_image_paste").performSemanticsAction(SemanticsActions.OnClick) { click ->
                clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "qa-image", uri))
                click()
            }
            compose.waitUntil(5_000) { pastedImages.isNotEmpty() }
            assertEquals(uri, pastedImages.single().first)
            assertTrue(pastedImages.single().second.startsWith("image/"))
        } finally {
            image.delete()
        }
    }

    @Test fun keyboardImageCommitIsRoutedThroughTheNativeContentReceiver() {
        showComposer()
        val image = createChatCaptureFile(context, video = false)
        try {
            val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
            image.outputStream().use { output ->
                try { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) }
                finally { bitmap.recycle() }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", image)
            compose.onNodeWithTag("clipboard_chat_input").performClick()
            compose.waitForIdle()
            compose.runOnIdle {
                fun editor(view: android.view.View): android.view.View? {
                    if (view.onCheckIsTextEditor()) return view
                    if (view is android.view.ViewGroup) {
                        for (index in 0 until view.childCount) editor(view.getChildAt(index))?.let { return it }
                    }
                    return null
                }
                val info = android.view.inputmethod.EditorInfo()
                val connection = requireNotNull(editor(compose.activity.window.decorView)?.onCreateInputConnection(info))
                assertTrue(connection.commitContent(
                    android.view.inputmethod.InputContentInfo(
                        uri, android.content.ClipDescription("qa-image", arrayOf("image/jpeg")), null,
                    ),
                    0, null,
                ))
            }
            compose.waitUntil(5_000) { pastedImages.isNotEmpty() }
            assertEquals(uri, pastedImages.single().first)
            assertEquals("image/jpeg", pastedImages.single().second)
        } finally {
            image.delete()
        }
    }
}
