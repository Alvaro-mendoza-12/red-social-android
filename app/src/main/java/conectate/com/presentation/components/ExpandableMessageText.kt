package conectate.com.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/** Changes only presentation; copying, replying and forwarding retain the full message. */
@Composable
fun ExpandableMessageText(
    text: String,
    backgroundColor: Color = Color.Unspecified,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    onUrlClick: ((String) -> Unit)? = null,
    onMentionClick: ((String) -> Unit)? = null,
    onCollectionInvitationClick: ((String) -> Unit)? = null,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Column {
        LinkText(
            text = text, backgroundColor = backgroundColor, color = color, fontSize = fontSize,
            maxLines = if (expanded) Int.MAX_VALUE else 8, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
            onUrlClick = onUrlClick, onMentionClick = onMentionClick,
            onCollectionInvitationClick = onCollectionInvitationClick,
        )
        if (overflowing || expanded) TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Ver menos" else "Leer más", color = color)
        }
    }
}
