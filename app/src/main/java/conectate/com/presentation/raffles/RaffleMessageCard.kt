package conectate.com.presentation.raffles

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun RaffleMessageCard(title: String, creator: String?, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🎡 Sorteo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            creator?.let { Text("Creado por $it", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onOpen) { Text("Ver sorteo y resultados") }
        }
    }
}
