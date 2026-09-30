package conectate.com

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.model.CallInvite
import conectate.com.presentation.calls.IncomingCallScreen
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AccessibilityLayoutAuditTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowIncomingCallWithDoubleTextKeepsBothActionsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(320.dp, 480.dp).testTag("audit_viewport")) {
                        IncomingCallScreen(CallInvite("layout-qa", "Llamada de prueba", "layout-owner",
                            "María Alejandra de los Ángeles — nombre largo de prueba", null, "dm", null, false),
                            null, {}, {})
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
        assertTrue("Actions overlap", d.bottom <= a.top)
        assertTrue("Answer touch target too small", a.height >= 48 * compose.density.density)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = instrumentation.targetContext.getExternalFilesDir("communication-qa")!!.apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "incoming-narrow-double-text.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
