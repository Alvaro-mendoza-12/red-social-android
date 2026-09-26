package conectate.com.presentation.documents

import java.util.Base64
import org.xml.sax.Attributes

/** Bounded, offline DOCX preview. It preserves common runs, headings, tables and embedded raster images. */
internal object WordPreviewHtml {
    fun render(parts: Map<String, ByteArray>, cancel: () -> Unit = {}): String {
        val document = parts["word/document.xml"] ?: return ""
        val relationships = mutableMapOf<String, String>()
        parts["word/_rels/document.xml.rels"]?.let { bytes ->
            DocumentContentParser.parseXml(bytes, cancel, start = { tag, attrs ->
                if (tag == "Relationship" && attrs.getValue("TargetMode") != "External") {
                    val target = attrs.getValue("Target").orEmpty().removePrefix("/").removePrefix("word/")
                    if (target.matches(Regex("media/[A-Za-z0-9_.-]+\\.(png|jpg|jpeg|gif|webp)", RegexOption.IGNORE_CASE))) {
                        relationships[attrs.getValue("Id").orEmpty()] = "word/$target"
                    }
                }
            })
        }
        val images = relationships.mapNotNull { (id, path) ->
            val bytes = parts[path] ?: return@mapNotNull null
            val mime = imageMime(bytes) ?: return@mapNotNull null
            if (bytes.size > 4 * 1024 * 1024) return@mapNotNull null
            id to "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
        }.toMap()
        val body = StringBuilder()
        var paragraph = StringBuilder()
        var run = StringBuilder()
        var inText = false
        var bold = false
        var italic = false
        var underline = false
        var heading = false
        var sizePt: Int? = null
        var color: String? = null
        var imageCount = 0

        fun flushRun() {
            if (run.isEmpty()) return
            val style = buildString {
                sizePt?.let { append("font-size:${it}pt;") }
                color?.let { append("color:#$it;") }
            }
            if (style.isNotEmpty()) paragraph.append("<span style=\"").append(style).append("\">")
            if (bold) paragraph.append("<b>")
            if (italic) paragraph.append("<i>")
            if (underline) paragraph.append("<u>")
            paragraph.append(run)
            if (underline) paragraph.append("</u>")
            if (italic) paragraph.append("</i>")
            if (bold) paragraph.append("</b>")
            if (style.isNotEmpty()) paragraph.append("</span>")
            run = StringBuilder()
        }

        DocumentContentParser.parseXml(document, cancel, start = { tag: String, attrs: Attributes ->
            when (tag) {
                "tbl" -> body.append("<table>")
                "tr" -> body.append("<tr>")
                "tc" -> body.append("<td>")
                "p" -> { paragraph = StringBuilder(); heading = false }
                "pStyle" -> heading = attribute(attrs, "val").orEmpty().startsWith("Heading", true) ||
                    attribute(attrs, "val").orEmpty().startsWith("Title", true)
                "r" -> { run = StringBuilder(); bold = false; italic = false; underline = false; sizePt = null; color = null }
                "b" -> bold = attribute(attrs, "val") != "0" && attribute(attrs, "val") != "false"
                "i" -> italic = attribute(attrs, "val") != "0" && attribute(attrs, "val") != "false"
                "u" -> underline = attribute(attrs, "val") != "none"
                "sz" -> sizePt = attribute(attrs, "val")?.toIntOrNull()?.div(2)?.coerceIn(6, 48)
                "color" -> color = attribute(attrs, "val")?.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }
                "t" -> inText = true
                "tab" -> run.append("&#9;")
                "br" -> run.append("<br>")
                "blip" -> {
                    val id = attribute(attrs, "embed")
                    val image = images[id]
                    if (image != null && ++imageCount <= 20 && body.length + paragraph.length + run.length + image.length < 12_000_000) {
                        run.append("<img alt=\"Imagen del documento\" src=\"").append(image).append("\">")
                    }
                }
            }
        }, characters = { if (inText) run.append(escape(it)) }, end = { tag ->
            when (tag) {
                "t" -> inText = false
                "r" -> flushRun()
                "p" -> {
                    body.append(if (heading) "<h2 class=\"heading\">" else "<p>")
                        .append(paragraph).append(if (heading) "</h2>" else "</p>")
                    require(body.length <= 12_000_000) { "La vista visual del documento es demasiado grande." }
                }
                "tc" -> body.append("</td>")
                "tr" -> body.append("</tr>")
                "tbl" -> body.append("</table>")
            }
        })
        return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:sans-serif;color:#242424;background:#fff;margin:16px;line-height:1.45}
            p{white-space:pre-wrap;margin:0 0 12px}.heading{font-size:1.35em;font-weight:bold;margin-top:18px}
            table{border-collapse:collapse;max-width:100%;overflow:auto;display:block;margin:12px 0}
            td{border:1px solid #aaa;vertical-align:top;padding:6px}img{max-width:100%;height:auto}</style></head><body>$body</body></html>"""
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char -> append(when (char) {
            '&' -> "&amp;"
            '<' -> "&lt;"
            '>' -> "&gt;"
            '"' -> "&quot;"
            '\'' -> "&#39;"
            else -> char.toString()
        }) }
    }

    private fun attribute(attributes: Attributes, localName: String): String? =
        (0 until attributes.length).firstOrNull { index ->
            attributes.getLocalName(index) == localName || attributes.getQName(index).substringAfter(':') == localName
        }?.let(attributes::getValue)

    private fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 6 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF" -> "image/gif"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}
