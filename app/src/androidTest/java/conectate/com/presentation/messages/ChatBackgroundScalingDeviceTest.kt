package conectate.com.presentation.messages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real wallpaper renderer must preserve every edge of an already cropped image. */
@RunWith(AndroidJUnit4::class)
class ChatBackgroundScalingDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun photo(width: Int, height: Int): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("wallpaper-scaling-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.GRAY)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val w = width.toFloat(); val h = height.toFloat()
            paint.color = android.graphics.Color.RED; canvas.drawRect(0f, 0f, w / 4, h / 4, paint)
            paint.color = android.graphics.Color.GREEN; canvas.drawRect(w * .75f, 0f, w, h / 4, paint)
            paint.color = android.graphics.Color.BLUE; canvas.drawRect(0f, h * .75f, w / 4, h, paint)
            paint.color = android.graphics.Color.YELLOW; canvas.drawRect(w * .75f, h * .75f, w, h, paint)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        return file
    }

    @Test fun originalSquarePortraitAndWideWallpaperKeepTheirChosenFormat() {
        val sizes = listOf(160 to 500, 240 to 240, 240 to 300, 480 to 270)
        val fixtures = sizes.map { (w, h) -> photo(w, h) }
        val source = mutableStateOf(Uri.fromFile(fixtures.first()).toString())
        try {
            compose.setContent {
                MaterialTheme(colorScheme = darkColorScheme(surface = Color.Black)) {
                    ChatBackgroundLayer("custom", source.value, Modifier.size(240.dp, 400.dp).testTag("wallpaper"))
                }
            }
            fixtures.forEachIndexed { index, file ->
                compose.runOnIdle { source.value = Uri.fromFile(file).toString() }
                compose.waitForIdle()
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                        .fetchSemanticsNodes().isEmpty()
                }
                val pixels = compose.onNodeWithTag("wallpaper").captureToImage().toPixelMap()
                val counts = IntArray(4)
                var minX = pixels.width; var maxX = -1; var minY = pixels.height; var maxY = -1
                for (y in 0 until pixels.height step 2) for (x in 0 until pixels.width step 2) {
                    val c = pixels[x, y]
                    val corner = when {
                        c.red > .15f && c.green < .08f && c.blue < .08f -> 0
                        c.green > .15f && c.red < .08f && c.blue < .08f -> 1
                        c.blue > .15f && c.red < .08f && c.green < .08f -> 2
                        c.red > .15f && c.green > .15f && c.blue < .08f -> 3
                        else -> -1
                    }
                    if (corner >= 0) {
                        counts[corner]++
                        minX = minOf(minX, x); maxX = maxOf(maxX, x)
                        minY = minOf(minY, y); maxY = maxOf(maxY, y)
                    }
                }
                assertTrue("The selected crop loses an edge: ${counts.contentToString()}", counts.all { it > 20 })
                val ratio = (maxX - minX + 1f) / (maxY - minY + 1f)
                val expected = sizes[index].first.toFloat() / sizes[index].second
                assertTrue("Wallpaper changed aspect ratio: $ratio / $expected", ratio / expected in .85f..1.15f)
            }
        } finally { fixtures.forEach { check(it.name.startsWith("wallpaper-scaling-")); it.delete() } }
    }
}
