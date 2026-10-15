package conectate.com.presentation.video

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VideoFeedNoMatchesDeviceAuditTest {
    @get:Rule val compose = createComposeRule()
    @Test fun emptyRankingOffersPreferencesWithoutClaimingNetworkFailure() {
        var reviewed = 0
        compose.setContent { MaterialTheme { VideoFeedNoMatchesPage({ reviewed++ }, {}) } }
        compose.onNodeWithText("No hay videos que coincidan").assertIsDisplayed()
        compose.onNodeWithText("Revisa tu conexión y vuelve a intentarlo.").assertDoesNotExist()
        compose.onNodeWithText("Reintentar").assertDoesNotExist()
        compose.onNodeWithText("Revisar Mi algoritmo").performClick()
        compose.runOnIdle { assertEquals(1, reviewed) }
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "communication-qa/video-feed-no-matches.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { check(compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Test fun anotherSearchRequiresExplicitUserAction() {
        var refreshed = 0
        compose.setContent { MaterialTheme { VideoFeedNoMatchesPage({}, { refreshed++ }) } }
        compose.runOnIdle { assertEquals(0, refreshed) }
        compose.onNodeWithText("Volver a buscar").performClick()
        compose.runOnIdle { assertEquals(1, refreshed) }
    }
}
