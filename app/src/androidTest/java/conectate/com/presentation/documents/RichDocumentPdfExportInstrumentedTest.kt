package conectate.com.presentation.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RichDocumentPdfExportInstrumentedTest {
    @Test fun editedWordCopyRoundTripsOnAndroidAndLeavesOriginalUntouched() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "docx-editor-original-test.docx")
        val edited = File(context.cacheDir, "docx-editor-copy-test.docx")
        val originalBytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                val parts = listOf(
                    "[Content_Types].xml" to "<Types/>".toByteArray(),
                    "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                        <w:p><w:pPr><w:keepNext/></w:pPr><w:r><w:t>Texto inicial</w:t></w:r></w:p>
                        <w:p><w:r><w:drawing/></w:r><w:r><w:t>Preservar</w:t></w:r></w:p>
                        </w:body></w:document>""".trimIndent().toByteArray(),
                    "word/media/image1.png" to byteArrayOf(1, 2, 3),
                )
                parts.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            }
            output.toByteArray()
        }
        source.writeBytes(originalBytes)
        try {
            val before = DocumentContentParser.parseOffice(ByteArrayInputStream(originalBytes))
            val edits = before.wordParagraphs.mapIndexed { index, paragraph ->
                if (index == 0) WordParagraphEdit("Texto guardado", bold = true, italic = true)
                else WordParagraphEdit(paragraph.text)
            }
            WordDocumentEditor.writeEditedCopy(source, edited, edits)
            val parsed = edited.inputStream().use { DocumentContentParser.parseOffice(it) }
            assertEquals("Texto guardado", parsed.wordParagraphs[0].text)
            assertEquals(true, parsed.wordParagraphs[0].bold)
            assertEquals(true, parsed.wordParagraphs[0].italic)
            assertEquals("Preservar", parsed.wordParagraphs[1].text)
            assertTrue(parsed.htmlPreview.orEmpty().contains("<b><i>Texto guardado</i></b>"))
            assertTrue("El original debe permanecer byte por byte igual", originalBytes.contentEquals(source.readBytes()))
        } finally {
            source.delete()
            edited.delete()
        }
    }

    @Test fun exportedWordPdfContainsEmbeddedImage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.RED)
        val png = ByteArrayOutputStream().use { output ->
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        image.recycle()
        val archive = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                listOf(
                    "word/document.xml" to """<w:document xmlns:w="urn:word" xmlns:a="urn:drawing" xmlns:r="urn:rels">
                        <w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Informe</w:t></w:r></w:p>
                        <w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Texto importante</w:t></w:r></w:p>
                        <w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p></w:body>
                        </w:document>""".toByteArray(),
                    "word/_rels/document.xml.rels" to
                        """<Relationships><Relationship Id="rId1" Target="media/image1.png"/></Relationships>""".toByteArray(),
                    "word/media/image1.png" to png,
                ).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
        val html = DocumentContentParser.parseOffice(ByteArrayInputStream(archive)).htmlPreview.orEmpty()
        assertTrue(html.contains("data:image/png;base64,"))
        val output = createRichDocumentPdf(context, "Informe.docx", html)
        try {
            assertTrue(output.isFile && output.length() > 100)
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    assertTrue(renderer.pageCount >= 1)
                    renderer.openPage(0).use { page ->
                        val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        try {
                            rendered.eraseColor(Color.WHITE)
                            page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            var redPixels = 0
                            for (y in 0 until rendered.height step 3) for (x in 0 until rendered.width step 3) {
                                val pixel = rendered.getPixel(x, y)
                                if (Color.red(pixel) > 180 && Color.green(pixel) < 100 && Color.blue(pixel) < 100) redPixels++
                            }
                            assertTrue("La imagen incrustada debe aparecer en el PDF", redPixels > 10)
                        } finally { rendered.recycle() }
                    }
                }
            }
            val saved = saveDocumentToDownloads(context, output, output.name, "application/pdf")
            try {
                context.contentResolver.query(saved, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null).use { cursor ->
                    assertTrue(cursor != null && cursor.moveToFirst())
                    assertTrue(cursor!!.getString(0).contains("Download/Novvin"))
                }
                context.contentResolver.openInputStream(saved).use { stream ->
                    assertTrue(stream != null && stream.read() == '%'.code)
                }
            } finally { context.contentResolver.delete(saved, null, null) }
        } finally { output.delete() }
    }

    @Test fun largeReceivedDocumentStreamsIntoDownloads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "large-received-document-test.bin")
        val block = ByteArray(16_384) { index -> (index % 251).toByte() }
        source.outputStream().use { output -> repeat(2_200) { output.write(block) } }
        try {
            assertTrue(source.length() > DocumentLimits.INPUT_BYTES)
            val saved = saveDocumentSourceToDownloads(context, Uri.fromFile(source),
                "recibido.bin", "application/octet-stream")
            try {
                context.contentResolver.openInputStream(saved).use { stream ->
                    assertTrue(stream != null)
                    var copied = 0L
                    val buffer = ByteArray(16_384)
                    while (true) {
                        val count = stream!!.read(buffer)
                        if (count < 0) break
                        copied += count
                    }
                    assertTrue("La descarga debe conservar el tamaño", copied == source.length())
                }
            } finally { context.contentResolver.delete(saved, null, null) }
        } finally {
            source.delete()
        }
    }
}
