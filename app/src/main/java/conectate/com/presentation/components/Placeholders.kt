package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import conectate.com.data.repository.UserRepository
import conectate.com.data.model.User


@Composable
fun OfflineNoticeBar(
    message: String = "Sin conexión a internet",
    onRetry: () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onRetry() },
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Box(
            modifier = Modifier.padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$message (Toca para reintentar)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareOptionsSheet(
    onDismiss: () -> Unit,
    onSendToFriend: () -> Unit,
    onExternalShare: () -> Unit,
    onToggleProfileShare: () -> Unit,
    isSharedToProfile: Boolean
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp, start = 16.dp, end = 16.dp, top = 8.dp)
        ) {
            ListItem(
                headlineContent = { Text("Enviar a un amigo") },
                modifier = Modifier.clickable { onSendToFriend(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text(if (isSharedToProfile) "Quitar de mi perfil" else "Compartir en mi perfil") },
                modifier = Modifier.clickable { onToggleProfileShare(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text("Compartir en otras apps") },
                modifier = Modifier.clickable { onExternalShare(); onDismiss() }
            )
        }
    }
}


