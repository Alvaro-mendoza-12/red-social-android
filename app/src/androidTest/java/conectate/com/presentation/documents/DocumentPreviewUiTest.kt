package conectate.com.presentation.documents

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.presentation.components.DocumentSharePreviewDialog
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentPreviewUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wordEditorSendsStyledDocxCopyAndKeepsSourceUnchanged() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File.createTempFile("word-vista-previa-", ".docx", context.cacheDir)
        val sent = AtomicReference<Uri?>()
        try {
            ZipOutputStream(original.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                    <w:p><w:r><w:t>Texto original</w:t></w:r></w:p></w:body></w:document>""".trimIndent().toByteArray())
                zip.closeEntry()
            }
            val sourceBytes = original.readBytes()
            compose.setContent {
                MaterialTheme {
                    DocumentSharePreviewDialog(Uri.fromFile(original), onDismiss = {}, onConfirmDocument = sent::set)
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Editar Word como copia").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Editar Word como copia").assertIsDisplayed()
            compose.onAllNodes(isToggleable()).assertCountEquals(1).onFirst().performClick()
            compose.onNodeWithText("Párrafo 1").assertIsDisplayed()
            compose.onNodeWithText("Negrita").assertIsNotSelected()
            compose.onNodeWithText("Negrita").performClick()
            compose.onNodeWithText("Negrita").assertIsSelected()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(1).onFirst().performTextReplacement("Texto guardado")
            compose.onNodeWithText("Negrita").assertIsSelected()
            compose.onNodeWithText("Enviar copia DOCX").performClick()
            compose.waitUntil(15_000) { sent.get() != null }

            val copy = File(requireNotNull(sent.get()?.path))
            assertNotEquals(original.absolutePath, copy.absolutePath)
            val edited = copy.inputStream().use { DocumentContentParser.parseOffice(it) }
            assertEquals("Texto guardado", edited.wordParagraphs.single().text)
            assertEquals(true, edited.wordParagraphs.single().bold)
            assertArrayEquals("El archivo elegido debe seguir intacto", sourceBytes, original.readBytes())
            copy.delete()
        } finally {
            sent.get()?.path?.let(::File)?.delete()
            original.delete()
        }
    }

    @Test fun internalPreviewSendsTheEditedCopyWithoutChangingTheOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = File.createTempFile("vista-previa-", ".txt", context.cacheDir)
        val sent = AtomicReference<Uri?>()
        try {
            original.writeText("Texto original")
            compose.setContent {
                MaterialTheme {
                    DocumentSharePreviewDialog(Uri.fromFile(original), onDismiss = {}, onConfirmDocument = sent::set)
                }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("Editar como texto").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Texto original").assertIsDisplayed()
            compose.onNode(isToggleable()).performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("Copia editada para enviar")
            compose.onNodeWithText("Enviar copia editada").performClick()
            compose.waitUntil(15_000) { sent.get() != null }
            val copy = File(requireNotNull(sent.get()?.path))
            assertNotEquals(original.absolutePath, copy.absolutePath)
            assertEquals("Copia editada para enviar", copy.readText())
            assertEquals("Texto original", original.readText())
        } finally {
            sent.get()?.path?.let(::File)?.delete()
            original.delete()
        }
    }
}
