package conectate.com.presentation.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.Message
import conectate.com.presentation.components.MessageBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@RunWith(AndroidJUnit4::class)
class StickerDeliveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val sticker = Message(
        id = "delivery-artwork-test", sender_id = "qa-sender", content = "🦖",
        content_type = "sticker", created_at = "2026-10-02T15:26:00Z",
    )

    private fun render(status: String, progress: Float? = null, own: Boolean = true, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.width(320.dp).background(MaterialTheme.colorScheme.background).padding(12.dp)) {
                        MessageBubble(sticker.copy(status = status), own, uploadProgress = progress, onRetrySend = {})
                    }
                }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.externalCacheDir, "qa-delivery-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test fun failedStickerRetryStaysCenteredAtDoubleFontSize() {
        render("failed", progress = 1f, fontScale = 2f)
        val artwork = compose.onNodeWithTag("sticker_artwork", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val retry = compose.onNodeWithTag("sticker_retry", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(artwork.center.x, retry.center.x, 1f)
        assertEquals(artwork.center.y, retry.center.y, 1f)
        assertTrue(artwork.contains(retry.center))
        compose.onNodeWithText("Pendiente de envío").assertDoesNotExist()
        compose.onNodeWithText("No se envió").assertDoesNotExist()
        compose.onNodeWithTag("message_delivery").assertDoesNotExist()
        saveScreenshot("failed-large-font")
    }

    @Test fun retryImmediatelyRemovesTheButtonWhenSendStarts() {
        val status = mutableStateOf("failed")
        var retries = 0
        compose.setContent {
            MaterialTheme {
                MessageBubble(sticker.copy(status = status.value), true, onRetrySend = {
                    retries++
                    status.value = "sending"
                })
            }
        }
        compose.onNodeWithContentDescription("Reintentar envío de sticker").performClick()
        compose.onNodeWithTag("sticker_retry").assertDoesNotExist()
        compose.onNodeWithContentDescription("sending", useUnmergedTree = true).assertExists()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun queuedStickerUsesOnlyTheCompactClock() {
        render("pending")
        compose.onNodeWithContentDescription("pending", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("sticker_retry").assertDoesNotExist()
        compose.onNodeWithTag("message_delivery").assertDoesNotExist()
        saveScreenshot("pending-clock")
    }

    @Test fun uploadingStickerHasProgressWithoutARetry() {
        render("sending", progress = 0.5f)
        compose.onNodeWithTag("sticker_upload", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("sticker_retry").assertDoesNotExist()
        compose.onNodeWithTag("message_delivery").assertDoesNotExist()
    }

    @Test fun sentStickerDoesNotKeepStaleUploadProgress() {
        render("sent", progress = 0.5f)
        compose.onNodeWithTag("sticker_upload").assertDoesNotExist()
        compose.onNodeWithTag("sticker_retry").assertDoesNotExist()
    }

    @Test fun receivedStickerNeverShowsSendControls() {
        render("failed", progress = 0.5f, own = false)
        compose.onNodeWithTag("sticker_upload").assertDoesNotExist()
        compose.onNodeWithTag("sticker_retry").assertDoesNotExist()
    }

    @Test fun otherQueuedMessagesDoNotOfferConcurrentManualRetries() {
        compose.setContent { MaterialTheme { MessageDeliveryControls("pending", null, {}) } }
        compose.onNodeWithText("Esperando envío").assertIsDisplayed()
        compose.onNodeWithText("Reintentar").assertDoesNotExist()
    }
}
