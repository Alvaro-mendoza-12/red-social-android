package conectate.com.presentation.documents

import java.text.Normalizer
import java.util.Locale

/** Offsets always refer to the original text, including composed/decomposed accents and emoji. */
internal data class DocumentTextMatch(
    val partIndex: Int,
    val start: Int,
    val endExclusive: Int,
    val blockIndex: Int,
)

internal data class DocumentReadingBlock(
    val partIndex: Int,
    val start: Int,
    val text: String,
    val isHeading: Boolean,
) {
    fun intersection(match: DocumentTextMatch): IntRange? {
        if (match.partIndex != partIndex) return null
        val first = maxOf(start, match.start)
        val end = minOf(start + text.length, match.endExclusive)
        return if (first < end) (first - start) until (end - start) else null
    }
}

internal data class DocumentSearchResults(val matches: List<DocumentTextMatch>, val totalMatches: Int) {
    val isLimited: Boolean get() = totalMatches > matches.size
}

/** A bounded local index. Create and search it on Default, never on the UI thread. */
internal class DocumentSearchIndex private constructor(
    val blocks: List<DocumentReadingBlock>,
    private val parts: List<SearchablePart>,
) {
    fun find(query: String, checkCancelled: () -> Unit = {}): DocumentSearchResults {
        val needle = normalize(query.trim(), checkCancelled).text
        if (needle.isEmpty()) return DocumentSearchResults(emptyList(), 0)
        val matches = ArrayList<DocumentTextMatch>()
        var count = 0
        parts.forEachIndexed { partIndex, part ->
            var offset = 0
            var previousStart = -1
            var previousEnd = -1
            while (offset <= part.normalized.text.length - needle.length) {
                checkCancelled()
                val found = part.normalized.text.indexOf(needle, offset)
                if (found < 0) break
                val start = part.normalized.starts[found]
                val end = part.normalized.ends[found + needle.length - 1]
                // Unicode normalization can expand one code point into several characters.
                if (start != previousStart || end != previousEnd) {
                    count++
                    if (matches.size < MAX_VISIBLE_MATCHES) {
                        val block = part.blockIndices.lastOrNull { blocks[it].start <= start }
                        if (block != null) matches += DocumentTextMatch(partIndex, start, end, block)
                    }
                    previousStart = start
                    previousEnd = end
                }
                offset = found + needle.length
            }
        }
        return DocumentSearchResults(matches, count)
    }

    companion object {
        const val BLOCK_CHARACTERS = 2_048
        const val MAX_VISIBLE_MATCHES = 1_000

        fun create(sections: List<DocumentSection>, checkCancelled: () -> Unit = {}): DocumentSearchIndex {
            val blocks = mutableListOf<DocumentReadingBlock>()
            val parts = mutableListOf<SearchablePart>()
            fun append(text: String, heading: Boolean) {
                if (text.isEmpty()) return
                checkCancelled()
                val partIndex = parts.size
                val firstBlock = blocks.size
                var start = 0
                while (start < text.length) {
                    checkCancelled()
                    var end = minOf(start + BLOCK_CHARACTERS, text.length)
                    if (end < text.length) {
                        val newline = text.lastIndexOf('\n', end - 1)
                        val space = text.lastIndexOf(' ', end - 1)
                        val boundary = if (newline >= start + BLOCK_CHARACTERS / 2) newline else space
                        if (boundary >= start + BLOCK_CHARACTERS / 2) end = boundary + 1
                        // Keep surrogate pairs and trailing combining marks in the same block.
                        if (Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
                        while (end < text.length && isMark(text.codePointAt(end))) {
                            end += Character.charCount(text.codePointAt(end))
                        }
                    }
                    blocks += DocumentReadingBlock(partIndex, start, text.substring(start, end), heading)
                    start = end
                }
                parts += SearchablePart(normalize(text, checkCancelled), firstBlock until blocks.size)
            }
            sections.forEach { section ->
                append(section.title, heading = true)
                append(section.text, heading = false)
            }
            return DocumentSearchIndex(blocks, parts)
        }

        private fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }

        private fun normalize(value: String, checkCancelled: () -> Unit): NormalizedText {
            val text = StringBuilder(value.length)
            val starts = ArrayList<Int>(value.length)
            val ends = ArrayList<Int>(value.length)
            var offset = 0
            while (offset < value.length) {
                if (offset % 256 == 0) checkCancelled()
                val codePoint = value.codePointAt(offset)
                val end = offset + Character.charCount(codePoint)
                val folded = Normalizer.normalize(String(Character.toChars(codePoint)), Normalizer.Form.NFD)
                    .lowercase(Locale.ROOT)
                var emitted = false
                var foldedOffset = 0
                while (foldedOffset < folded.length) {
                    val foldedCodePoint = folded.codePointAt(foldedOffset)
                    if (!isMark(foldedCodePoint)) {
                        text.appendCodePoint(foldedCodePoint)
                        repeat(Character.charCount(foldedCodePoint)) { starts += offset; ends += end }
                        emitted = true
                    }
                    foldedOffset += Character.charCount(foldedCodePoint)
                }
                if (!emitted && ends.isNotEmpty()) ends[ends.lastIndex] = end
                offset = end
            }
            return NormalizedText(text.toString(), starts.toIntArray(), ends.toIntArray())
        }
    }

    private data class SearchablePart(val normalized: NormalizedText, val blockIndices: IntRange)
    private data class NormalizedText(val text: String, val starts: IntArray, val ends: IntArray)
}
