package conectate.com.data.local

import java.text.BreakIterator
import java.util.Locale

/** Preserve valid emoji sequences and repair orphan UTF-16 units from old drafts. */
internal fun safeConversationDraft(text: String, maxLength: Int = 16_000): String {
    val limit = maxLength.coerceAtLeast(0)
    var end = minOf(text.length, limit)
    if (end < text.length && end > 0) {
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        if (!characters.isBoundary(end)) end = characters.preceding(end).coerceAtLeast(0)
        if (end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    }
    var index = 0
    var repaired: StringBuilder? = null
    while (index < end) {
        val char = text[index]
        val pair = char.isHighSurrogate() && index + 1 < end && text[index + 1].isLowSurrogate()
        val invalid = char.isLowSurrogate() || char.isHighSurrogate() && !pair
        if (invalid && repaired == null) repaired = StringBuilder(end).append(text, 0, index)
        if (repaired != null) {
            if (invalid) repaired.append('\uFFFD')
            else {
                repaired.append(char)
                if (pair) repaired.append(text[index + 1])
            }
        }
        index += if (pair) 2 else 1
    }
    return repaired?.toString() ?: if (end == text.length) text else text.substring(0, end)
}

/** IME offsets use UTF-16 units; a caret must not split a surrogate pair. */
internal fun safeConversationCaret(text: CharSequence, offset: Int): Int {
    val bounded = offset.coerceIn(0, text.length)
    return if (bounded in 1 until text.length && text[bounded - 1].isHighSurrogate() && text[bounded].isLowSurrogate())
        bounded + 1 else bounded
}
