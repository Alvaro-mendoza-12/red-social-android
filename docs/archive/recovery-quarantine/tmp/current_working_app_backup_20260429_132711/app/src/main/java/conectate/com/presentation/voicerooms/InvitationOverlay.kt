package conectate.com.presentation.voicerooms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import conectate.com.data.repository.Invitation
import conectate.com.presentation.navigation.Screen

@Composable
fun InvitationOverlay(
    invitation: Invitation?,
    onAccept: (String) -> Unit,
    onDecline: () -> Unit
) {
    AnimatedVisibility(
        visible = invitation != null,
        enter = slideInVertically(initialOffsetY = { -it }),
        exit = slideOutVertically(targetOffsetY = { -it }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .statusBarsPadding()
    ) {
        invitation?.let { invite ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E1E1E),
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Invitación a Sala de Voz",
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${invite.inviterName} te invitó a",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                        Text(
                            text = invite.roomName,
                            color = Color(0xFF4CA1AF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onDecline,
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0xFFFF5252).copy(alpha = 0.2f),
                                contentColor = Color(0xFFFF5252)
                            )
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Rechazar")
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        IconButton(
                            onClick = { onAccept(invite.roomId) },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0xFF4CAF50).copy(alpha = 0.2f),
                                contentColor = Color(0xFF4CAF50)
                            )
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Aceptar")
                        }
                    }
                }
            }
        }
    }
}
