package conectate.com.data.documents

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class DocumentCatalogTest {
    private fun document(uri: String, name: String, path: String = "Trabajo", ancestors: List<String> = listOf("root")) =
        AvailableDocument(uri, name, "application/octet-stream", null, null, path, ancestors)

    @Test fun `provider MIME and filename fallback organize office documents and CSV correctly`() {
        assertEquals(DocumentCategory.PDF, documentCategory("archivo", "APPLICATION/PDF; version=1.7"))
        assertEquals(DocumentCategory.WORD, documentCategory("Contrato.DOCX", "application/octet-stream"))
        assertEquals(DocumentCategory.EXCEL, documentCategory("Ventas.CSV", "text/csv"))
        assertEquals(DocumentCategory.POWERPOINT, documentCategory("Sesión.PPTX", "application/octet-stream"))
        assertEquals(DocumentCategory.TEXT, documentCategory("notas", "text/plain"))
        assertEquals(DocumentCategory.OTHER, documentCategory("respaldo.bin", "application/octet-stream"))
    }

    @Test fun `search matches all words across accented names and folders independent of device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val expected = document("one", "INFORME sesión.pdf", "Área técnica")
            val others = listOf(document("two", "Informe.pdf", "Descargas"), document("three", "sesion.txt"))
            assertEquals(listOf(expected), filterDocuments(others + expected, DocumentCategory.ALL, "  SESION   tecnica ", null))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `folder and category filters combine without leaking sibling files and duplicate grants`() {
        val included = document("one", "Zeta.pdf", ancestors = listOf("root", "child"))
        val first = document("two", "Álbum.pdf", ancestors = listOf("root", "child", "nested"))
        val sibling = document("three", "Fuera.pdf", ancestors = listOf("root", "sibling"))
        val sheet = document("four", "Tabla.xlsx", ancestors = listOf("root", "child"))
        assertEquals(listOf(first, included), filterDocuments(listOf(included, sibling, sheet, first, included), DocumentCategory.PDF, "", "child"))
        assertEquals(4, filterDocuments(listOf(included, sibling, sheet, first), DocumentCategory.ALL, "", null).size)
    }

    @Test fun `unavailable size is distinct from an empty document`() {
        assertEquals("Tamaño no disponible", formatDocumentSize(null))
        assertEquals("Tamaño no disponible", formatDocumentSize(-1))
        assertEquals("0 B", formatDocumentSize(0))
    }
    @Test fun `optional extension restriction filters LRC without changing chat defaults`() {
        val lyric = document("lyric", "Canción.LRC", ancestors = listOf("root", "music"))
        val renamed = document("renamed", "fake.lrc.exe", ancestors = listOf("root", "music"))
        val plain = document("plain", "notes.txt", ancestors = listOf("root", "music"))
        val otherFolder = document("other", "Outside.lrc", ancestors = listOf("root", "other"))
        val documents = listOf(lyric, renamed, plain, otherFolder)
        assertEquals(listOf(lyric), filterDocuments(documents, DocumentCategory.ALL, "", "music", setOf(".LRC")))
        assertEquals(listOf(lyric), filterDocuments(documents, DocumentCategory.ALL, "cancion", null, setOf("lrc")))
        assertTrue(filterDocuments(documents, DocumentCategory.ALL, "", null, emptySet()).isEmpty())
        assertEquals(4, filterDocuments(documents, DocumentCategory.ALL, "", null).size)
    }

}
