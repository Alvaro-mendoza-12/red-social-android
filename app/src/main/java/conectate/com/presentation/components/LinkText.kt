package conectate.com.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.TextUnit
import androidx.core.net.toUri

@Composable
fun LinkText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onUrlClick: ((String) -> Unit)? = null,
    onTextClick: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val urlClickListener = remember(uriHandler, onUrlClick) {
        LinkInteractionListener { annotation ->
            val url = (annotation as? LinkAnnotation.Url)?.url
            if (url != null) {
                if (onUrlClick != null &&
                    (url.contains("conectate.com/post/") || url.contains("conectate.com/video/"))
                ) {
                    onUrlClick(url)
                } else {
                    runCatching {
                        val externalUrl = if (url.toUri().scheme.isNullOrBlank()) {
                            "https://$url"
                        } else {
                            url
                        }
                        uriHandler.openUri(externalUrl)
                    }
                }
            }
        }
    }
    val textClickListener = remember(onTextClick) {
        LinkInteractionListener { onTextClick?.invoke() }
    }

    val annotatedString = remember(
        text,
        color,
        fontSize,
        onTextClick,
        urlClickListener,
        textClickListener
    ) {
        buildAnnotatedString {
            val urlPattern = android.util.Patterns.WEB_URL
            val matcher = urlPattern.matcher(text)
            var lastIndex = 0
            val plainStyle = SpanStyle(
                color = if (color == Color.Unspecified) Color.Unspecified else color,
                fontSize = fontSize
            )

            fun appendPlain(value: String) {
                if (onTextClick != null) {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = "TEXT",
                            styles = TextLinkStyles(style = plainStyle),
                            linkInteractionListener = textClickListener
                        )
                    ) {
                        append(value)
                    }
                } else {
                    withStyle(style = plainStyle) { append(value) }
                }
            }

            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()
                val url = text.substring(start, end)

                // Append text before URL
                if (start > lastIndex) {
                    appendPlain(text.substring(lastIndex, start))
                }

                // Append URL
                withLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = Color(0xFF1DA1F2),
                                textDecoration = TextDecoration.Underline,
                                fontSize = fontSize
                            )
                        ),
                        linkInteractionListener = urlClickListener
                    )
                ) {
                    append(url)
                }

                lastIndex = end
            }

            // Append remaining text
            if (lastIndex < text.length) {
                appendPlain(text.substring(lastIndex))
            }
        }
    }

    Text(
        text = annotatedString,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color),
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = onTextLayout
    )
}
