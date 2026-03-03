package conectate.com.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import conectate.com.data.model.VoiceRoom

@Composable
fun ActiveVoiceBar(
    activeRoom: VoiceRoom?,
    isMuted: Boolean,
    onReEnter: (String) -> Unit,
    onToggleMute: () -> Unit,
    onHangUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = activeRoom != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
    ) {
        activeRoom?.let { room ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                color = Color(0xFF0F151C),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 0.dp,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onToggleMute,
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFF1B2430), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMuted) "Activar micrófono" else "Silenciar micrófono",
                            tint = if (isMuted) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onReEnter(room.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = null,
                            tint = Color(0xFF25D366),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${room.name} - En llamada",
                            color = Color(0xFF25D366),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            maxLines = 1
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    IconButton(
                        onClick = onHangUp,
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFF1B2430), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Colgar",
                            tint = Color(0xFFFF5A73),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
