package conectate.com.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import conectate.com.data.model.publicMentionPattern

private val mentionBlue = Color(0xFF64B5F6)

val mentionInputTransformation = VisualTransformation { value ->
    val styled = AnnotatedString.Builder(value.text)
    publicMentionPattern.findAll(value.text).forEach { match ->
        styled.addStyle(SpanStyle(color = mentionBlue), match.range.first, match.range.last + 1)
    }
    TransformedText(styled.toAnnotatedString(), OffsetMapping.Identity)
}

@Composable
fun MentionText(content: String, onMentionClick: (String) -> Unit) {
    val listener = remember(onMentionClick) {
        LinkInteractionListener { link ->
            (link as? LinkAnnotation.Clickable)?.tag?.let(onMentionClick)
        }
    }
    val annotated = remember(content, listener) {
        buildAnnotatedString {
            var offset = 0
            publicMentionPattern.findAll(content).forEach { match ->
                append(content.substring(offset, match.range.first))
                withLink(
                    LinkAnnotation.Clickable(
                        tag = match.groupValues[1],
                        styles = TextLinkStyles(style = SpanStyle(color = mentionBlue)),
                        linkInteractionListener = listener,
                    )
                ) { append(match.value) }
                offset = match.range.last + 1
            }
            append(content.substring(offset))
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium)
}
