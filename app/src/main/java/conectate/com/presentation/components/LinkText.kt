package conectate.com.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
import conectate.com.data.model.videoIdFromSharedLink
import conectate.com.presentation.theme.readableLinkColors

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
    backgroundColor: Color = Color.Unspecified,
    onMentionClick: ((String) -> Unit)? = null,
    onCollectionInvitationClick: ((String) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val surface = MaterialTheme.colorScheme.surface
    val linkColors = readableLinkColors(
        if (backgroundColor == Color.Unspecified) surface else backgroundColor.compositeOver(surface),
    )
    val urlClickListener = remember(uriHandler, onUrlClick, onCollectionInvitationClick) {
        LinkInteractionListener { annotation ->
            val url = (annotation as? LinkAnnotation.Url)?.url
            if (url != null) {
                if (onCollectionInvitationClick != null && conectate.com.presentation.messages.CollectionQrParser.parse(url).isSuccess) {
                    onCollectionInvitationClick(url)
                } else if (onUrlClick != null &&
                    (postIdFromSharedLink(url) != null || videoIdFromSharedLink(url) != null)
                ) {
                    onUrlClick(url)
                } else {
                    runCatching {
                        uriHandler.openUri(url)
                    }.onFailure {
                        conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo abrir el enlace. Comprueba que tienes un navegador instalado.")
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
        linkColors,
        onTextClick,
        urlClickListener,
        textClickListener,
        onMentionClick
    ) {
        buildAnnotatedString {
            var lastIndex = 0
            val plainStyle = SpanStyle(
                color = if (color == Color.Unspecified) Color.Unspecified else color,
                fontSize = fontSize
            )

            fun appendUnlinked(value: String) {
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

            fun appendPlain(value: String) {
                if (onMentionClick == null) { appendUnlinked(value); return }
                var position = 0
                conectate.com.data.model.publicMentionPattern.findAll(value).forEach { match ->
                    appendUnlinked(value.substring(position, match.range.first))
                    withLink(LinkAnnotation.Clickable(
                        tag = match.groupValues[1],
                        styles = TextLinkStyles(style = SpanStyle(color = linkColors.foreground)),
                        linkInteractionListener = LinkInteractionListener { onMentionClick(match.groupValues[1]) },
                    )) { append(match.value) }
                    position = match.range.last + 1
                }
                appendUnlinked(value.substring(position))
            }

            for (link in findWebTextLinks(text)) {
                val start = link.start
                val end = link.end

                // Append text before URL
                if (start > lastIndex) {
                    appendPlain(text.substring(lastIndex, start))
                }

                // Append URL
                withLink(
                    LinkAnnotation.Url(
                        url = link.url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = linkColors.foreground,
                                background = linkColors.background,
                                textDecoration = TextDecoration.Underline,
                                fontSize = fontSize
                            )
                        ),
                        linkInteractionListener = urlClickListener
                    )
                ) {
                    append(text.substring(start, end))
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
