package conectate.com.presentation.components

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit

@Composable
fun LinkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    onUrlClick: ((String) -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current

    val annotatedString = remember(text, color, fontSize) {
        buildAnnotatedString {
            val urlPattern = android.util.Patterns.WEB_URL
            val matcher = urlPattern.matcher(text)
            var lastIndex = 0

            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()
                val url = text.substring(start, end)

                // Append text before URL
                if (start > lastIndex) {
                    withStyle(style = SpanStyle(color = if (color == Color.Unspecified) Color.Unspecified else color, fontSize = fontSize)) {
                        append(text.substring(lastIndex, start))
                    }
                }

                // Append URL
                pushStringAnnotation(tag = "URL", annotation = url)
                withStyle(
                    style = SpanStyle(
                        color = Color(0xFF1DA1F2), // Twitter/Facebook Blue
                        textDecoration = TextDecoration.Underline,
                        fontSize = fontSize
                    )
                ) {
                    append(url)
                }
                pop()

                lastIndex = end
            }

            // Append remaining text
            if (lastIndex < text.length) {
                withStyle(style = SpanStyle(color = if (color == Color.Unspecified) Color.Unspecified else color, fontSize = fontSize)) {
                    append(text.substring(lastIndex))
                }
            }
        }
    }

    ClickableText(
        text = annotatedString,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color),
        onClick = { offset: Int ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    val url = annotation.item
                    if (onUrlClick != null && (url.contains("conectate.com/post/") || url.contains("conectate.com/video/"))) {
                        onUrlClick(url)
                    } else {
                        try {
                            uriHandler.openUri(url)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
        }
    )
}
