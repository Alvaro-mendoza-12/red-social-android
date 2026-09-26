package conectate.com.presentation.documents

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal object DocumentLimits {
    const val INPUT_BYTES = 32L * 1024 * 1024
    const val TEXT_BYTES = 512 * 1024
    const val TEXT_CHARACTERS = 200_000
    const val ZIP_ENTRIES = 512
    const val ZIP_EXPANDED_BYTES = 16L * 1024 * 1024
    const val XML_BYTES = 4 * 1024 * 1024
    const val XML_ELEMENTS = 100_000
    const val SECTIONS = 100
}

internal data class DocumentSection(val title: String, val text: String)
internal data class WordParagraph(
    val text: String,
    val editable: Boolean,
    val textEditable: Boolean,
    /** Null means the paragraph contains mixed formatting. */
    val bold: Boolean?,
    val italic: Boolean?,
)
internal data class WordParagraphEdit(
    val text: String,
    /** Null preserves the source run formatting. */
    val bold: Boolean? = null,
    val italic: Boolean? = null,
)
internal data class ParsedOfficeDocument(
    val format: String,
    val sections: List<DocumentSection>,
    val htmlPreview: String? = null,
    val wordParagraphs: List<WordParagraph> = emptyList(),
) {
    fun plainText(): String = sections.joinToString("\n\n") { "${it.title}\n${it.text}" }
}

/** Text-only OOXML interpretation. Never extracts files, executes formulas, macros or external relationships. */
internal object DocumentContentParser {
    fun decodeText(bytes: ByteArray): String {
        require(bytes.size <= DocumentLimits.TEXT_BYTES) { "El texto supera el límite de lectura de 512 KB." }
        return decodeXmlOrText(bytes).also {
            require(it.length <= DocumentLimits.TEXT_CHARACTERS) { "El texto supera los 200.000 caracteres." }
            require(!it.contains('\u0000')) { "Este archivo contiene datos binarios; no se puede editar como texto." }
        }.removePrefix("\uFEFF")
    }

    fun parseOffice(input: InputStream, checkCancelled: () -> Unit = {}): ParsedOfficeDocument {
        val xml = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                require(++entries <= DocumentLimits.ZIP_ENTRIES) { "El documento contiene demasiadas partes." }
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && name.split('/').none { it == ".." }) { "Ruta no válida dentro del documento." }
                val keep = name == "word/document.xml" || name == "word/_rels/document.xml.rels" ||
                    name.matches(Regex("word/media/[A-Za-z0-9_.-]+\\.(png|jpg|jpeg|gif|webp)", RegexOption.IGNORE_CASE)) ||
                    name.matches(Regex("ppt/media/[A-Za-z0-9_.-]+\\.(png|jpg|jpeg|gif|webp)", RegexOption.IGNORE_CASE)) ||
                    name == "xl/sharedStrings.xml" ||
                    name == "xl/workbook.xml" || name == "xl/_rels/workbook.xml.rels" ||
                    name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml")) ||
                    name.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) ||
                    name.matches(Regex("ppt/slides/_rels/slide[0-9]+\\.xml\\.rels"))
                val output = if (keep) ByteArrayOutputStream() else null
                var entryBytes = 0L
                val buffer = ByteArray(8_192)
                while (true) {
                    checkCancelled()
                    val read = zip.read(buffer)
                    if (read < 0) break
                    expanded += read
                    entryBytes += read
                    require(expanded <= DocumentLimits.ZIP_EXPANDED_BYTES) { "El documento descomprimido supera 16 MB." }
                    if (keep) {
                        require(entryBytes <= DocumentLimits.XML_BYTES) { "Una parte del documento supera 4 MB." }
                        output!!.write(buffer, 0, read)
                    }
                }
                if (keep) {
                    require(name !in xml) { "El documento contiene partes duplicadas." }
                    xml[name] = output!!.toByteArray()
                }
                zip.closeEntry()
            }
        }
        var presentationCharacters = 0
        val result = when {
            "word/document.xml" in xml -> {
                val paragraphs = WordDocumentEditor.readParagraphs(xml.getValue("word/document.xml"), checkCancelled)
                ParsedOfficeDocument("Word · vista previa", listOf(
                    DocumentSection("Documento", paragraphText(xml.getValue("word/document.xml"), checkCancelled))
                ), WordPreviewHtml.render(xml, checkCancelled), paragraphs)
            }
            "xl/workbook.xml" in xml -> readWorkbook(xml, checkCancelled).let { document ->
                document.copy(htmlPreview = SpreadsheetPreviewHtml.render(document.sections, checkCancelled))
            }
            xml.keys.any { it.startsWith("ppt/slides/slide") } -> {
                val slides = xml.keys.filter { it.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) }
                    .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: 0 }
                    .also { require(it.size <= DocumentLimits.SECTIONS) { "Hay más de 100 diapositivas." } }
                    .mapIndexed { index, path ->
                        val text = paragraphText(xml.getValue(path), checkCancelled)
                        presentationCharacters += text.length
                        require(presentationCharacters <= DocumentLimits.TEXT_CHARACTERS) { "Las diapositivas superan los 200.000 caracteres." }
                        DocumentSection("Diapositiva ${index + 1}", text)
                    }
                ParsedOfficeDocument("PowerPoint · lectura", slides,
                    PresentationPreviewHtml.render(xml, slides, checkCancelled))
            }
            else -> throw IllegalArgumentException("Este archivo ZIP no es un documento Word, Excel o PowerPoint compatible.")
        }
        require(result.sections.sumOf { it.text.length } <= DocumentLimits.TEXT_CHARACTERS) { "El contenido supera los 200.000 caracteres." }
        return result
    }

    private fun paragraphText(bytes: ByteArray, cancel: () -> Unit): String {
        val text = StringBuilder()
        var inText = false
        parseXml(bytes, cancel, start = { tag, _ ->
            if (tag == "t") inText = true
            if (tag == "tab") text.append('\t')
            if (tag == "br") text.append('\n')
        }, characters = { if (inText) text.append(it) }, end = { tag ->
            when (tag) {
                "t" -> inText = false
                "p", "tr" -> text.append('\n')
                "tc" -> text.append('\t')
            }
            require(text.length <= DocumentLimits.TEXT_CHARACTERS) { "El contenido supera los 200.000 caracteres." }
        })
        return text.toString().trim()
    }

    private fun readWorkbook(xml: Map<String, ByteArray>, cancel: () -> Unit): ParsedOfficeDocument {
        val strings = mutableListOf<String>()
        var shared = StringBuilder()
        var inText = false
        var totalText = 0
        xml["xl/sharedStrings.xml"]?.let { bytes ->
            parseXml(bytes, cancel, start = { tag, _ ->
                if (tag == "si") shared = StringBuilder()
                if (tag == "t") inText = true
            }, characters = { if (inText) shared.append(it) }, end = { tag ->
                if (tag == "t") inText = false
                if (tag == "si") {
                    totalText += shared.length
                    require(strings.size < 20_000 && totalText <= DocumentLimits.TEXT_CHARACTERS) { "La tabla de textos supera el límite de lectura." }
                    strings += shared.toString()
                }
            })
        }
        val sheets = mutableListOf<Pair<String, String>>()
        parseXml(xml.getValue("xl/workbook.xml"), cancel, start = { tag, attributes ->
            if (tag == "sheet") {
                require(sheets.size < DocumentLimits.SECTIONS) { "Hay más de 100 hojas." }
                sheets += attributes.getValue("name").orEmpty() to (attributes.getValue("r:id") ?: attributes.getValue("id").orEmpty())
            }
        })
        val targets = mutableMapOf<String, String>()
        xml["xl/_rels/workbook.xml.rels"]?.let { bytes ->
            parseXml(bytes, cancel, start = { tag, attributes ->
                if (tag == "Relationship" && attributes.getValue("TargetMode") != "External") {
                    val target = attributes.getValue("Target").orEmpty().removePrefix("/").removePrefix("xl/")
                    if (target.matches(Regex("worksheets/sheet[0-9]+\\.xml"))) {
                        targets[attributes.getValue("Id").orEmpty()] = "xl/$target"
                    }
                }
            })
        }
        var workbookCharacters = 0
        return ParsedOfficeDocument("Excel · lectura", sheets.mapIndexed { index, (name, relationship) ->
            val path = targets[relationship] ?: "xl/worksheets/sheet${index + 1}.xml"
            val bytes = xml[path] ?: return@mapIndexed DocumentSection(name.ifBlank { "Hoja ${index + 1}" }, "Hoja no disponible en este archivo.")
            val text = readSheet(bytes, strings, cancel)
            workbookCharacters += text.length
            require(workbookCharacters <= DocumentLimits.TEXT_CHARACTERS) { "Las hojas superan los 200.000 caracteres." }
            DocumentSection(name.ifBlank { "Hoja ${index + 1}" }, text)
        })
    }

    private fun readSheet(bytes: ByteArray, strings: List<String>, cancel: () -> Unit): String {
        val result = StringBuilder()
        val row = mutableListOf<String>()
        var cellType = ""
        var column = 0
        var capture = false
        var value = StringBuilder()
        var formula = StringBuilder()
        var inFormula = false
        var cells = 0
        parseXml(bytes, cancel, start = { tag, attributes ->
            when (tag) {
                "row" -> row.clear()
                "c" -> {
                    require(++cells <= 30_000) { "La hoja supera las 30.000 celdas." }
                    cellType = attributes.getValue("t").orEmpty()
                    column = columnIndex(attributes.getValue("r").orEmpty()).coerceAtMost(255)
                    value = StringBuilder()
                    formula = StringBuilder()
                }
                "v", "t" -> capture = true
                "f" -> inFormula = true
            }
        }, characters = {
            if (capture) value.append(it)
            if (inFormula) formula.append(it)
        }, end = { tag ->
            when (tag) {
                "v", "t" -> capture = false
                "f" -> inFormula = false
                "c" -> {
                    while (row.size <= column) row += ""
                    val cellText = when (cellType) {
                        "s" -> value.toString().toIntOrNull()?.let { strings.getOrNull(it) }.orEmpty()
                        "b" -> if (value.toString() == "1") "Sí" else "No"
                        else -> value.toString().ifEmpty { if (formula.isNotEmpty()) "=$formula" else "" }
                    }
                    require(result.length + row.sumOf { it.length } + cellText.length <= DocumentLimits.TEXT_CHARACTERS) {
                        "La hoja supera los 200.000 caracteres."
                    }
                    row[column] = cellText
                }
                "row" -> {
                    result.append(row.joinToString("\t")).append('\n')
                    require(result.length <= DocumentLimits.TEXT_CHARACTERS) { "La hoja supera los 200.000 caracteres." }
                }
            }
        })
        return result.toString().trimEnd()
    }

    private fun columnIndex(address: String): Int {
        var value = 0
        for (char in address.takeWhile(Char::isLetter)) {
            value = value * 26 + (char.uppercaseChar() - 'A' + 1)
            if (value > 256) throw IllegalArgumentException("La hoja supera las 256 columnas de lectura.")
        }
        return (value - 1).coerceAtLeast(0)
    }

    internal fun parseXml(
        bytes: ByteArray,
        cancel: () -> Unit,
        start: (String, Attributes) -> Unit = { _, _ -> },
        characters: (String) -> Unit = {},
        end: (String) -> Unit = {},
        namespacedStart: ((String, String, Attributes) -> Unit)? = null,
        namespacedEnd: ((String, String) -> Unit)? = null,
    ) {
        val text = decodeXmlOrText(bytes)
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "No se admiten DTD ni entidades en documentos." }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val reader = factory.newSAXParser().xmlReader
        listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")
            .forEach { feature -> runCatching { reader.setFeature(feature, false) } }
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External entities are disabled") }
        var depth = 0
        var elements = 0
        reader.contentHandler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                cancel()
                require(++depth <= 64 && ++elements <= DocumentLimits.XML_ELEMENTS) { "Estructura XML demasiado compleja." }
                val local = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
                start(local, attributes)
                namespacedStart?.invoke(uri.orEmpty(), local, attributes)
            }
            override fun characters(ch: CharArray, offset: Int, length: Int) { cancel(); characters(String(ch, offset, length)) }
            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val local = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')
                end(local)
                namespacedEnd?.invoke(uri.orEmpty(), local)
                depth--
            }
        }
        reader.parse(InputSource(StringReader(text)))
    }

    private fun decodeXmlOrText(bytes: ByteArray): String {
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    }
}
