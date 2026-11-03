package conectate.com.presentation.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PostMediaTileUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localPhotoOpensAndChangingToAMissingPhotoShowsTheError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File.createTempFile("post-tile-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val source = mutableStateOf(Uri.fromFile(photo).toString())
        var opens = 0
        try {
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            compose.setContent {
                MaterialTheme {
                    PostMediaTile(source.value, Modifier.size(240.dp)) { opens++ }
                }
            }
            compose.waitUntil(5000) {
                compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                    .fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("No se pudo cargar la imagen").assertDoesNotExist()
            compose.onNodeWithContentDescription("Imagen de publicación").performClick()
            compose.runOnIdle {
                assertEquals(1, opens)
                source.value = Uri.fromFile(File(photo.parentFile, "${photo.name}-missing.png")).toString()
            }
            compose.waitUntil(5000) {
                compose.onAllNodesWithContentDescription("No se pudo cargar la imagen").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("No se pudo cargar la imagen").assertIsDisplayed()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            photo.delete()
        }
    }
}
