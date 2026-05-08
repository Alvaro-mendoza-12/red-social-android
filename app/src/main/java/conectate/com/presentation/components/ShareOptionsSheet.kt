package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareOptionsSheet(
    onDismiss: () -> Unit,
    onSendToFriend: () -> Unit,
    onExternalShare: () -> Unit,
    onToggleProfileShare: () -> Unit,
    isSharedToProfile: Boolean,
    onReportVideo: (() -> Unit)? = null,
    isReported: Boolean = false
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
        ) {
            Text(
                text = "Compartir",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            ShareOptionItem(
                icon = Icons.Default.Send,
                label = "Enviar a amigo",
                onClick = {
                    onSendToFriend()
                    onDismiss()
                }
            )
            
            ShareOptionItem(
                icon = Icons.Default.Share,
                label = "Compartir enlace externo",
                onClick = {
                    onExternalShare()
                    onDismiss()
                }
            )
            
            ShareOptionItem(
                icon = if (isSharedToProfile) Icons.Default.RepeatOn else Icons.Default.Repeat,
                label = if (isSharedToProfile) "Quitar de mi perfil" else "Añadir a mi perfil",
                color = if (isSharedToProfile) Color(0xFF00bcd4) else Color.Unspecified,
                onClick = {
                    onToggleProfileShare()
                    onDismiss()
                }
            )

            if (onReportVideo != null) {
                ShareOptionItem(
                    icon = Icons.Default.Flag,
                    label = if (isReported) "Reportado" else "Reportar video",
                    color = if (isReported) Color(0xFFFF9800) else MaterialTheme.colorScheme.error,
                    onClick = {
                        if (!isReported) {
                            onReportVideo()
                        }
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun ShareOptionItem(
    icon: ImageVector,
    label: String,
    color: Color = Color.Unspecified,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = label,
                fontSize = 16.sp,
                color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
            )
        }
    }
}
