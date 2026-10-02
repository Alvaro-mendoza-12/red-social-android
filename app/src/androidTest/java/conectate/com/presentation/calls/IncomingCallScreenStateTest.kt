package conectate.com.presentation.calls

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.CallInvite
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IncomingCallScreenStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun answeringShowsProgressAndDisablesBothActions() {
        val processing = mutableStateOf(false)
        var accepts = 0
        compose.setContent {
            MaterialTheme {
                IncomingCallScreen(invite(), null,
                    onAccept = { accepts++; processing.value = true },
                    onDecline = { fail("A second terminal action must not run") },
                    isHandling = processing.value)
            }
        }
        compose.onNodeWithTag("incoming_accept").performClick()
        compose.onNodeWithText("Procesando llamada…").assertIsDisplayed()
        compose.onNodeWithTag("incoming_accept").assertIsNotEnabled()
        compose.onNodeWithTag("incoming_decline").assertIsNotEnabled()
        assertEquals(1, accepts)
        screenshot("incoming-processing.png")
    }

    @Test fun largeTextOnANarrowScreenKeepsTheActionsSeparateAndReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 480.dp)) {
                        IncomingCallScreen(invite("María Alejandra de los Ángeles — QA nombre largo"), null, {}, {})
                    }
                }
            }
        }
        val decline = compose.onNodeWithTag("incoming_decline")
        val accept = compose.onNodeWithTag("incoming_accept")
        decline.performScrollTo().assertIsDisplayed().assertIsEnabled()
        accept.performScrollTo().assertIsDisplayed().assertIsEnabled()
        val a = accept.fetchSemanticsNode().boundsInRoot
        val d = decline.fetchSemanticsNode().boundsInRoot
        assertTrue("Call actions overlap", d.bottom <= a.top)
        assertTrue("Answer target is smaller than 48 dp", a.height >= 48 * compose.density.density)
        screenshot("incoming-narrow-double-text.png")
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = instrumentation.targetContext.getExternalFilesDir("call-screen-qa")!!.apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun invite(name: String = "QA llamada local") = CallInvite(
        "qa-state", name, "qa-caller", name, null, "dm", null, false)
}
