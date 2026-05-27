package conectate.com.presentation.calls

import android.media.RingtoneManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import conectate.com.data.model.CallInvite
import conectate.com.presentation.components.CircularProfileImage


fun CallInvitationOverlay(
    invite: CallInvite?,
    onAccept: (CallInvite) -> Unit,
    onDecline: (CallInvite) -> Unit
) {
    if (invite == null) return

    val context = LocalContext.current
    val ringtone = remember(invite.callId) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            RingtoneManager.getRingtone(context, uri)
        }.getOrNull()
    }

    DisposableEffect(invite.callId) {
        runCatching { ringtone?.play() }
        onDispose { runCatching { ringtone?.stop() } }
    }

    Dialog(
        onDismissRequest = { /* force action */ },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                ) {
                    CircularProfileImage(imageUrl = invite.callerAvatarUrl, size = 110.dp, showBorder = false)
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = invite.callerName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Llamada entrante",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (invite.title.isNotBlank()) {
                    Text(
                        text = invite.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f)
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { onDecline(invite) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Rechazar")
                    }

                    Button(
                        onClick = { onAccept(invite) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Aceptar")
                    }
                }
            }
        }
    }
}






