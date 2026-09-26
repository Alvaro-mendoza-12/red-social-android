package conectate.com.presentation.documents

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.XMLReader
import org.xml.sax.helpers.AttributesImpl
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLFilterImpl
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.SAXParserFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.sax.SAXTransformerFactory
import javax.xml.transform.stream.StreamResult

/** Safe subset of DOCX editing. Complex paragraphs stay intact and read-only. */
internal object WordDocumentEditor {
    private const val MAX_PARAGRAPHS = 1_000
    private const val WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    private val nonEditableElements = setOf(
        "tab", "br", "cr", "drawing", "pict", "object", "hyperlink", "fldChar", "instrText",
        "delText", "del", "ins", "moveFrom", "moveTo", "sdt", "subDoc", "footnoteReference",
        "endnoteReference", "commentReference", "bookmarkStart", "bookmarkEnd", "altChunk", "sym",
    )

    private data class ParagraphBuilder(
        val text: StringBuilder = StringBuilder(),
        val runBold: MutableList<Boolean> = mutableListOf(),
        val runItalic: MutableList<Boolean> = mutableListOf(),
        var hasTextNode: Boolean = false,
        var hasTextOutsideRun: Boolean = false,
        var supported: Boolean = true,
    )

    fun readParagraphs(xml: ByteArray, cancel: () -> Unit): List<WordParagraph> {
        val paragraphs = mutableListOf<WordParagraph>()
        var current: ParagraphBuilder? = null
        var runText = StringBuilder()
        var runBold = false
        var runItalic = false
        var runDepth = 0
        var inText = false
        var rootSeen = false
        var wordNamespace = ""
        var paragraphDepth = 0
        DocumentContentParser.parseXml(xml, cancel, namespacedStart = paragraphStart@{ uri, tag, attrs ->
            if (tag == "document" && !rootSeen) {
                rootSeen = true
                wordNamespace = uri
            }
            if (!rootSeen || uri != wordNamespace) return@paragraphStart
            when (tag) {
                "p" -> {
                    if (current == null) {
                        require(paragraphs.size < MAX_PARAGRAPHS) { "El documento tiene demasiados párrafos para editar." }
                        current = ParagraphBuilder()
                    } else current!!.supported = false
                    paragraphDepth++
                }
                "r" -> if (current != null) {
                    runDepth++
                    runText = StringBuilder()
                    runBold = false
                    runItalic = false
                }
                "b" -> if (current != null) runBold = enabled(attrs)
                "i" -> if (current != null) runItalic = enabled(attrs)
                "t" -> if (current != null) {
                    current!!.hasTextNode = true
                    inText = true
                    if (runDepth == 0) current!!.hasTextOutsideRun = true
                }
                in nonEditableElements -> if (current != null) current!!.supported = false
            }
        }, characters = { value ->
            val paragraph = current ?: return@parseXml
            if (inText) {
                paragraph.text.append(value)
                runText.append(value)
                require(paragraph.text.length <= DocumentLimits.TEXT_CHARACTERS) { "El párrafo supera el límite de texto." }
            }
        }, namespacedEnd = paragraphEnd@{ uri, tag ->
            if (!rootSeen || uri != wordNamespace) return@paragraphEnd
            when (tag) {
                "t" -> inText = false
                "r" -> if (current != null) {
                    if (runText.isNotEmpty()) {
                        current!!.runBold += runBold
                        current!!.runItalic += runItalic
                    }
                    runText = StringBuilder()
                    runDepth = (runDepth - 1).coerceAtLeast(0)
                }
                "p" -> if (paragraphDepth > 0) {
                    paragraphDepth--
                    if (paragraphDepth == 0) current?.let { paragraph ->
                        val bold = uniform(paragraph.runBold)
                        val italic = uniform(paragraph.runItalic)
                        paragraphs += WordParagraph(
                        text = paragraph.text.toString(),
                        editable = paragraph.hasTextNode && !paragraph.hasTextOutsideRun && paragraph.supported,
                        textEditable = paragraph.hasTextNode && !paragraph.hasTextOutsideRun && paragraph.supported && paragraph.runBold.size <= 1,
                        bold = bold,
                            italic = italic,
                        )
                        current = null
                    }
                }
            }
        })
        return paragraphs
    }

    private fun enabled(attrs: Attributes): Boolean {
        val value = (0 until attrs.length).firstOrNull { attrs.getLocalName(it) == "val" || attrs.getQName(it).substringAfter(':') == "val" }
            ?.let(attrs::getValue)
        return value !in setOf("0", "false", "off", "none")
    }

    private fun uniform(values: List<Boolean>): Boolean? = when {
        values.isEmpty() -> false
        values.all { it } -> true
        values.none { it } -> false
        else -> null
    }

    suspend fun createEditedCopy(context: Context, source: File, sourceName: String, edits: List<WordParagraphEdit>): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "document-copies").apply { mkdirs() }
            directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1_000 }?.forEach(File::delete)
            val base = safeDocumentName(sourceName).substringBeforeLast('.').take(80)
            val output = File(directory, "$base-editado-${UUID.randomUUID().toString().take(8)}.docx")
            try {
                writeEditedCopy(source, output, edits)
                output
            } catch (failure: Throwable) {
                output.delete()
                throw failure
            }
        }

    /** Writes a bounded copy, changes only word/document.xml, then reparses it before returning. */
    internal fun writeEditedCopy(source: File, output: File, edits: List<WordParagraphEdit>) {
        require(source.isFile && source.length() <= DocumentLimits.INPUT_BYTES) { "El documento original ya no está disponible o supera 32 MB." }
        val originalXml = source.inputStream().use { zip ->
            ZipInputStream(zip).use { archive ->
                var found: ByteArray? = null
                var entryCount = 0
                var expanded = 0L
                val names = mutableSetOf<String>()
                while (true) {
                    val entry = archive.nextEntry ?: break
                    require(++entryCount <= DocumentLimits.ZIP_ENTRIES) { "El documento contiene demasiadas partes." }
                    val path = safeZipPath(entry.name)
                    require(names.add(path)) { "El documento contiene partes duplicadas." }
                    val bytes = readCurrentEntry(archive, if (path == "word/document.xml") DocumentLimits.XML_BYTES.toLong() else DocumentLimits.ZIP_EXPANDED_BYTES, DocumentLimits.ZIP_EXPANDED_BYTES - expanded)
                    expanded += bytes.size
                    require(expanded <= DocumentLimits.ZIP_EXPANDED_BYTES) { "El documento descomprimido supera 16 MB." }
                    if (path == "word/document.xml") found = bytes
                    archive.closeEntry()
                }
                found ?: error("El archivo no contiene un documento Word editable.")
            }
        }
        val paragraphs = readParagraphs(originalXml) {}
        require(paragraphs.size == edits.size) { "El borrador no coincide con este documento. Vuelve a abrirlo e inténtalo de nuevo." }
        edits.forEachIndexed { index, edit ->
            require(edit.text.length <= DocumentLimits.TEXT_CHARACTERS && '\n' !in edit.text && '\r' !in edit.text) {
                "Cada párrafo debe mantenerse en una sola línea y dentro del límite de lectura."
            }
            if (edit.text != paragraphs[index].text) require(paragraphs[index].textEditable) {
                "El párrafo ${index + 1} tiene varios segmentos con formato y se conserva intacto."
            }
            if (edit.bold != null || edit.italic != null) require(paragraphs[index].editable) {
                "El párrafo ${index + 1} contiene elementos avanzados y se conserva intacto."
            }
        }

        var expanded = 0L
        var entryCount = 0
        var foundDocument = false
        val names = mutableSetOf<String>()
        source.inputStream().buffered().use { input ->
            ZipInputStream(input).use { archive ->
                output.outputStream().buffered().use { fileOut ->
                    ZipOutputStream(fileOut).use { destination ->
                        while (true) {
                            val entry = archive.nextEntry ?: break
                            require(++entryCount <= DocumentLimits.ZIP_ENTRIES) { "El documento contiene demasiadas partes." }
                            val path = safeZipPath(entry.name)
                            require(names.add(path)) { "El documento contiene partes duplicadas." }
                            val bytes = readCurrentEntry(archive, if (path == "word/document.xml") DocumentLimits.XML_BYTES.toLong() else DocumentLimits.ZIP_EXPANDED_BYTES, DocumentLimits.ZIP_EXPANDED_BYTES - expanded)
                            expanded += bytes.size
                            require(expanded <= DocumentLimits.ZIP_EXPANDED_BYTES) { "El documento descomprimido supera 16 MB." }
                            val copy = ZipEntry(entry.name).apply {
                                if (entry.time >= 0) time = entry.time
                                entry.comment?.let { comment = it }
                            }
                            destination.putNextEntry(copy)
                            if (path == "word/document.xml") {
                                require(!foundDocument) { "El documento contiene partes duplicadas." }
                                destination.write(rewriteDocumentXml(bytes, paragraphs, edits))
                                foundDocument = true
                            } else if (!entry.isDirectory) destination.write(bytes)
                            destination.closeEntry()
                            archive.closeEntry()
                        }
                    }
                }
            }
        }
        require(foundDocument) { "El archivo no contiene word/document.xml." }
        val verified = output.inputStream().use { DocumentContentParser.parseOffice(it) }
        verified.wordParagraphs.forEachIndexed { index, paragraph ->
            val edit = edits[index]
            if (paragraphs[index].textEditable) require(paragraph.text == edit.text) { "No se pudo verificar el texto del párrafo ${index + 1}." }
            if (edit.bold != null) require(paragraph.bold == edit.bold) { "No se pudo verificar la negrita del párrafo ${index + 1}." }
            if (edit.italic != null) require(paragraph.italic == edit.italic) { "No se pudo verificar la cursiva del párrafo ${index + 1}." }
        }
    }

    private fun safeZipPath(name: String): String {
        val normalized = name.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && '\u0000' !in normalized && normalized.split('/').none { it == ".." }) {
            "Ruta no válida dentro del documento."
        }
        return normalized
    }

    private fun readCurrentEntry(input: ZipInputStream, entryLimit: Long, remainingLimit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= entryLimit && output.size().toLong() + count <= remainingLimit) {
                "El documento descomprimido supera el límite permitido."
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun rewriteDocumentXml(xml: ByteArray, paragraphs: List<WordParagraph>, edits: List<WordParagraphEdit>): ByteArray {
        // Reuse the hardened parser's DTD/entity rejection and structural bounds before rewriting.
        DocumentContentParser.parseXml(xml, {})
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        val reader = factory.newSAXParser().xmlReader
        listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities")
            .forEach { feature -> runCatching { reader.setFeature(feature, false) } }
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> throw SAXException("External entities are disabled") }
        val transformerFactory = SAXTransformerFactory.newInstance() as SAXTransformerFactory
        runCatching { transformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        val transformer = transformerFactory.newTransformerHandler()
        transformer.transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        val output = ByteArrayOutputStream(xml.size + 512)
        transformer.setResult(StreamResult(output))
        val filter = WordEditXmlFilter(reader, paragraphs, edits)
        filter.contentHandler = transformer
        filter.parse(InputSource(ByteArrayInputStream(xml)))
        return output.toByteArray().also { require(it.size <= DocumentLimits.XML_BYTES) { "La parte Word editada supera 4 MB." } }
    }

    private class WordEditXmlFilter(
        parent: XMLReader,
        private val paragraphs: List<WordParagraph>,
        private val edits: List<WordParagraphEdit>,
    ) : XMLFilterImpl(parent) {
        private data class Element(val uri: String, val local: String)
        private data class Run(val paragraph: Int, val edit: WordParagraphEdit, var rPrHandled: Boolean = false)
        private val elements = mutableListOf<Element>()
        private val paragraphStack = mutableListOf<Int>()
        private val runStack = mutableListOf<Run>()
        private val prefixes = mutableMapOf<String, String>()
        private val textReplaced = mutableSetOf<Int>()
        private var nextParagraph = 0
        private var skippedDepth = 0
        private var replacingText = false
        private var wordNamespace = ""
        private var wordPrefix = ""
        private var syntheticPrefixAdded = false
        private var rootDepth = -1

        override fun startPrefixMapping(prefix: String?, uri: String?) {
            val actualPrefix = prefix.orEmpty()
            val actualUri = uri.orEmpty()
            prefixes[actualPrefix] = actualUri
            if (actualUri == WORD_NS) {
                wordNamespace = actualUri
                if (actualPrefix.isNotEmpty()) wordPrefix = actualPrefix
            }
            super.startPrefixMapping(prefix, uri)
        }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val actualUri = uri.orEmpty()
            val actualLocal = localName?.takeIf(String::isNotEmpty) ?: qName.orEmpty().substringAfter(':')
            val actualQName = qName.orEmpty().ifEmpty { actualLocal }
            val parent = elements.lastOrNull()
            if (actualLocal == "document" && wordNamespace.isEmpty()) {
                wordNamespace = actualUri
                wordPrefix = prefixes.entries.firstOrNull { it.value == wordNamespace && it.key.isNotEmpty() }?.key
                    ?: actualQName.substringBefore(':').takeIf { ':' in actualQName }.orEmpty()
            }
            if (wordNamespace.isEmpty() && actualLocal == "p") wordNamespace = actualUri
            if (actualLocal == "p" && isWord(actualUri)) {
                paragraphStack += paragraphStack.lastOrNull() ?: nextParagraph++
            }
            val paragraph = paragraphStack.lastOrNull() ?: -1
            val run = runStack.lastOrNull()

            if (skippedDepth > 0) {
                skippedDepth++
                elements += Element(actualUri, actualLocal)
                return
            }
            if (actualLocal == "document" && !wordNamespace.isNullOrEmpty()) {
                if (wordPrefix.isEmpty()) wordPrefix = "w"
                if (prefixes[wordPrefix] != wordNamespace) {
                    super.startPrefixMapping(wordPrefix, wordNamespace)
                    prefixes[wordPrefix] = wordNamespace
                    syntheticPrefixAdded = true
                }
                rootDepth = elements.size
            }

            if (actualLocal == "r" && isWord(actualUri) && paragraph >= 0) {
                runStack += Run(paragraph, edits.getOrNull(paragraph) ?: WordParagraphEdit(paragraphs[paragraph].text))
            } else if (parent?.local == "r" && isWord(parent.uri)) {
                val active = runStack.lastOrNull()
                if (active != null && hasStyleOverride(active.edit) && !active.rPrHandled) {
                    if (actualLocal == "rPr" && isWord(actualUri)) active.rPrHandled = true
                    else {
                        emitRunProperties(active)
                        active.rPrHandled = true
                    }
                }
            }

            val currentRun = runStack.lastOrNull()
            val formatName = actualLocal in setOf("b", "bCs", "i", "iCs")
            if (formatName && parent?.local == "rPr" && isWord(actualUri) && currentRun != null && overrides(currentRun.edit, actualLocal)) {
                skippedDepth = 1
                elements += Element(actualUri, actualLocal)
                return
            }

            var outputAttributes = attributes ?: AttributesImpl()
            val edit = edits.getOrNull(paragraph)
            val original = paragraphs.getOrNull(paragraph)
            var replaceWith: String? = null
            var suppressText = false
            if (actualLocal == "t" && isWord(actualUri) && edit != null && original != null && edit.text != original.text) {
                if (textReplaced.add(paragraph)) {
                    replaceWith = edit.text
                    if ((edit.text.startsWith(' ') || edit.text.endsWith(' ')) && !hasXmlSpace(outputAttributes)) {
                        outputAttributes = AttributesImpl(outputAttributes).apply {
                            addAttribute(XML_NS, "space", "xml:space", "CDATA", "preserve")
                        }
                    }
                } else suppressText = true
            }
            super.startElement(uri, localName, qName, outputAttributes)
            elements += Element(actualUri, actualLocal)
            if (replaceWith != null) {
                replacingText = true
                val chars = replaceWith.toCharArray()
                contentHandler.characters(chars, 0, chars.size)
            } else if (suppressText) replacingText = true
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (skippedDepth == 0 && !replacingText) super.characters(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val actualUri = uri.orEmpty()
            val actualLocal = localName?.takeIf(String::isNotEmpty) ?: qName.orEmpty().substringAfter(':')
            if (skippedDepth > 0) {
                skippedDepth--
                elements.removeAt(elements.lastIndex)
                if (skippedDepth == 0 && actualLocal == "t") replacingText = false
                return
            }
            if (actualLocal == "t" && isWord(actualUri)) replacingText = false
            if (actualLocal == "rPr" && isWord(actualUri)) runStack.lastOrNull()?.let { run ->
                if (hasStyleOverride(run.edit)) emitStyleOverrides(run.edit)
            }
            if (actualLocal == "r" && isWord(actualUri)) runStack.lastOrNull()?.let { run ->
                if (hasStyleOverride(run.edit) && !run.rPrHandled) emitRunProperties(run)
            }
            super.endElement(uri, localName, qName)
            elements.removeAt(elements.lastIndex)
            if (actualLocal == "r" && isWord(actualUri) && runStack.isNotEmpty()) runStack.removeAt(runStack.lastIndex)
            if (actualLocal == "p" && isWord(actualUri) && paragraphStack.isNotEmpty()) paragraphStack.removeAt(paragraphStack.lastIndex)
            if (rootDepth == elements.size && syntheticPrefixAdded && actualLocal == "document") {
                super.endPrefixMapping(wordPrefix)
                syntheticPrefixAdded = false
            }
        }

        private fun isWord(uri: String): Boolean = wordNamespace.isEmpty() || uri == wordNamespace
        private fun hasStyleOverride(edit: WordParagraphEdit): Boolean = edit.bold != null || edit.italic != null
        private fun overrides(edit: WordParagraphEdit, name: String): Boolean = when (name) {
            "b", "bCs" -> edit.bold != null
            "i", "iCs" -> edit.italic != null
            else -> false
        }

        private fun emitRunProperties(run: Run) {
            val prefix = wordPrefix
            val qName = if (prefix.isEmpty()) "rPr" else "$prefix:rPr"
            contentHandler.startElement(wordNamespace, "rPr", qName, AttributesImpl())
            emitStyleOverrides(run.edit)
            contentHandler.endElement(wordNamespace, "rPr", qName)
        }

        private fun emitStyleOverrides(edit: WordParagraphEdit) {
            edit.bold?.let { value ->
                emitProperty("b", value)
                emitProperty("bCs", value)
            }
            edit.italic?.let { value ->
                emitProperty("i", value)
                emitProperty("iCs", value)
            }
        }

        private fun emitProperty(local: String, enabled: Boolean) {
            val prefix = wordPrefix
            val qName = if (prefix.isEmpty()) local else "$prefix:$local"
            val attributes = AttributesImpl()
            if (!enabled) {
                val valueQName = if (prefix.isEmpty()) "val" else "$prefix:val"
                attributes.addAttribute(wordNamespace, "val", valueQName, "CDATA", "0")
            }
            contentHandler.startElement(wordNamespace, local, qName, attributes)
            contentHandler.endElement(wordNamespace, local, qName)
        }

        private fun hasXmlSpace(attributes: Attributes): Boolean = (0 until attributes.length).any {
            (attributes.getURI(it) == XML_NS && attributes.getLocalName(it) == "space") || attributes.getQName(it) == "xml:space"
        }
    }
}
