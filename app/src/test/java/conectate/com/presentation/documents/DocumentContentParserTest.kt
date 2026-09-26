package conectate.com.presentation.documents

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class DocumentContentParserTest {
    @Test fun wordKeepsParagraphsAndTableCellContents() {
        val document = parse("word/document.xml" to """
            <w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:t>Título</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Columna A</w:t></w:r></w:p></w:tc>
            <w:tc><w:p><w:r><w:t>Columna B</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>
        """.trimIndent())
        assertEquals("Word · vista previa", document.format)
        assertTrue(document.sections.single().text.startsWith("Título\n"))
        assertTrue(document.sections.single().text.contains("Columna A"))
        assertTrue(document.sections.single().text.contains("\t"))
        assertTrue(document.sections.single().text.contains("Columna B"))
        assertEquals(listOf("Título", "Columna A", "Columna B"), document.wordParagraphs.map { it.text })
        assertTrue(document.wordParagraphs.all { it.editable })
    }

    @Test fun wordPreviewKeepsCommonFormattingAndEmbeddedImageWithoutExternalLink() {
        val image = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2)
        val parsed = DocumentContentParser.parseOffice(archiveBytes(listOf(
            "word/document.xml" to """<w:document xmlns:w="urn:word" xmlns:a="urn:drawing" xmlns:r="urn:rels"><w:body>
                <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>Guía &amp; fotos</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>
                </w:body></w:document>""".toByteArray(),
            "word/_rels/document.xml.rels" to """<Relationships><Relationship Id="rId1" Target="media/image1.png"/>
                <Relationship Id="rId2" Target="https://example.invalid/a.png" TargetMode="External"/></Relationships>""".toByteArray(),
            "word/media/image1.png" to image,
        )))
        val html = parsed.htmlPreview.orEmpty()
        assertTrue(html.contains("class=\"heading\""))
        assertTrue(html.contains("<b>Guía &amp; fotos</b>"))
        assertTrue(html.contains("Guía &amp; fotos"))
        assertTrue(html.contains("data:image/png;base64,"))
        assertFalse(html.contains("https://example.invalid"))
    }

    @Test fun wordEditorChangesTextAndStylesInSeparateCopyAndKeepsComplexParagraphsAndMedia() {
        val original = java.io.File.createTempFile("word-original-", ".docx")
        val edited = java.io.File.createTempFile("word-edited-", ".docx")
        val documentXml = """<w:document xmlns:w="urn:word"><w:body>
            <w:p><w:pPr><w:keepNext/></w:pPr><w:r><w:t>Hola mundo</w:t></w:r></w:p>
            <w:p><w:r><w:rPr><w:color w:val="FF0000"/></w:rPr><w:t>Color</w:t></w:r><w:r><w:t> fuerte</w:t></w:r></w:p>
            <w:p><w:r><w:drawing/></w:r><w:r><w:t>Texto asociado a imagen</w:t></w:r></w:p>
            </w:body></w:document>""".trimIndent().toByteArray()
        val imageBytes = byteArrayOf(1, 2, 3, 4)
        val inputArchive = archiveBytes(listOf(
            "[Content_Types].xml" to "<Types/>".toByteArray(),
            "word/document.xml" to documentXml,
            "word/media/image1.png" to imageBytes,
        )).readBytes()
        original.writeBytes(inputArchive)
        try {
            val before = DocumentContentParser.parseOffice(ByteArrayInputStream(inputArchive))
            assertEquals("Hola mundo", before.wordParagraphs[0].text)
            assertTrue(before.wordParagraphs[0].editable)
            assertTrue(before.wordParagraphs[0].textEditable)
            assertTrue(before.wordParagraphs[1].editable)
            assertFalse(before.wordParagraphs[1].textEditable)
            assertFalse(before.wordParagraphs[2].editable)
            val edits = before.wordParagraphs.mapIndexed { index, paragraph ->
                if (index == 0) WordParagraphEdit("Hola universo", bold = true, italic = true)
                else if (index == 1) WordParagraphEdit(paragraph.text, bold = true)
                else WordParagraphEdit(paragraph.text)
            }
            WordDocumentEditor.writeEditedCopy(original, edited, edits)
            val afterBytes = edited.readBytes()
            val after = DocumentContentParser.parseOffice(ByteArrayInputStream(afterBytes))
            assertEquals("Hola universo", after.wordParagraphs[0].text)
            assertEquals(true, after.wordParagraphs[0].bold)
            assertEquals(true, after.wordParagraphs[0].italic)
            assertEquals("Color fuerte", after.wordParagraphs[1].text)
            assertEquals(true, after.wordParagraphs[1].bold)
            assertEquals("Texto asociado a imagen", after.wordParagraphs[2].text)
            assertTrue(after.htmlPreview.orEmpty().contains("<b><i>Hola universo</i></b>"))
            ZipInputStream(ByteArrayInputStream(afterBytes)).use { zip ->
                val media = generateSequence { zip.nextEntry }.firstOrNull { it.name == "word/media/image1.png" }
                assertNotNull("La copia DOCX conserva los recursos de imagen", media)
                assertArrayEquals(imageBytes, zip.readBytes())
            }
            assertArrayEquals("El archivo original no debe cambiar", inputArchive, original.readBytes())
        } finally {
            original.delete()
            edited.delete()
        }
    }

    @Test fun wordEditorRejectsChangesToUnsupportedParagraphsAndPathTraversal() {
        val source = java.io.File.createTempFile("word-unsafe-", ".docx")
        val output = java.io.File.createTempFile("word-unsafe-copy-", ".docx")
        source.writeBytes(archiveBytes(listOf("word/document.xml" to
            """<w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:drawing/></w:r><w:r><w:t>Texto</w:t></w:r></w:p></w:body></w:document>""".toByteArray())).readBytes())
        try {
            assertThrows(IllegalArgumentException::class.java) {
                WordDocumentEditor.writeEditedCopy(source, output, listOf(WordParagraphEdit("Cambiado")))
            }
            source.writeBytes(archiveBytes(listOf("../word/document.xml" to "<doc/>".toByteArray())).readBytes())
            assertThrows(IllegalArgumentException::class.java) {
                WordDocumentEditor.writeEditedCopy(source, output, listOf(WordParagraphEdit("Texto")))
            }
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test fun wordTextBoxParagraphDoesNotShiftTheIndicesOfEditableBodyParagraphs() {
        val source = java.io.File.createTempFile("word-textbox-", ".docx")
        val output = java.io.File.createTempFile("word-textbox-copy-", ".docx")
        source.writeBytes(archiveBytes(listOf("word/document.xml" to """
            <w:document xmlns:w="urn:word"><w:body>
              <w:p><w:r><w:t>Diagrama</w:t><w:drawing><w:txbxContent><w:p><w:r><w:t>Texto de forma</w:t></w:r></w:p></w:txbxContent></w:drawing></w:r></w:p>
              <w:p><w:r><w:t>Segundo párrafo</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent().toByteArray())).readBytes())
        try {
            val before = source.inputStream().use { DocumentContentParser.parseOffice(it) }
            assertEquals(2, before.wordParagraphs.size)
            assertFalse(before.wordParagraphs[0].editable)
            assertEquals("Segundo párrafo", before.wordParagraphs[1].text)
            val edits = listOf(WordParagraphEdit(before.wordParagraphs[0].text), WordParagraphEdit("Párrafo editado"))
            WordDocumentEditor.writeEditedCopy(source, output, edits)
            val after = output.inputStream().use { DocumentContentParser.parseOffice(it) }
            assertEquals("Párrafo editado", after.wordParagraphs[1].text)
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test fun spreadsheetResolvesSharedStringsSheetNamesGapsAndCachedFormulaValues() {
        val parsed = parse(
            "xl/workbook.xml" to """<workbook xmlns:r="urn:rels"><sheets><sheet name="Ventas" r:id="sheet-id"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="sheet-id" Target="worksheets/sheet2.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to """<sst><si><t>Producto</t></si><si><r><t>Café</t></r></si></sst>""",
            "xl/worksheets/sheet2.xml" to """<worksheet><sheetData><row><c r="A1" t="s"><v>0</v></c><c r="C1"><v>20</v></c></row><row><c r="A2" t="s"><v>1</v></c><c r="B2"><f>1+2</f><v>3</v></c></row></sheetData></worksheet>"""
        )
        assertEquals("Ventas", parsed.sections.single().title)
        assertEquals("Producto\t\t20\nCafé\t3", parsed.sections.single().text)
        val html = parsed.htmlPreview.orEmpty()
        assertTrue(html.contains("<h2>Ventas</h2>"))
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<td>Producto</td>"))
        assertTrue(html.contains("<td>20</td>"))
    }

    @Test fun presentationSlidesSortNumericallyRatherThanLexicographically() {
        val parsed = parse("ppt/slides/slide10.xml" to "<sld><p><t>Diez</t></p></sld>",
            "ppt/slides/slide2.xml" to "<sld><p><t>Dos</t></p></sld>")
        assertEquals(listOf("Dos", "Diez"), parsed.sections.map { it.text })
        val html = parsed.htmlPreview.orEmpty()
        assertTrue(html.contains("<section class=\"slide\"><h2>Diapositiva 1</h2><p>Dos</p>"))
        assertTrue(html.indexOf("<p>Dos</p>") < html.indexOf("<p>Diez</p>"))
    }

    @Test fun presentationPreviewIncludesEmbeddedRasterImagesButIgnoresExternalRelationships() {
        val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2)
        val parsed = DocumentContentParser.parseOffice(archiveBytes(listOf(
            "ppt/slides/slide1.xml" to """<p:sld xmlns:p="urn:p" xmlns:a="urn:a" xmlns:r="urn:r">
                <p:sp><a:t>Gráfico &amp; datos</a:t></p:sp><p:pic><a:blip r:embed="rId1"/></p:pic>
                <p:pic><a:blip r:embed="rId2"/></p:pic></p:sld>""".toByteArray(),
            "ppt/slides/_rels/slide1.xml.rels" to """<Relationships>
                <Relationship Id="rId1" Target="../media/image1.png"/>
                <Relationship Id="rId2" Target="https://example.invalid/image.png" TargetMode="External"/>
                </Relationships>""".toByteArray(),
            "ppt/media/image1.png" to png,
        )))
        val html = parsed.htmlPreview.orEmpty()
        assertTrue(html.contains("Gráfico &amp; datos"))
        assertTrue(html.contains("alt=\"Imagen de la diapositiva\""))
        assertTrue(html.contains("data:image/png;base64,"))
        assertFalse(html.contains("example.invalid"))
    }

    @Test fun doctypeAndEntityDeclarationsAreRejectedBeforeXmlParsing() {
        val payload = """<!DOCTYPE doc [<!ENTITY secret SYSTEM "file:///private/data">]><doc><t>&secret;</t></doc>"""
        assertThrows(IllegalArgumentException::class.java) { parse("word/document.xml" to payload) }
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + payload.toByteArray(Charsets.UTF_16LE)
        assertThrows(IllegalArgumentException::class.java) {
            DocumentContentParser.parseOffice(archiveBytes(listOf("word/document.xml" to utf16)))
        }
    }

    @Test fun compressedBombsPathTraversalAndExcessiveEntriesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DocumentContentParser.parseOffice(archiveBytes(listOf("word/document.xml" to ByteArray(DocumentLimits.XML_BYTES + 1) { 65 })))
        }
        assertThrows(IllegalArgumentException::class.java) { parse("../word/document.xml" to "<doc/>") }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentContentParser.parseOffice(archiveBytes((0..DocumentLimits.ZIP_ENTRIES).map { "part$it" to byteArrayOf() }))
        }
    }

    @Test fun deepXmlAndSharedStringExpansionAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("word/document.xml" to "<doc>".repeat(65) + "</doc>".repeat(65))
        }
        val repeated = "x".repeat(100_001)
        assertThrows(IllegalArgumentException::class.java) {
            parse("xl/workbook.xml" to "<workbook><sheet name=\"Test\"/></workbook>",
                "xl/sharedStrings.xml" to "<sst><si><t>$repeated</t></si></sst>",
                "xl/worksheets/sheet1.xml" to "<worksheet><row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>0</v></c></row></worksheet>")
        }
    }

    @Test fun cancellationIsObservedWhileProcessingArchive() {
        val input = archiveBytes(listOf("word/document.xml" to "<doc/>".toByteArray()))
        assertThrows(CancellationException::class.java) {
            DocumentContentParser.parseOffice(input) { throw CancellationException("closed") }
        }
    }

    @Test fun textSupportsUtf8AndBomUtf16WithoutSilentlyDecodingBinaryOrOversizedInput() {
        assertEquals("Hola, José", DocumentContentParser.decodeText("Hola, José".toByteArray()))
        assertEquals("Hola", DocumentContentParser.decodeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hola".toByteArray(Charsets.UTF_16LE)))
        assertThrows(IllegalArgumentException::class.java) { DocumentContentParser.decodeText(byteArrayOf(1, 0, 2)) }
        assertThrows(IllegalArgumentException::class.java) { DocumentContentParser.decodeText(ByteArray(DocumentLimits.TEXT_BYTES + 1)) }
    }

    private fun parse(vararg parts: Pair<String, String>) = DocumentContentParser.parseOffice(
        archiveBytes(parts.map { it.first to it.second.toByteArray(Charsets.UTF_8) })
    )

    private fun archiveBytes(parts: List<Pair<String, ByteArray>>): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            parts.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content); zip.closeEntry()
            }
        }
        return ByteArrayInputStream(output.toByteArray())
    }
}
