package conectate.com.presentation.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Configuración de restricciones de video estilo TikTok
 */
object VideoConstraints {
    const val MAX_DURATION_SECONDS = 30
    const val MAX_FILE_SIZE_MB = 100
    const val MAX_FILE_SIZE_BYTES = MAX_FILE_SIZE_MB * 1024 * 1024L
    const val MIN_DURATION_SECONDS = 3
    const val MIN_DIMENSION = 360
    const val MAX_DIMENSION = 1920
    const val TARGET_ASPECT_RATIO = 9f / 16f // Vertical video (TikTok style)
    const val ALLOWED_ASPECT_RATIO_TOLERANCE = 0.2f
}

data class VideoValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val durationSeconds: Int,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long
)

object VideoValidator {
    
    suspend fun validateVideo(context: Context, uri: Uri): VideoValidationResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        
        // Check file size
        val fileSize = getFileSize(context, uri)
        if (fileSize > VideoConstraints.MAX_FILE_SIZE_BYTES) {
            errors.add("El video es demasiado grande (${formatFileSize(fileSize)}). Máximo: ${VideoConstraints.MAX_FILE_SIZE_MB}MB")
        }
        
        // Check duration and dimensions
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val durationSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs).toInt()
            
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            
            // Validate duration
            if (durationSeconds > VideoConstraints.MAX_DURATION_SECONDS) {
                errors.add("El video dura $durationSeconds segundos. Máximo permitido: ${VideoConstraints.MAX_DURATION_SECONDS} segundos")
            }
            
            if (durationSeconds < VideoConstraints.MIN_DURATION_SECONDS) {
                errors.add("El video es muy corto ($durationSeconds segundos). Mínimo: ${VideoConstraints.MIN_DURATION_SECONDS} segundos")
            }
            
            // Validate dimensions
            if (width < VideoConstraints.MIN_DIMENSION || height < VideoConstraints.MIN_DIMENSION) {
                errors.add("Resolución muy baja (${width}x$height). Mínimo: ${VideoConstraints.MIN_DIMENSION}x${VideoConstraints.MIN_DIMENSION}")
            }
            
            // Validate aspect ratio (encourage vertical video)
            if (width > 0 && height > 0) {
                val aspectRatio = width.toFloat() / height.toFloat()
                val targetRatio = VideoConstraints.TARGET_ASPECT_RATIO
                val tolerance = VideoConstraints.ALLOWED_ASPECT_RATIO_TOLERANCE
                
                if (aspectRatio > targetRatio + tolerance) {
                    // Video is too wide (horizontal)
                    errors.add("El video es horizontal. Se recomienda formato vertical (9:16)")
                }
            }
            
            VideoValidationResult(
                isValid = errors.isEmpty(),
                errors = errors,
                durationSeconds = durationSeconds,
                width = width,
                height = height,
                fileSizeBytes = fileSize
            )
        } catch (e: Exception) {
            errors.add("Error al analizar el video: ${e.message}")
            VideoValidationResult(
                isValid = false,
                errors = errors,
                durationSeconds = 0,
                width = 0,
                height = 0,
                fileSizeBytes = fileSize
            )
        } finally {
            retriever.release()
        }
    }
    
    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                fd.statSize
            } ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
}

@Composable
fun VideoValidationDialog(
    result: VideoValidationResult,
    onDismiss: () -> Unit,
    onProceed: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Icon
                val icon = if (result.isValid) Icons.Default.CheckCircle else Icons.Default.Warning
                val iconColor = if (result.isValid) Color(0xFF4CAF50) else Color(0xFFFF9800)
                
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(56.dp)
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Title
                Text(
                    text = if (result.isValid) "Video válido" else "Problemas encontrados",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = iconColor
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Video info
                VideoInfoRow(
                    icon = Icons.Default.Timer,
                    label = "Duración",
                    value = "${result.durationSeconds}s / ${VideoConstraints.MAX_DURATION_SECONDS}s máx"
                )
                
                VideoInfoRow(
                    icon = Icons.Default.AspectRatio,
                    label = "Resolución",
                    value = "${result.width}x${result.height}"
                )
                
                VideoInfoRow(
                    icon = Icons.Default.Storage,
                    label = "Tamaño",
                    value = VideoValidator.formatFileSize(result.fileSizeBytes)
                )
                
                // Errors
                if (result.errors.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Problemas:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    result.errors.forEach { error ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancelar")
                    }
                    
                    if (result.isValid) {
                        Button(
                            onClick = onProceed,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Continuar")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Componente de advertencia de duración durante grabación/edición
 */
@Composable
fun VideoDurationWarning(
    currentSeconds: Int,
    maxSeconds: Int = VideoConstraints.MAX_DURATION_SECONDS,
    modifier: Modifier = Modifier
) {
    val progress = currentSeconds.toFloat() / maxSeconds.toFloat()
    val color = when {
        progress > 0.9f -> Color(0xFFF44336) // Red - almost limit
        progress > 0.7f -> Color(0xFFFF9800) // Orange - getting close
        else -> Color(0xFF4CAF50) // Green - good
    }
    
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$currentSeconds s",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            
            Text(
                text = "Max: ${maxSeconds}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(color)
            )
        }
        
        if (progress >= 1f) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "¡Límite de duración alcanzado!",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFF44336),
                fontWeight = FontWeight.Bold
            )
        }
    }
}
