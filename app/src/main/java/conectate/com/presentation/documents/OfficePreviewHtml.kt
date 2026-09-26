package conectate.com.presentation.documents

import java.util.Base64
import org.xml.sax.Attributes

/** Offline, escaped visual previews for office formats that are not rendered by Android itself. */
internal object SpreadsheetPreviewHtml {
    fun render(sections: List<DocumentSection>, cancel: () -> Unit = {}): String? {
        val body = StringBuilder()
        sections.forEachIndexed { index, section ->
            cancel()
            body.append("<h2>").append(escapeHtml(section.title)).append("</h2>")
            val rows = section.text.split('\n')
            body.append("<div class=\"table-wrap\"><table><tbody>")
            rows.forEach { row ->
                cancel()
                body.append("<tr>")
                row.split('\t').forEach { cell ->
                    body.append("<td>").append(escapeHtml(cell)).append("</td>")
                }
                body.append("</tr>")
                if (body.length > MAX_HTML_BODY) return null
            }
            body.append("</tbody></table></div>")
            if (index < sections.lastIndex) body.append("<hr>")
        }
        return page(body.toString())
    }
}

internal object PresentationPreviewHtml {
    fun render(
        parts: Map<String, ByteArray>,
        slides: List<DocumentSection>,
        cancel: () -> Unit = {},
    ): String? {
        val body = StringBuilder()
        val paths = parts.keys.filter { it.matches(Regex("ppt/slides/slide[0-9]+\\.xml")) }
            .sortedBy { it.substringAfterLast("slide").substringBefore('.').toIntOrNull() ?: 0 }
        var renderedImages = 0
        var encodedImageBytes = 0L

        paths.forEachIndexed { index, path ->
            cancel()
            val slide = slides.getOrNull(index) ?: return null
            body.append("<section class=\"slide\"><h2>")
                .append(escapeHtml(slide.title)).append("</h2>")
            if (slide.text.isNotBlank()) {
                body.append("<p>").append(escapeHtml(slide.text).replace("\n", "<br>")).append("</p>")
            }

            val relationships = slideImageRelationships(parts, path, cancel)
            val referencedImages = linkedSetOf<String>()
            val slideXml = parts[path] ?: return null
            DocumentContentParser.parseXml(slideXml, cancel, start = { tag, attributes ->
                if (tag == "blip") attribute(attributes, "embed")?.let(referencedImages::add)
            })
            referencedImages.forEach imageLoop@{ relationId ->
                cancel()
                if (renderedImages >= MAX_IMAGES) return@imageLoop
                val imagePath = relationships[relationId] ?: return@imageLoop
                val bytes = parts[imagePath] ?: return@imageLoop
                if (bytes.size > MAX_IMAGE_BYTES) return@imageLoop
                val mime = imageMime(bytes) ?: return@imageLoop
                val encodedLength = ((bytes.size + 2L) / 3L) * 4L
                if (encodedImageBytes + encodedLength > MAX_ENCODED_IMAGES_BYTES) return@imageLoop
                val uri = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
                body.append("<img alt=\"Imagen de la diapositiva\" src=\"")
                    .append(uri).append("\">")
                renderedImages++
                encodedImageBytes += encodedLength
            }
            body.append("</section>")
            if (body.length > MAX_HTML_BODY) return null
        }
        return page(body.toString())
    }

    private fun slideImageRelationships(
        parts: Map<String, ByteArray>,
        slidePath: String,
        cancel: () -> Unit,
    ): Map<String, String> {
        val slideName = slidePath.substringAfterLast('/')
        val relationshipsPath = "ppt/slides/_rels/$slideName.rels"
        val bytes = parts[relationshipsPath] ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        DocumentContentParser.parseXml(bytes, cancel, start = { tag, attributes ->
            if (tag != "Relationship" || attribute(attributes, "TargetMode") == "External") return@parseXml
            val target = attribute(attributes, "Target").orEmpty()
            val mediaName = when {
                target.startsWith("../media/") -> target.removePrefix("../media/")
                target.startsWith("media/") -> target.removePrefix("media/")
                target.startsWith("/ppt/media/") -> target.removePrefix("/ppt/media/")
                else -> return@parseXml
            }
            if (!mediaName.matches(Regex("[A-Za-z0-9_.-]+\\.(png|jpg|jpeg|gif|webp)", RegexOption.IGNORE_CASE))) return@parseXml
            val id = attribute(attributes, "Id")?.takeIf(String::isNotBlank) ?: return@parseXml
            result[id] = "ppt/media/$mediaName"
        })
        return result
    }

    private fun attribute(attributes: Attributes, localName: String): String? =
        (0 until attributes.length).firstOrNull { index ->
            attributes.getLocalName(index) == localName || attributes.getQName(index).substringAfter(':') == localName
        }?.let(attributes::getValue)

    private fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)) -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 6 && bytes.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF" -> "image/gif"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}

private fun page(body: String): String = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <style>body{font-family:sans-serif;color:#242424;background:#fff;margin:16px;line-height:1.45}
    h2{font-size:1.1em;margin:16px 0 8px}p{white-space:pre-wrap;margin:0 0 12px}
    .table-wrap{max-width:100%;overflow-x:auto;margin:8px 0 20px}table{border-collapse:collapse;min-width:100%}
    td{border:1px solid #aaa;vertical-align:top;padding:6px;min-width:40px;white-space:pre-wrap}
    .slide{box-sizing:border-box;min-height:320px;margin:12px 0;padding:20px;background:#fff;border:1px solid #bbb;
    box-shadow:0 2px 8px #0002}img{display:block;max-width:100%;max-height:520px;height:auto;object-fit:contain;margin:12px auto}</style>
    </head><body>$body</body></html>"""

private fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach { char -> append(when (char) {
        '&' -> "&amp;"
        '<' -> "&lt;"
        '>' -> "&gt;"
        '"' -> "&quot;"
        '\'' -> "&#39;"
        else -> char.toString()
    }) }
}

private const val MAX_HTML_BODY = 12_000_000
private const val MAX_IMAGES = 20
private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
private const val MAX_ENCODED_IMAGES_BYTES = 10L * 1024 * 1024
