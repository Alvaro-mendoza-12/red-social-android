package conectate.com.presentation.documents

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native Android verification: copied text and PdfDocument output are opened by the same real reader. */
@RunWith(AndroidJUnit4::class)
@MediumTest
class DocumentProcessingTest {
    @Test fun aThreeHundredPagePdfOpensAndRendersOnlyTheRequestedPages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File.createTempFile("document-300-pages-", ".pdf", context.cacheDir)
        try {
            val pdf = android.graphics.pdf.PdfDocument()
            try {
                repeat(300) { index ->
                    val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    page.canvas.drawText("Pagina ${index + 1}", 40f, 80f, android.graphics.Paint().apply { textSize = 20f })
                    pdf.finishPage(page)
                }
                original.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            DocumentReadSession(context).use { session ->
                val opened = session.open(Uri.fromFile(original))
                assertEquals(300, (opened.body as DocumentBody.Pdf).pages)
                for (index in listOf(0, 299)) {
                    val page = renderDocumentPdfPage(opened.file!!, index)
                    assertTrue(page.width in 1..1200)
                    assertTrue(page.height in 1..1600)
                    page.recycle()
                }
                assertTrue(original.isFile)
            }
        } finally { original.delete() }
    }

    @Test fun editingAndPdfConversionKeepTheOriginalAndProduceReadableCopies() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File.createTempFile("document-original-", ".txt", context.cacheDir)
        val copies = mutableListOf<File>()
        try {
            original.writeText("Documento original\nCafé, 10", Charsets.UTF_8)
            DocumentReadSession(context).use { session ->
                val opened = session.open(Uri.fromFile(original))
                assertEquals("Documento original\nCafé, 10", (opened.body as DocumentBody.Text).value)
                val edited = "Documento corregido\nCafé, 20"
                val textCopy = createDocumentCopy(context, original.name, edited, asPdf = false).also(copies::add)
                assertEquals(edited, textCopy.readText())
                assertEquals("Documento original\nCafé, 10", original.readText())

                val pdfCopy = createDocumentCopy(context, original.name, edited, asPdf = true).also(copies::add)
                assertTrue(pdfCopy.inputStream().use { input -> ByteArray(5).also { input.read(it) } }.toString(Charsets.US_ASCII) == "%PDF-")
                DocumentReadSession(context).use { pdfSession ->
                    val pdf = pdfSession.open(Uri.fromFile(pdfCopy))
                    assertTrue(pdf.body is DocumentBody.Pdf)
                    assertTrue((pdf.body as DocumentBody.Pdf).pages >= 1)
                    val page = renderDocumentPdfPage(pdf.file!!, 0)
                    assertTrue(page.width in 1..1_200)
                    assertTrue(page.height in 1..1_600)
                    page.recycle()
                }
                assertEquals("Documento original\nCafé, 10", original.readText())
            }
        } finally {
            copies.forEach(File::delete)
            original.delete()
        }
    }
}
