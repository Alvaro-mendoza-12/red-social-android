package conectate.com.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal data class ReadableLinkColors(val foreground: Color, val background: Color = Color.Unspecified)

internal fun textContrastRatio(foreground: Color, background: Color): Float {
    val first = foreground.luminance()
    val second = background.luminance()
    return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
}

/** Retain blue links on every chat accent, with at least 4.5:1 text contrast. */
internal fun readableLinkColors(background: Color): ReadableLinkColors {
    val candidates = listOf(Color(0xFF1DA1F2), Color(0xFF1565C0), Color(0xFF063A80), Color(0xFFC7E7FF))
    candidates.firstOrNull { textContrastRatio(it, background) >= 4.5f }
        ?.let { return ReadableLinkColors(it) }
    // Some saturated accents cannot contrast sufficiently with blue text.
    return ReadableLinkColors(Color(0xFF1565C0), Color(0xFFE3F2FD))
}
