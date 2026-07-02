package conectate.com.presentation.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBackgroundPickerSheet(
    onDismiss: () -> Unit,
    onSelectPattern: (String) -> Unit,
    onSelectGallery: () -> Unit,
    onClear: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Fondo de chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Fondos incluidos",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            BackgroundOption("Océano", Icons.Default.Palette, onClick = { onSelectPattern("preset_ocean") })
            BackgroundOption("Lavanda", Icons.Default.Palette, onClick = { onSelectPattern("preset_lavender") })
            BackgroundOption("Cálido", Icons.Default.Palette, onClick = { onSelectPattern("preset_warm") })
            BackgroundOption("Grafito", Icons.Default.Palette, onClick = { onSelectPattern("preset_graphite") })
            BackgroundOption(
                "Patrón doodles",
                Icons.Default.Palette,
                onClick = { onSelectPattern("pattern_doodles") }
            )
            BackgroundOption(
                "Patrón cuadrícula",
                Icons.Default.GridOn,
                onClick = { onSelectPattern("pattern_grid") }
            )
            BackgroundOption("Elegir de galería", Icons.Default.Image, onSelectGallery)
            BackgroundOption("Quitar fondo", Icons.Default.Block, onClear, isDestructive = true)
        }
    }
}

@Composable
private fun BackgroundOption(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    val tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (isDestructive) tint else MaterialTheme.colorScheme.onSurface)
    }
}
