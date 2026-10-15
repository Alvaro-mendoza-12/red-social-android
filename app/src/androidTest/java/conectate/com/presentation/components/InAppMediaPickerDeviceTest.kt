package conectate.com.presentation.components

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.core.media.savePublicMedia
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class InAppMediaPickerDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun selectsAndEditsAnOwnedPhotoWithoutAnExternalFileBrowser() = runBlocking<Unit> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("conectate.com.qa.cierre11",context.packageName)
        val bitmap=Bitmap.createBitmap(32,24,Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
        val source=try { savePublicMedia(context,"Novvin_QA_${UUID.randomUUID()}.png","image/png",{}) {
            check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
        } } finally { bitmap.recycle() }
        val chosen=AtomicReference<Uri?>()
        try {
            compose.setContent { MaterialTheme {
                val picker=rememberInAppMediaPicker { chosen.set(it) }
                Button(onClick={picker.launch("image/*")}) { Text("Seleccionar imagen QA") }
            } }
            compose.onNodeWithText("Seleccionar imagen QA").performClick()
            val tag="composer_gallery_image_${ContentUris.parseId(source)}"
            compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithContentDescription("Continuar").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("Usar archivo").fetchSemanticsNodes().singleOrNull()
                    ?.config?.contains(SemanticsProperties.Disabled)==false
            }
            compose.onNodeWithContentDescription("Girar").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("Usar archivo").fetchSemanticsNodes().singleOrNull()
                    ?.config?.contains(SemanticsProperties.Disabled)==false
            }
            compose.onNodeWithContentDescription("Usar archivo").performClick()
            compose.waitUntil(15_000) { chosen.get()!=null }
            context.contentResolver.openInputStream(chosen.get()!!).use {
                val image=checkNotNull(BitmapFactory.decodeStream(it))
                try { assertEquals(24,image.width);assertEquals(32,image.height) } finally {image.recycle()}
            }
        } finally {
            context.contentResolver.delete(source,null,null)
            chosen.get()?.takeIf { it.authority=="${context.packageName}.provider" }
                ?.let { context.contentResolver.delete(it,null,null) }
        }
    }
}
