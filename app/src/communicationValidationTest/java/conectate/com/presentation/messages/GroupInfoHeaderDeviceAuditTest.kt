package conectate.com.presentation.messages

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Local photo bytes and missing-image fallback; no production account or network. */
@OptIn(ExperimentalTestApi::class)
class GroupInfoHeaderDeviceAuditTest {
    @get:Rule val compose = createComposeRule()
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "communication-qa/$name")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { check(compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun groupPhotoLoadsAndIsCenteredWithItsNameAndOpensOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(context.cacheDir, "owned-group-photo.png")
        val bitmap = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.BLUE)
        canvas.drawRect(0f,0f,20f,64f,Paint().apply { color=Color.CYAN })
        photo.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
        var opened: String? = null
        try {
            compose.setContent { MaterialTheme {
                Box(Modifier.width(360.dp).testTag("card")) {
                    GroupInfoHeader("buscar errores",null,photo.toURI().toString(),5,{ opened=it })
                }
            } }
            compose.waitUntil(timeoutMillis=10_000) {
                val image=compose.onNodeWithTag("group_info_avatar").captureToImage().asAndroidBitmap()
                val color=image.getPixel(image.width/2,image.height/2)
                Color.blue(color)>180 && Color.red(color)<70 && Color.green(color)<120
            }
            val card=compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
            val avatar=compose.onNodeWithTag("group_info_avatar").fetchSemanticsNode().boundsInRoot
            val name=compose.onNodeWithText("buscar errores").fetchSemanticsNode().boundsInRoot
            assertTrue(kotlin.math.abs(avatar.center.x-card.center.x)<=1f)
            assertTrue(kotlin.math.abs(name.center.x-card.center.x)<=1f)
            assertTrue(name.top>=avatar.bottom)
            compose.onNodeWithText("5 miembros").assertIsDisplayed()
            compose.onNodeWithTag("group_info_avatar").performClick()
            assertEquals(photo.toURI().toString(),opened)
            screenshot("group-info-visible-photo.png")
        } finally { photo.delete() }
    }
    @Test fun missingGroupPhotoHasVisibleInitialAndNoBrokenImageAction() {
        var opened=false
        compose.setContent { MaterialTheme { Box(Modifier.width(360.dp)) {
            GroupInfoHeader("buscar errores",null,"  ",5,{ opened=true })
        } } }
        compose.onNodeWithText("B",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Foto del grupo").assertIsNotEnabled()
        assertFalse(opened)
        screenshot("group-info-visible-fallback.png")
    }
}
