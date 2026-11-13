package conectate.com.presentation.messages

import android.graphics.Color
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.presentation.messages.stickers.StickerEditor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class AnimatedStickerImportDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun importedWebpSendsOriginal() = checkOriginal("three-frames.webp")
    @Test fun importedGifSendsOriginal() = checkOriginal("three-frames.gif")

    private fun checkOriginal(asset: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File.createTempFile("sticker-qa", ".media", instrumentation.targetContext.cacheDir)
        try {
            instrumentation.context.assets.open("stickers/$asset").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            var originalSelected = false
            compose.setContent {
                MaterialTheme {
                    StickerEditor(Uri.fromFile(file), onDismiss = {},
                        onSave = { _, _ -> error("Animation exported as PNG") },
                        onSendOriginal = { originalSelected = true })
                }
            }
            val image = compose.onNodeWithContentDescription("Sticker")
            val colors = mutableSetOf<Int>()
            compose.waitUntil(15_000) {
                if (compose.onAllNodesWithContentDescription("Sticker").fetchSemanticsNodes().isNotEmpty()) {
                    val bitmap = image.captureToImage().asAndroidBitmap()
                    val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    // Only count the saturated colors in the test clip, never the loading spinner.
                    if (color in setOf(Color.RED, Color.BLUE, Color.GREEN)) colors.add(color)
                }
                colors.size >= 2
            }
            compose.onNodeWithText("Enviar").performClick()
            compose.runOnIdle { assertTrue(originalSelected) }
        } finally { file.delete() }
    }
}
