package conectate.com.presentation.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Original user-provided samples are pushed locally; never embedded in the APK. */
@RunWith(AndroidJUnit4::class)
class OriginalStickerAnimationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun problematicOriginalKeepsMoving() = checkAnimation("qa-sticker-original.webp")
    @Test fun comparisonOriginalKeepsMoving() = checkAnimation("qa-sticker-comparison.webp")
    private fun checkAnimation(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(context.getExternalFilesDir(null),name)
        assumeTrue("Explicit local sticker fixture required",source.isFile)
        compose.setContent { MaterialTheme {StickerArtwork(source.toURI().toString(),description="Original de prueba",modifier=Modifier.size(160.dp))} }
        compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Original de prueba")).fetchSemanticsNodes().isNotEmpty() }
        // AnimatedDrawable advances on Android's rendering clock, independently of Compose's test clock.
        Thread.sleep(900)
        val hashes=mutableSetOf<Int>()
        repeat(6) {
            val bitmap=compose.onNodeWithContentDescription("Original de prueba").captureToImage().asAndroidBitmap()
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            hashes.add(pixels.contentHashCode())
            Thread.sleep(170)
        }
        assertTrue("Original sticker rendered only one frame",hashes.size>1)
    }
}
