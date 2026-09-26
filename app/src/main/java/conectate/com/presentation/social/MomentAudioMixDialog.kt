package conectate.com.presentation.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Edits the same selection that both preview players and the publication exporter consume. */
@Composable
internal fun MomentAudioMixDialog(
    selection: MomentMusicSelection,
    onChange: (MomentMusicSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val originalVolume = selection.originalAudioVolume * if (selection.duckOriginalAudio) 0.35f else 1f
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mezclar audio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Escucha la mezcla mientras ajustas los dos volúmenes.")
                AudioMixSlider("Audio original del video", originalVolume) {
                    onChange(selection.copy(originalAudioVolume = it, duckOriginalAudio = false))
                }
                AudioMixSlider("Música añadida", selection.musicVolume) {
                    onChange(selection.copy(musicVolume = it))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

@Composable
private fun AudioMixSlider(label: String, volume: Float, onChange: (Float) -> Unit) {
    val safeVolume = volume.coerceIn(0f, 1f)
    Text("$label: ${(safeVolume * 100).roundToInt()} %", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = safeVolume,
        onValueChange = onChange,
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
    )
    TextButton(onClick = { onChange(if (safeVolume > 0f) 0f else 1f) }) {
        Text(if (safeVolume > 0f) "Silenciar" else "Activar sonido")
    }
}
