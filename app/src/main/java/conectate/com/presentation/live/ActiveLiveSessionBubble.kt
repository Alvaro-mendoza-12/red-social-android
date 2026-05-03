package conectate.com.presentation.live

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import conectate.com.data.model.ActiveLiveSession
import conectate.com.presentation.components.CircularProfileImage
import kotlin.math.roundToInt

@Composable
fun ActiveLiveSessionBubble(
    session: ActiveLiveSession,
    onReturnToLive: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offset by remember(session.stream.id) { mutableStateOf(IntOffset.Zero) }

    Surface(
        modifier = modifier
            .widthIn(max = 260.dp)
            .offset { offset }
            .pointerInput(session.stream.id) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offset = IntOffset(
                        x = offset.x + dragAmount.x.roundToInt(),
                        y = offset.y + dragAmount.y.roundToInt()
                    )
                }
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProfileImage(
                imageUrl = session.stream.hostAvatarUrl,
                size = 34.dp,
                showBorder = false
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = "LIVE",
                color = Color.White,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.stream.title.ifBlank { "En directo" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = session.viewerCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onReturnToLive) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "Abrir live",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
