package conectate.com.presentation.social

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Lifetime in hours is separate from the audio excerpt in seconds. */
@Composable
internal fun MomentLifetimeDialog(hours: Int, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember(hours) { mutableIntStateOf(hours.coerceIn(5, 24)) }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Schedule, null) },
        title = { Text("Tiempo visible") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("El momento desaparecerá después de este tiempo. Puedes eliminarlo antes.",
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 12, 24).forEach { preset ->
                        FilterChip(selected = chosen == preset, onClick = { chosen = preset },
                            label = { Text("$preset h") }, modifier = Modifier.weight(1f).testTag("moment_lifetime_${preset}h"))
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { chosen-- }, enabled = chosen > 5, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Remove, "Una hora menos")
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$chosen horas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Entre 5 y 24 horas", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { chosen++ }, enabled = chosen < 24, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Add, "Una hora más")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChange(chosen); onDismiss() }) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
