package conectate.com.presentation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Isolated component rendering; no account, backend or personal data. */
class InboxUnreadBadgeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun digitsRemainOpticallyCenteredAcrossFontScales() {
        val counts = listOf(1, 9, 10, 99, 100)
        val scale = mutableFloatStateOf(1f)
        var deviceDensity = 1f
        compose.setContent {
            deviceDensity = LocalDensity.current.density
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(deviceDensity, scale.floatValue)) {
                    Column(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF141318)).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text("Contador de Grupos · ${scale.floatValue}", color = Color.White)
                        counts.forEach { count ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Grupos", color = Color.White, fontSize = 12.sp)
                                InboxUnreadBadge(count, Modifier.testTag("badge-$count"))
                            }
                        }
                    }
                }
            }
        }
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "badge-audit").apply { mkdirs() }
        val evidence = mutableListOf<String>()
        for (fontScale in listOf(1f, 1.3f, 2f)) {
            compose.runOnIdle { scale.floatValue = fontScale }
            compose.waitForIdle()
            for (count in counts) {
                val node = compose.onNodeWithTag("badge-$count").assertIsDisplayed()
                val bitmap = node.captureToImage()
                val pixels = bitmap.toPixelMap()
                var minX = bitmap.width; var minY = bitmap.height; var maxX = -1; var maxY = -1
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val p = pixels[x, y]
                    if (p.red > .95f && p.green > .95f && p.blue > .95f) {
                        minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                    }
                }
                assertTrue("Badge must contain visible white digits", maxX >= minX && maxY >= minY)
                val dx = abs((minX + maxX + 1) / 2f - bitmap.width / 2f) / deviceDensity
                val dy = abs((minY + maxY + 1) / 2f - bitmap.height / 2f) / deviceDensity
                evidence.add("count=$count scale=$fontScale dxDp=$dx dyDp=$dy size=${bitmap.width}x${bitmap.height}")
                // Glyph bearing/raster rounding is distinct from layout centering.
                assertTrue("Digit ink outside center tolerance: ${evidence.last()}", dx <= 1.5f && dy <= 1.5f)
                assertTrue("Clipped digit ink: ${evidence.last()}", minX > 0 && minY > 0 && maxX < bitmap.width - 1 && maxY < bitmap.height - 1)
            }
            File(folder, "scale-${(fontScale * 100).toInt()}.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        File(folder, "measurements.txt").writeText(evidence.joinToString("\n"))
    }

    @Test fun zeroCountDoesNotRenderBadge() {
        compose.setContent { InboxUnreadBadge(0, Modifier.testTag("zero-count")) }
        compose.onNodeWithTag("zero-count").assertDoesNotExist()
    }
}
