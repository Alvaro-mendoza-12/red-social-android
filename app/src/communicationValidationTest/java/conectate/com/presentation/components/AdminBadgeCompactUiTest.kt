package conectate.com.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AdminBadgeCompactUiTest {
    private val motionScale = TestMotionDurationScale()

    @get:Rule
    val compose = createComposeRule(effectContext = motionScale)

    @Test
    fun defaultBadgeFitsCompactBoundsAndAnnouncesOneAccessibleLabel() {
        compose.runOnUiThread { motionScale.scaleFactor = 0f }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                MaterialTheme { AdminBadge(Modifier.testTag("admin"), showVerifiedLine = false) }
            }
        }
        val badge = compose.onNodeWithTag("admin")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Administrador verificado")
        val bounds = badge.fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("Compact ADMIN must fit within 64 dp", bounds.width <= 64f * density + 1f)
        assertTrue("Compact ADMIN must fit within 18 dp", bounds.height <= 18f * density + 1f)
        assertFalse("The accessible label must replace duplicate child text",
            badge.fetchSemanticsNode().config.contains(SemanticsProperties.Text))
    }

    @Test
    fun largeAccessibilityTextCanIncreaseBadgeWidthAndHeight() {
        compose.runOnUiThread { motionScale.scaleFactor = 0f }
        compose.setContent {
            MaterialTheme {
                Column {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                        AdminBadge(Modifier.testTag("normal"), showVerifiedLine = false)
                    }
                    CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                        AdminBadge(Modifier.testTag("large"), showVerifiedLine = false)
                    }
                }
            }
        }
        val normal = compose.onNodeWithTag("normal").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val large = compose.onNodeWithTag("large").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Badge text must follow system font scaling", large.width > normal.width)
        assertTrue("Badge height must expand instead of clipping large text", large.height > normal.height)
    }

    @Test
    fun verifiedLineStaysInsideItsBadgeComponent() {
        compose.runOnUiThread { motionScale.scaleFactor = 0f }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                MaterialTheme {
                    Column {
                        AdminBadge(Modifier.testTag("without_line"), showVerifiedLine = false)
                        AdminBadge(Modifier.testTag("with_line"), showVerifiedLine = true)
                    }
                }
            }
        }
        val plain = compose.onNodeWithTag("without_line").fetchSemanticsNode().boundsInRoot
        val lined = compose.onNodeWithTag("with_line").fetchSemanticsNode().boundsInRoot
        val expectedExtraHeight = with(compose.density) { 2.dp.toPx() }
        assertTrue("The optional line must not enlarge badge width", kotlin.math.abs(lined.width - plain.width) <= 1f)
        assertTrue("The optional line must be contained below the chip",
            kotlin.math.abs(lined.height - plain.height - expectedExtraHeight) <= 1f)
    }

    @Test
    fun disablingMotionFreezesHighlightAndReenablingRestoresItsSweep() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { motionScale.scaleFactor = 0f }
        compose.setContent { MaterialTheme { AdminBadge(Modifier.testTag("admin"), showVerifiedLine = false) } }
        compose.mainClock.advanceTimeByFrame()
        val initial = pixels()
        compose.mainClock.advanceTimeBy(800L)
        assertTrue("Reduced motion must keep the diagonal highlight static", initial.contentEquals(pixels()))

        compose.runOnUiThread { motionScale.scaleFactor = 1f }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val movingStart = pixels()
        compose.mainClock.advanceTimeBy(1200L)
        assertFalse("The normal badge must have a moving diagonal highlight", movingStart.contentEquals(pixels()))

        compose.runOnUiThread { motionScale.scaleFactor = 0f }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val frozen = pixels()
        compose.mainClock.advanceTimeBy(800L)
        assertTrue("Changing the system scale to zero must stop the active sweep", frozen.contentEquals(pixels()))
    }

    private fun pixels(): IntArray {
        val bitmap = compose.onNodeWithTag("admin").captureToImage().asAndroidBitmap()
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    private class TestMotionDurationScale : MotionDurationScale {
        override var scaleFactor: Float by mutableFloatStateOf(1f)
    }
}
