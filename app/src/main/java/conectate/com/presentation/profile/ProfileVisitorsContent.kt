package conectate.com.presentation.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.profilePublicHandle
import conectate.com.presentation.components.CircularProfileImage

@Composable
internal fun ProfileVisitorsContent(
    state: ProfileVisitorsState,
    onRetry: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
        Text("Visitas recientes", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (state.loading) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Cargando visitas…")
            }
        }
        if (state.error != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text(state.error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Reintentar") }
            }
        }
        if (!state.loading && state.error == null && state.items.isEmpty()) {
            Text(if (!state.enabled) "El historial de visitas está desactivado en tu privacidad."
                else "Todavía no hay visitas visibles. Solo aparecen cuando ambos usuarios habilitan el historial.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
        if (state.items.isNotEmpty()) {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(state.items, key = { it.user.id }) { visitor ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenProfile(visitor.user.id) }
                        .padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProfileImage(imageUrl = visitor.user.avatar_url, size = 42.dp,
                            fallbackName = visitor.user.displayName)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(visitor.user.displayName, fontWeight = FontWeight.SemiBold)
                            Text("@${profilePublicHandle(visitor.user)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(profileVisitLabel(visitor.viewedAt), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }
}
