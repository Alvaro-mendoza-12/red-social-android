package conectate.com.presentation.components

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.delay

/** Uses the user's enabled system dictionary; never sends drafts to our server. */
@Composable
fun rememberChatSpellCheck(
    text: String,
    base: VisualTransformation = VisualTransformation.None,
): VisualTransformation {
    val context = LocalContext.current
    val checker = remember(context) { ChatSpellChecker(context) }
    DisposableEffect(checker) { onDispose { checker.close() } }
    LaunchedEffect(checker, text) {
        checker.invalidate()
        delay(550)
        checker.check(text)
    }
    val checkedText = checker.checkedText
    val mistakes = checker.mistakes
    return remember(base, checkedText, mistakes) {
        VisualTransformation { input ->
            val transformed = base.filter(input)
            if (input.text != checkedText || mistakes.isEmpty()) transformed else {
                val annotated = AnnotatedString.Builder(transformed.text)
                mistakes.forEach { range ->
                    val start = transformed.offsetMapping.originalToTransformed(range.first)
                    val end = transformed.offsetMapping.originalToTransformed(range.last + 1)
                    if (start < end && end <= transformed.text.length) annotated.addStyle(
                        SpanStyle(color = Color(0xFFED6666), textDecoration = TextDecoration.Underline), start, end,
                    )
                }
                TransformedText(annotated.toAnnotatedString(), transformed.offsetMapping)
            }
        }
    }
}

private class ChatSpellChecker(context: Context) : SpellCheckerSession.SpellCheckerSessionListener {
    var checkedText by mutableStateOf("")
        private set
    var mistakes by mutableStateOf<List<IntRange>>(emptyList())
        private set
    private var sequence = 0
    private var requestedText = ""
    private var closed = false
    private val session = runCatching {
        (context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager)
            ?.newSpellCheckerSession(null, null, this, true)
    }.getOrNull()

    fun invalidate() { sequence++; checkedText = ""; mistakes = emptyList(); session?.cancel() }
    fun check(text: String) {
        if (closed || text.isBlank() || text.length > 4_000) return
        requestedText = text
        runCatching { session?.getSentenceSuggestions(arrayOf(TextInfo(text, 0, sequence)), 3) }
    }
    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit
    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        if (closed) return
        val ranges = mutableListOf<IntRange>()
        var current = false
        results.orEmpty().forEach { sentence ->
            for (index in 0 until sentence.suggestionsCount) {
                val suggestion = sentence.getSuggestionsInfoAt(index) ?: continue
                if (suggestion.sequence != sequence) continue
                current = true
                val start = sentence.getOffsetAt(index)
                val end = start + sentence.getLengthAt(index)
                if (start < 0 || end > requestedText.length || start >= end) continue
                // Handles, links and identifiers should not be marked as misspellings.
                val word = requestedText.substring(start, end)
                if (word.any(Char::isDigit) || start > 0 && requestedText[start - 1] in "@/#") continue
                if (suggestion.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0) {
                    ranges += start until end
                }
            }
        }
        if (current) { checkedText = requestedText; mistakes = ranges }
    }
    fun close() { closed = true; sequence++; session?.close() }
}
