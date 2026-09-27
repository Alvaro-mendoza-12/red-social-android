package conectate.com.presentation.videos

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle

internal data class VideoMention(val username: String, val start: Int, val end: Int)

internal fun videoDescriptionMentions(text: String): List<VideoMention> =
    Regex("(?<![A-Za-z0-9_])@([A-Za-z0-9_.-]{3,24})")
        .findAll(text)
        .map { match ->
            VideoMention(match.groupValues[1], match.range.first, match.range.last + 1)
        }
        .toList()

@Composable
fun VideoDescriptionText(
    text: String,
    onMentionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
) {
    val mentionColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        Color(0xFF70D4FF)
    } else {
        Color(0xFF075A9E)
    }
    val annotated = remember(text, mentionColor) {
        buildAnnotatedString {
            append(text)
            videoDescriptionMentions(text).forEach { mention ->
                addStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold), mention.start, mention.end)
                addStringAnnotation("video_mention", mention.username, mention.start, mention.end)
            }
        }
    }
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        modifier = modifier.pointerInput(annotated, onMentionClick) {
            detectTapGestures { position ->
                val offset = layout?.getOffsetForPosition(position) ?: return@detectTapGestures
                annotated.getStringAnnotations("video_mention", offset, offset)
                    .firstOrNull()?.let { onMentionClick(it.item) }
            }
        },
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
    )
}
