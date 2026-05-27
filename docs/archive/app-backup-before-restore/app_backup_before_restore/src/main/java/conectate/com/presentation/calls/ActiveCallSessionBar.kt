package conectate.com.presentation.calls

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import conectate.com.data.model.ActiveCall
import conectate.com.data.model.CallConnectionState


@Composable
fun ActiveCallSessionBar(
    call: ActiveCall,
    onReturnToCall: (String) -> Unit,
    onHangup: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .clickable { onReturnToCall(call.callId) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PhoneInTalk,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = call.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = when (call.connectionState) {
                    CallConnectionState.RINGING_OUTGOING -> "Llamando…"
                    CallConnectionState.RINGING_INCOMING -> "Entrante…"
                    CallConnectionState.CONNECTING -> "Conectando…"
                    CallConnectionState.CONNECTED -> "En llamada"
                    CallConnectionState.ENDED -> "Finalizada"
                    else -> ""
                }
                if (status.isNotBlank()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                IconButton(onClick = onHangup) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Colgar",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}






