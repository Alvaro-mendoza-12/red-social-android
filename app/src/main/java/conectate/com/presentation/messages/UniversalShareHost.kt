package conectate.com.presentation.messages

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.util.IncomingShareKind
import conectate.com.util.IncomingSharePayload

@Composable
fun UniversalShareHost(
    incomingShare: IncomingSharePayload?,
    forwardCoordinator: UniversalForwardCoordinator,
    onIncomingShareConsumed: () -> Unit
) {
    val forwardRequest = forwardCoordinator.request
    if (incomingShare == null && forwardRequest == null) return

    val viewModel: ShareDestinationViewModel = hiltViewModel()
    val destinations by viewModel.destinations.collectAsStateWithLifecycle()
    val actionState by viewModel.actionState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activeKey = forwardRequest?.requestId ?: incomingShare?.id.orEmpty()
    var query by remember(activeKey) { mutableStateOf("") }
    var selectedKeys by remember(activeKey) { mutableStateOf<Set<String>>(emptySet()) }

    fun close() {
        viewModel.resetActionState()
        if (forwardRequest != null) {
            forwardCoordinator.dismiss()
        } else {
            onIncomingShareConsumed()
        }
    }

    LaunchedEffect(activeKey) {
        viewModel.resetActionState()
        viewModel.refreshDestinations()
    }

    LaunchedEffect(actionState) {
        val completed = actionState as? ShareActionState.Completed ?: return@LaunchedEffect
        Toast.makeText(context, completed.message, Toast.LENGTH_SHORT).show()
        close()
    }

    val filtered = remember(destinations, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) {
            destinations
        } else {
            destinations.filter { destination ->
                destination.title.lowercase().contains(normalized) ||
                    destination.subtitle.orEmpty().lowercase().contains(normalized)
            }
        }
    }
    val isRunning = actionState is ShareActionState.Running
    val preview = remember(incomingShare, forwardRequest) {
        when {
            forwardRequest != null -> {
                val count = forwardRequest.candidates.size
                "$count mensaje${if (count == 1) "" else "s"} para reenviar"
            }
            incomingShare != null -> incomingShare.previewLabel()
            else -> ""
        }
    }

    Dialog(
        onDismissRequest = { if (!isRunning) close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (forwardRequest != null) "Reenviar a…" else "Compartir con…",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (preview.isNotBlank()) {
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { close() }, enabled = !isRunning) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Buscar en todo") },
                    placeholder = { Text("Chats, grupos o comunidades") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    enabled = !isRunning
                )

                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 470.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ShareDestinationType.entries.forEach { type ->
                        val section = filtered.filter { it.type == type }
                        if (section.isNotEmpty()) {
                            item(key = "header-${type.name}") {
                                Text(
                                    text = type.sectionTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                            items(section, key = ShareDestination::key) { destination ->
                                val checked = destination.key in selectedKeys
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isRunning) {
                                            selectedKeys = if (checked) {
                                                selectedKeys - destination.key
                                            } else {
                                                selectedKeys + destination.key
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = destination.type.sectionIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(destination.title, fontWeight = FontWeight.Medium)
                                        destination.subtitle?.takeIf { it.isNotBlank() }?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Checkbox(
                                        checked = checked,
                                        enabled = !isRunning,
                                        onCheckedChange = { shouldSelect ->
                                            selectedKeys = if (shouldSelect) {
                                                selectedKeys + destination.key
                                            } else {
                                                selectedKeys - destination.key
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = if (query.isBlank()) {
                                    "Aún no hay chats, grupos o comunidades disponibles."
                                } else {
                                    "No se encontraron destinos."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                }

                (actionState as? ShareActionState.Failed)?.let { failed ->
                    Text(
                        text = failed.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Button(
                    onClick = {
                        if (forwardRequest != null) {
                            viewModel.forwardMessages(forwardRequest.candidates, selectedKeys)
                        } else if (incomingShare != null) {
                            viewModel.sendIncomingShare(incomingShare, selectedKeys)
                        }
                    },
                    enabled = selectedKeys.isNotEmpty() && !isRunning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (forwardRequest != null) "Reenviar" else "Compartir")
                }
            }
        }
    }
}

private val ShareDestinationType.sectionTitle: String
    get() = when (this) {
        ShareDestinationType.CHAT -> "Chats"
        ShareDestinationType.GROUP -> "Grupos"
        ShareDestinationType.COMMUNITY -> "Comunidades"
    }

private val ShareDestinationType.sectionIcon
    get() = when (this) {
        ShareDestinationType.CHAT -> Icons.AutoMirrored.Filled.Chat
        ShareDestinationType.GROUP -> Icons.Default.Groups
        ShareDestinationType.COMMUNITY -> Icons.Default.Forum
    }

private fun IncomingSharePayload.previewLabel(): String {
    if (items.isEmpty()) return text?.trim()?.take(80) ?: "Texto compartido"
    val first = items.first()
    val kind = when (first.kind) {
        IncomingShareKind.IMAGE -> "Imagen"
        IncomingShareKind.GIF -> "GIF animado"
        IncomingShareKind.STICKER -> "Sticker"
        IncomingShareKind.VIDEO -> "Video"
        IncomingShareKind.AUDIO -> "Audio"
        IncomingShareKind.DOCUMENT -> "Documento"
    }
    val suffix = if (items.size > 1) " y ${items.size - 1} archivo(s) más" else ""
    return "${first.displayName?.takeIf { it.isNotBlank() } ?: kind}$suffix"
}
