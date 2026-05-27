package conectate.com.presentation.video

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import conectate.com.data.model.Video

enum class DuetMode {
    LEFT_RIGHT,    // Original left, Duet right
    RIGHT_LEFT,    // Duet left, Original right
    TOP_BOTTOM,    // Original top, Duet bottom
    REACTION       // Picture-in-picture style
}

enum class StitchPosition {
    START,  // Your video plays first, then original
    END     // Original plays first, then your video
}

data class DuetConfig(
    val mode: DuetMode = DuetMode.RIGHT_LEFT,
    val useAudioFrom: String = "both", // "original", "duet", "both"
    val layout: String = "side_by_side"
)

data class StitchConfig(
    val position: StitchPosition = StitchPosition.END,
    val trimOriginalStart: Int? = null, // seconds to trim from start
    val trimOriginalEnd: Int? = null,    // seconds to trim from end
    val maxTotalDuration: Int = 30
)

@Composable
fun DuetStitchOptionsDialog(
    video: Video,
    onDuetSelected: (DuetConfig) -> Unit,
    onStitchSelected: (StitchConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0 = Duet, 1 = Stitch
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header with video preview
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    AsyncImage(
                        model = video.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column {
                        Text(
                            text = video.title ?: "Video",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = "@${video.userName.ifBlank { "usuario" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Duet") },
                        icon = { Icon(Icons.Default.Splitscreen, null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Stitch") },
                        icon = { Icon(Icons.Default.ContentCut, null) }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Content based on tab
                when (selectedTab) {
                    0 -> DuetOptionsContent(
                        onConfigSelected = { config ->
                            onDuetSelected(config)
                            onDismiss()
                        }
                    )
                    1 -> StitchOptionsContent(
                        originalDuration = if (video.duration > 0) video.duration.toInt() else 30,
                        onConfigSelected = { config ->
                            onStitchSelected(config)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DuetOptionsContent(
    onConfigSelected: (DuetConfig) -> Unit
) {
    var selectedMode by remember { mutableStateOf(DuetMode.RIGHT_LEFT) }
    var selectedAudio by remember { mutableStateOf("both") }
    
    Column {
        Text(
            text = "Selecciona el diseño",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        // Layout options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DuetMode.values().forEach { mode ->
                DuetModeCard(
                    mode = mode,
                    isSelected = selectedMode == mode,
                    onClick = { selectedMode = mode }
                )
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Audio options
        Text(
            text = "Configuración de audio",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        Column {
            AudioOptionRow(
                label = "Audio del video original",
                isSelected = selectedAudio == "original",
                onClick = { selectedAudio = "original" }
            )
            AudioOptionRow(
                label = "Audio de tu dueto",
                isSelected = selectedAudio == "duet",
                onClick = { selectedAudio = "duet" }
            )
            AudioOptionRow(
                label = "Ambos audios",
                isSelected = selectedAudio == "both",
                onClick = { selectedAudio = "both" }
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Button(
            onClick = {
                onConfigSelected(DuetConfig(
                    mode = selectedMode,
                    useAudioFrom = selectedAudio
                ))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.VideoCall, null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Crear Duet")
        }
    }
}

@Composable
private fun DuetModeCard(
    mode: DuetMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val (icon, label) = when (mode) {
        DuetMode.LEFT_RIGHT -> Icons.Default.ViewColumn to "Izquierda"
        DuetMode.RIGHT_LEFT -> Icons.Default.ViewColumn to "Derecha"
        DuetMode.TOP_BOTTOM -> Icons.Default.TableRows to "Arriba/Abajo"
        DuetMode.REACTION -> Icons.Default.PictureInPicture to "Reacción"
    }
    
    Card(
        modifier = Modifier
            .width(80.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AudioOptionRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = isSelected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun StitchOptionsContent(
    originalDuration: Int,
    onConfigSelected: (StitchConfig) -> Unit
) {
    var position by remember { mutableStateOf(StitchPosition.END) }
    var trimStart by remember { mutableStateOf(0) }
    var trimEnd by remember { mutableStateOf(originalDuration.coerceAtMost(15)) }
    
    Column {
        Text(
            text = "¿Dónde insertar tu video?",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        // Position options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StitchPositionCard(
                modifier = Modifier.weight(1f),
                label = "Tu video primero",
                description = "Tu contenido + original",
                isSelected = position == StitchPosition.START,
                onClick = { position = StitchPosition.START }
            )
            
            StitchPositionCard(
                modifier = Modifier.weight(1f),
                label = "Original primero",
                description = "Original + tu contenido",
                isSelected = position == StitchPosition.END,
                onClick = { position = StitchPosition.END }
            )
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        // Trim options
        Text(
            text = "Recortar video original",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Inicio: ${trimStart}s",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Fin: ${trimEnd}s",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Duration slider
                RangeSlider(
                    value = trimStart.toFloat()..trimEnd.toFloat(),
                    onValueChange = { range ->
                        trimStart = range.start.toInt()
                        trimEnd = range.endInclusive.toInt()
                    },
                    valueRange = 0f..originalDuration.toFloat()
                )
                
                Text(
                    text = "Duración seleccionada: ${trimEnd - trimStart} segundos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(20.dp))
        
        Button(
            onClick = {
                onConfigSelected(StitchConfig(
                    position = position,
                    trimOriginalStart = if (trimStart > 0) trimStart else null,
                    trimOriginalEnd = if (trimEnd < originalDuration) trimEnd else null
                ))
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ContentCut, null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Crear Stitch")
        }
    }
}

@Composable
private fun StitchPositionCard(
    modifier: Modifier = Modifier,
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = if (isSelected) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun DuetStitchButton(
    video: Video,
    onDuetClick: (DuetConfig) -> Unit,
    onStitchClick: (StitchConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var showOptions by remember { mutableStateOf(false) }
    
    Column(modifier = modifier) {
        IconButton(
            onClick = { showOptions = true }
        ) {
            Icon(
                imageVector = Icons.Default.Splitscreen,
                contentDescription = "Duet/Stitch",
                modifier = Modifier.size(28.dp),
                tint = Color.White
            )
        }
        
        Text(
            text = "Duet",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    
    if (showOptions) {
        DuetStitchOptionsDialog(
            video = video,
            onDuetSelected = onDuetClick,
            onStitchSelected = onStitchClick,
            onDismiss = { showOptions = false }
        )
    }
}
