package conectate.com.presentation.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Coil/Compose with local fixtures; the test body makes no API requests or account changes. */
@RunWith(AndroidJUnit4::class)
class PostPhotoScalingDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun photo(width: Int, height: Int): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("post-photo-scaling-", ".png", context.cacheDir)
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
            return file
        } finally { bitmap.recycle() }
    }

    private fun awaitLoaded() {
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("No se pudo cargar la imagen").assertDoesNotExist()
    }

    private fun assertFourCorners(tag: String, expectedAspectRatio: Float) {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val counts = IntArray(4)
        var minX = pixels.width; var maxX = -1
        var minY = pixels.height; var maxY = -1
        for (y in 0 until pixels.height step 2) for (x in 0 until pixels.width step 2) {
            val c = pixels[x, y]
            val corner = when {
                c.red > .8f && c.green < .2f && c.blue < .2f -> 0
                c.red < .2f && c.green > .8f && c.blue < .2f -> 1
                c.red < .2f && c.green < .2f && c.blue > .8f -> 2
                c.red > .8f && c.green > .8f && c.blue < .2f -> 3
                else -> -1
            }
            if (corner >= 0) {
                counts[corner]++
                minX = minOf(minX, x); maxX = maxOf(maxX, x)
                minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
        assertTrue("All four source corners must remain visible: ${counts.contentToString()}", counts.all { it > 20 })
        val displayedAspectRatio = (maxX - minX + 1).toFloat() / (maxY - minY + 1)
        assertTrue("The visible photograph must retain its proportion: $displayedAspectRatio / $expectedAspectRatio",
            displayedAspectRatio / expectedAspectRatio in .85f..1.15f)
    }

    private fun stageHeightDp(): Float =
        compose.onNodeWithTag("post_media_stage").fetchSemanticsNode().boundsInRoot.height / compose.density.density

    @Test fun portraitLandscapeSquareAndExtremeFormatsKeepEverySourceCorner() {
        val sizes = listOf(160 to 400, 400 to 160, 240 to 240, 100 to 1000, 1000 to 100)
        val fixtures = sizes.map { (w, h) -> photo(w, h) }
        val source = mutableStateOf(Uri.fromFile(fixtures.first()).toString())
        try {
            compose.setContent {
                MaterialTheme {
                    PostMediaTile(source.value, Modifier.size(240.dp).testTag("scaled_photo")) {}
                }
            }
            fixtures.forEachIndexed { index, file ->
                if (index > 0) compose.runOnIdle { source.value = Uri.fromFile(file).toString() }
                awaitLoaded()
                assertFourCorners("scaled_photo", sizes[index].first.toFloat() / sizes[index].second)
            }
        } finally { fixtures.forEach { check(it.name.startsWith("post-photo-scaling-")); it.delete() } }
    }

    @Test fun loadingDifferentPhotoShapesKeepsTheFollowingContentInPlace() {
        val fixtures = listOf(photo(160, 400), photo(240, 240), photo(400, 160))
        val missing = File(fixtures.first().parentFile, "${fixtures.first().name}-missing")
        val source = mutableStateOf(Uri.fromFile(missing).toString())
        try {
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.width(280.dp)) {
                        PostMediaGallery(listOf(source.value), 500.dp) {}
                        Box(Modifier.size(8.dp).testTag("following_content"))
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("No se pudo cargar la imagen").fetchSemanticsNodes().isNotEmpty()
            }
            fun followingTop() = compose.onNodeWithTag("following_content").fetchSemanticsNode().boundsInRoot.top
            val before = followingTop()
            assertEquals(210f, stageHeightDp(), 1f)
            fixtures.forEachIndexed { index, file ->
                compose.runOnIdle { source.value = Uri.fromFile(file).toString() }
                awaitLoaded()
                assertFourCorners("post_media_stage", listOf(.4f, 1f, 2.5f)[index])
                assertEquals("The next item must keep its position after decoding", before, followingTop(), 0f)
            }
        } finally { fixtures.forEach { check(it.name.startsWith("post-photo-scaling-")); it.delete() } }
    }

    @Test fun aMixedFormatCarouselRetainsTheNextPhotoAndItsOpenAction() {
        val fixtures = listOf(photo(160, 400), photo(400, 160))
        val urls = fixtures.map { Uri.fromFile(it).toString() }
        var opened = -1
        try {
            compose.setContent {
                MaterialTheme {
                    Box(Modifier.width(280.dp)) { PostMediaGallery(urls, 500.dp) { opened = it } }
                }
            }
            awaitLoaded()
            val before = stageHeightDp()
            assertFourCorners("post_media_stage", .4f)
            compose.onNodeWithTag("post_media_stage").performTouchInput { swipeLeft() }
            awaitLoaded()
            assertEquals("Swiping must keep the stage height", before, stageHeightDp(), 0f)
            assertFourCorners("post_media_stage", 2.5f)
            compose.onNodeWithTag("post_media_stage").performTouchInput { click() }
            compose.runOnIdle { assertEquals(1, opened) }
        } finally { fixtures.forEach { check(it.name.startsWith("post-photo-scaling-")); it.delete() } }
    }
}
