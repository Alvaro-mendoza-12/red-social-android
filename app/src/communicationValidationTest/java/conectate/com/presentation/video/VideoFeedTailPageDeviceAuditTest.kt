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

class VideoFeedTailPageDeviceAuditTest {
    @get:Rule val compose=createComposeRule()
    @Test fun exhaustedCatalogOffersContinuationInsteadOfAnEnd() {
        var refresh=0;var search=0
        compose.setContent { MaterialTheme { VideoFeedTailPage(true,false,null,{}, {refresh++}, {search++}) } }
        compose.onNodeWithText("Cargando tu próximo video").assertIsDisplayed();compose.onNodeWithText("Llegaste al final").assertDoesNotExist()
        compose.onNodeWithText("Continuar").performClick();compose.onNodeWithText("Buscar videos").performClick()
        compose.runOnIdle { assertEquals(1,refresh);assertEquals(1,search) }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"communication-qa/video-feed-tail.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { check(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun loadingDoesNotClaimTheCatalogIsExhausted() {
        compose.setContent { MaterialTheme { VideoFeedTailPage(false,true,null,{}, {}, {}) } }
        compose.onNodeWithText("Cargando más videos").assertIsDisplayed();compose.onNodeWithText("Llegaste al final").assertDoesNotExist()
        compose.onNodeWithText("Actualizar").assertDoesNotExist()
    }
    @Test fun pageFailureRetriesWithoutPretendingItReachedTheEnd() {
        var attempts=0
        compose.setContent { MaterialTheme { VideoFeedTailPage(false,false,"Failed",{attempts++}, {}, {}) } }
        compose.onNodeWithText("No pudimos cargar más videos").assertIsDisplayed();compose.onNodeWithText("Reintentar").performClick()
        compose.runOnIdle { assertEquals(1,attempts) };compose.onNodeWithText("Llegaste al final").assertDoesNotExist()
    }
}
