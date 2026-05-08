package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun ViewOnceBadge(
    modifier: Modifier = Modifier,
    isViewed: Boolean = false
) {
    val backgroundColor = if (isViewed) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color(0xFFFF9800).copy(alpha = 0.2f) // Orange for view-once
    }
    
    val contentColor = if (isViewed) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        Color(0xFFE65100)
    }
    
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isViewed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isViewed) "Visto" else "Ver una vez",
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun DisappearingMessageBadge(
    timerSeconds: Int,
    modifier: Modifier = Modifier
) {
    val backgroundColor = Color(0xFF4CAF50).copy(alpha = 0.2f) // Green for disappearing
    val contentColor = Color(0xFF2E7D32)
    
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Timer,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = formatTimer(timerSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun EphemeralTimer(
    secondsRemaining: Int,
    totalSeconds: Int,
    modifier: Modifier = Modifier
) {
    val progress = if (totalSeconds > 0) secondsRemaining.toFloat() / totalSeconds else 0f
    
    Box(
        modifier = modifier.size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        // Background circle
        CircularProgressIndicator(
            progress = 1f,
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.surfaceVariant
        )
        
        // Progress circle
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize(),
            strokeWidth = 2.dp,
            color = when {
                progress > 0.5f -> Color(0xFF4CAF50)
                progress > 0.25f -> Color(0xFFFF9800)
                else -> Color(0xFFF44336)
            }
        )
        
        Text(
            text = secondsRemaining.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun ViewOnceContentDialog(
    content: @Composable () -> Unit,
    onDismiss: () -> Unit,
    onViewed: () -> Unit,
    title: String = "Mensaje de visualización única"
) {
    var hasViewed by remember { mutableStateOf(false) }
    
    Dialog(
        onDismissRequest = {
            if (hasViewed) {
                onDismiss()
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(48.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = "Este mensaje se autodestruirá después de verlo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Content area
                if (!hasViewed) {
                    Button(
                        onClick = {
                            hasViewed = true
                            onViewed()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ver mensaje")
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(16.dp)
                    ) {
                        content()
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "⚠️ Este mensaje ya no estará disponible",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Cerrar")
                    }
                }
            }
        }
    }
}

@Composable
fun EphemeralMessageSettingsDialog(
    currentSetting: EphemeralSetting,
    onSettingSelected: (EphemeralSetting) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Mensajes temporales",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Los mensajes desaparecerán automáticamente después del tiempo seleccionado",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                EphemeralSetting.entries.forEach { setting ->
                    ListItem(
                        headlineContent = { Text(setting.label) },
                        leadingContent = {
                            RadioButton(
                                selected = currentSetting == setting,
                                onClick = { onSettingSelected(setting) }
                            )
                        },
                        supportingContent = setting.description?.let { { Text(it) } },
                        modifier = Modifier.clickable { onSettingSelected(setting) }
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Cancelar")
                }
            }
        }
    }
}

enum class EphemeralSetting(
    val label: String,
    val seconds: Int,
    val description: String? = null
) {
    OFF("Desactivado", 0, "Los mensajes no desaparecerán"),
    VIEW_ONCE("Ver una vez", -1, "El mensaje se elimina después de verlo"),
    SECONDS_30("30 segundos", 30),
    MINUTES_1("1 minuto", 60),
    MINUTES_5("5 minutos", 300),
    HOURS_1("1 hora", 3600),
    HOURS_24("24 horas", 86400),
    DAYS_7("7 días", 604800)
}

private fun formatTimer(seconds: Int): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86400 -> "${seconds / 3600}h"
        else -> "${seconds / 86400}d"
    }
}
