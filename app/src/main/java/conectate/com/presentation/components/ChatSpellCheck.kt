package conectate.com.presentation.components

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

data class ChatSpellingIssue(
    val start: Int,
    val end: Int,
    val word: String,
    val suggestions: List<String>,
)

data class ChatSpellCheckState(
    val visualTransformation: VisualTransformation,
    val issues: List<ChatSpellingIssue>,
) {
    fun replace(text: String, issue: ChatSpellingIssue, suggestion: String): String {
        if (issue.start !in 0..text.length || issue.end !in issue.start..text.length) return text
        if (text.substring(issue.start, issue.end) != issue.word) return text
        return text.replaceRange(issue.start, issue.end, suggestion)
    }
}

/** Uses the user's enabled system dictionary; drafts and suggestions stay on-device. */
@Composable
fun rememberChatSpellCheckState(
    text: String,
    base: VisualTransformation = VisualTransformation.None,
): ChatSpellCheckState {
    val context = LocalContext.current
    val checker = remember(context) { ChatSpellChecker(context) }
    DisposableEffect(checker) { onDispose { checker.close() } }
    LaunchedEffect(checker, text) {
        checker.invalidate()
        delay(450)
        checker.check(text)
    }
    val checkedText = checker.checkedText
    val issues = checker.issues.takeIf { checkedText == text }.orEmpty()
    val transformation = remember(base, checkedText, issues) {
        VisualTransformation { input ->
            val transformed = base.filter(input)
            if (input.text != checkedText || issues.isEmpty()) transformed else {
                val annotated = AnnotatedString.Builder(transformed.text)
                issues.forEach { issue ->
                    val start = transformed.offsetMapping.originalToTransformed(issue.start)
                    val end = transformed.offsetMapping.originalToTransformed(issue.end)
                    if (start < end && end <= transformed.text.length) annotated.addStyle(
                        SpanStyle(color = Color(0xFFED6666), textDecoration = TextDecoration.Underline), start, end,
                    )
                }
                TransformedText(annotated.toAnnotatedString(), transformed.offsetMapping)
            }
        }
    }
    return remember(transformation, issues) { ChatSpellCheckState(transformation, issues) }
}

/** Keeps selection/composition so tapping an underlined word can reveal its corrections. */
@Composable
fun rememberChatSpellingDraft(text: String): MutableState<TextFieldValue> {
    val draft = remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) {
        if (draft.value.text != text) draft.value = TextFieldValue(text, TextRange(text.length))
    }
    return draft
}

/** Shows tappable corrections returned by Android's local spell checker. */
@Composable
fun ChatSpellingSuggestions(
    state: ChatSpellCheckState,
    selection: TextRange,
    onReplace: (ChatSpellingIssue, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val issue = state.issues.firstOrNull {
        selection.min >= it.start && selection.max <= it.end
    } ?: return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Corregir «${issue.word}»", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        issue.suggestions.take(4).forEach { suggestion ->
            SuggestionChip(
                onClick = { onReplace(issue, suggestion) },
                label = { Text(suggestion, maxLines = 1) },
            )
            Spacer(Modifier.width(6.dp))
        }
        if (issue.suggestions.isEmpty()) {
            Text("Sin sugerencias", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal class ChatSpellChecker(context: Context) : SpellCheckerSession.SpellCheckerSessionListener {
    var checkedText by mutableStateOf("")
        private set
    var issues by mutableStateOf<List<ChatSpellingIssue>>(emptyList())
        private set
    private var sequence = 0
    private var requestedText = ""
    private var closed = false
    private val session = runCatching {
        (context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager)
            ?.newSpellCheckerSession(null, null, this, true)
    }.getOrNull()

    fun invalidate() {
        sequence++
        checkedText = ""
        issues = emptyList()
        runCatching { session?.cancel() }
    }

    fun check(text: String) {
        if (closed || text.isBlank() || text.length > 4_000) return
        requestedText = text
        runCatching { session?.getSentenceSuggestions(arrayOf(TextInfo(text, 0, sequence)), 4) }
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo?>?) {
        if (closed) return
        val found = mutableListOf<ChatSpellingIssue>()
        var current = false
        results.orEmpty().forEach { sentence ->
            if (sentence == null) return@forEach
            // Some Android spell services send null entries (notably after an
            // emoji-only request), or inconsistent parcelled ranges. A system
            // suggestion must never be able to terminate the app's Main thread.
            val count = runCatching { sentence.suggestionsCount }.getOrDefault(0)
                .coerceIn(0, requestedText.length + 1)
            for (index in 0 until count) {
                val info = runCatching { sentence.getSuggestionsInfoAt(index) }.getOrNull() ?: continue
                if (info.sequence != sequence) continue
                current = true
                val range = runCatching { sentence.getOffsetAt(index) to sentence.getLengthAt(index) }.getOrNull() ?: continue
                val (start, length) = range
                if (start < 0 || start > requestedText.length || length <= 0 || length > requestedText.length - start) continue
                val end = start + length
                if (conectate.com.data.local.safeConversationCaret(requestedText, start) != start ||
                    conectate.com.data.local.safeConversationCaret(requestedText, end) != end) continue
                // Handles, links, numeric codes and identifiers are not dictionary words.
                val word = requestedText.substring(start, end)
                if (word.none(Char::isLetter) || word.any(Char::isDigit) || start > 0 && requestedText[start - 1] in "@/#") continue
                if (info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) continue
                val suggestions = (0 until info.suggestionsCount.coerceIn(0, 16))
                    .mapNotNull { suggestionIndex -> runCatching { info.getSuggestionAt(suggestionIndex)?.trim() }.getOrNull() }
                    .filter { it.isNotBlank() && !it.equals(word, ignoreCase = true) }
                    .distinct()
                    .take(4)
                found += ChatSpellingIssue(start, end, word, suggestions)
            }
        }
        if (current) {
            checkedText = requestedText
            issues = found
        }
    }

    fun close() {
        closed = true
        sequence++
        runCatching { session?.close() }
    }
}
