package conectate.com.presentation.messages

import android.icu.text.BreakIterator
import java.util.Locale

/** Delete one visible character, preserving joined emoji, flags, modifiers and UTF-16 pairs. */
internal fun removeLastChatGrapheme(text: String): String {
    if (text.isEmpty()) return text
    val characters = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    var start = characters.preceding(text.length).coerceAtLeast(0)
    // Older Android Unicode tables may not recognize a newer ZWJ emoji yet.
    // Keep the joiner-connected parts together instead of leaving a broken glyph.
    while (start > 0 && (text[start - 1] == '\u200D' || text[start] == '\u200D' ||
            extendsEmojiAtBoundary(text, start))) {
        start = characters.preceding(start).coerceAtLeast(0)
    }
    if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
    return text.substring(0, start)
}

private fun extendsEmojiAtBoundary(text: String, offset: Int): Boolean {
    val point = text.codePointAt(offset)
    if (point in 0x1F3FB..0x1F3FF) {
        // API 24's Unicode table does not know newer emoji bases, but must not
        // delete an ordinary letter before a standalone modifier either.
        val previous = text.codePointBefore(offset)
        return previous in 0x1F000..0x1FAFF || previous in 0x2600..0x27FF
    }
    return point in 0xFE00..0xFE0F || point in 0xE0100..0xE01EF || point in 0xE0020..0xE007F ||
        Character.getType(point) in setOf(Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
}
