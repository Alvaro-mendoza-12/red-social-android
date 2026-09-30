package conectate.com

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.presentation.documents.DocumentReadSession
import conectate.com.presentation.documents.PdfDocumentPages
import conectate.com.presentation.documents.InternalDocumentDialog
import conectate.com.presentation.documents.renderDocumentPdfPage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class DocumentPreviewDeviceAuditTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun pdfSwipesBothWaysAndZoomDoesNotTurnPages() {
        val file = File(context.cacheDir, "qa-swipe-pages.pdf")
        val document = PdfDocument()
        try {
            repeat(3) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("Pagina ${index + 1}", 40f, 80f, Paint().apply { color=Color.BLUE; textSize=30f })
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        try {
            compose.setContent { MaterialTheme { PdfDocumentPages(file, 3, Modifier.fillMaxSize()) } }
            compose.onNodeWithTag("document-page-counter").assertTextEquals("1 / 3")
            compose.onNodeWithText("Anterior").assertIsNotEnabled()
            compose.onNodeWithTag("document-pages").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("document-page-counter").assertTextEquals("2 / 3")
            compose.onNodeWithTag("document-pages").performTouchInput { swipeRight() }
            compose.onNodeWithTag("document-page-counter").assertTextEquals("1 / 3")
            compose.onNodeWithText("Siguiente").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("Página 2").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("document-pages").performTouchInput {
                pinch(center-Offset(35f,0f),center+Offset(35f,0f),center-Offset(110f,0f),center+Offset(110f,0f),600)
            }
            compose.waitForIdle()
            val zoomScreenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir("communication-qa"), "pdf-zoom-diagnostic.png").outputStream().use {
                zoomScreenshot?.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            zoomScreenshot?.recycle()
            compose.onNodeWithText("Restablecer zoom").assertIsDisplayed()
            compose.onNodeWithTag("document-pages").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("document-page-counter").assertTextEquals("2 / 3")
            compose.onNodeWithText("Restablecer zoom").performClick()
            compose.onNodeWithTag("document-pages").performTouchInput { swipeLeft() }
            compose.onNodeWithTag("document-page-counter").assertTextEquals("3 / 3")
            compose.onNodeWithText("Siguiente").assertIsNotEnabled()
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir("communication-qa"), "pdf-swipe-layout.png").outputStream().use {
                screenshot?.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
            screenshot?.recycle()
        } finally { file.delete() }
    }

    @Test(timeout=150_000) fun officeDesignsRenderThroughOurServerAndLeaveTheOriginalUntouched() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditOfficeFormats") == "true")
        val account = JSONArray(File(context.cacheDir,"media-qa-accounts.json").readText()).getJSONObject(0)
        val reports = JSONArray()
        fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        for (extension in listOf("docx","pptx","xlsx")) {
            val original=File(context.cacheDir,"qa-formats/sample.$extension")
            val before=digest(original)
            DocumentReadSession(context).use { session ->
                val opened=session.open(Uri.fromFile(original), original.name)
                val started=System.nanoTime()
                val preview=session.renderOffice(opened,account.getString("token"))
                val bitmap=renderDocumentPdfPage(preview.file,0)
                val colors=hashSetOf<Int>()
                for (x in 0 until bitmap.width step 12) for(y in 0 until bitmap.height step 12) colors.add(bitmap.getPixel(x,y))
                assertTrue("Office visual lost its colors: $extension", colors.size>3)
                assertEquals("Office original changed: $extension",before,digest(original))
                File(context.getExternalFilesDir("communication-qa"),"office-$extension-layout.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                }
                reports.put(JSONObject().put("format",extension).put("pages",preview.pages)
                    .put("elapsedMs",(System.nanoTime()-started)/1_000_000).put("originalPreserved",true))
                bitmap.recycle()
            }
        }
        File(context.getExternalFilesDir("communication-qa"),"office-formats.json").writeText(reports.toString(2))
    }

    @Test(timeout=150_000) fun officeDialogsShowTheLayoutForAllThreeFormats() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("auditOfficeFormats") == "true")
        val source = mutableStateOf<Uri?>(null)
        compose.setContent {
            MaterialTheme { source.value?.let { InternalDocumentDialog(it, onDismiss = {}) } }
        }
        val onlyFormat = InstrumentationRegistry.getArguments().getString("auditOfficeUiFormat")
        for (extension in listOf("docx", "pptx", "xlsx").filter { onlyFormat == null || it == onlyFormat }) {
            val original = File(context.cacheDir, "qa-formats/sample.$extension")
            compose.runOnIdle { source.value = Uri.fromFile(original) }
            compose.waitUntil(45_000) {
                compose.onAllNodesWithContentDescription("Página 1").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Diseño").assertIsDisplayed()
            compose.onNodeWithText("↓ Descargar documento").assertIsDisplayed()
            compose.onNodeWithContentDescription("Página 1").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(600)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.getExternalFilesDir("communication-qa"), "office-$extension-dialog.png").outputStream().use {
                screenshot?.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            screenshot?.recycle()
            compose.runOnIdle { source.value = null }
            compose.waitForIdle()
        }
    }
}
