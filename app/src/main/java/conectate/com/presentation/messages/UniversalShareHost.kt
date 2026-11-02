package conectate.com.presentation.messages

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.util.IncomingShareKind
import conectate.com.util.IncomingSharePayload
import kotlinx.coroutines.launch

@Composable
fun UniversalShareHost(
    incomingShare: IncomingSharePayload?,
    incomingShareRequestId: String? = null,
    forwardCoordinator: UniversalForwardCoordinator,
    onIncomingShareConsumed: () -> ShareDismissal,
    onOpenConversation: (ShareDestination) -> Unit = {},
    onFinished: () -> Unit = {},
) {
    val forwardRequest = forwardCoordinator.request
    val videoRequest = forwardCoordinator.videoRequest
    val postRequest = forwardCoordinator.postRequest
    val savedBatchRequestId = forwardCoordinator.savedBatchRequestId
    val internalContent = videoRequest != null || postRequest != null
    if (incomingShare == null && forwardRequest == null && videoRequest == null && postRequest == null && savedBatchRequestId == null) return

    val viewModel: ShareDestinationViewModel = hiltViewModel()
    val knownDestinations by viewModel.destinations.collectAsStateWithLifecycle()
    val people by viewModel.videoPeople.collectAsStateWithLifecycle()
    val destinations = if (!internalContent) knownDestinations else (knownDestinations + people).distinctBy { it.key }
    val observedActionState by viewModel.actionState.collectAsStateWithLifecycle()
    val observedRecovery by viewModel.recoveryState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val maximumDialogHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.9f
    val activeKey = postRequest?.requestId ?: videoRequest?.requestId ?: forwardRequest?.requestId ?: savedBatchRequestId ?: incomingShareRequestId ?: incomingShare?.id.orEmpty()
    val actionState = observedActionState.forRequest(activeKey)
    val recovery = observedRecovery.takeIf { it.requestId == activeKey }
    var query by rememberSaveable(activeKey) { mutableStateOf("") }
    var selectedKeys by rememberSaveable(activeKey) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedPeople by remember(activeKey) { mutableStateOf<Map<String, ShareDestination>>(emptyMap()) }
    var caption by rememberSaveable(activeKey) { mutableStateOf(if (internalContent) "" else incomingShare?.text ?: incomingShare?.subject.orEmpty()) }

    var closing by remember(activeKey) { mutableStateOf(false) }
    suspend fun close(): Boolean {
        if (closing) return false
        closing = true
        try {
        if (!viewModel.finishRequest(activeKey)) return false
        val dismissal = if (forwardRequest != null || internalContent || savedBatchRequestId != null) {
            forwardCoordinator.dismiss(activeKey)
        } else {
            if (forwardCoordinator.activeRequestId != null) ShareDismissal.IGNORED else onIncomingShareConsumed()
        }
        if (dismissal.consumed) onFinished()
        return dismissal.mayNavigate
        } finally { closing = false }
    }

    LaunchedEffect(activeKey) {
        viewModel.bindRequest(activeKey)
    }

    LaunchedEffect(query, activeKey) {
        if (internalContent) viewModel.searchVideoPeople(query, activeKey)
    }

    LaunchedEffect(activeKey, actionState) {
        val completed = actionState as? ShareActionState.Completed
        val queued = actionState as? ShareActionState.Queued
        if (completed == null && queued == null) return@LaunchedEffect
        scope.launch {
            if (!close()) return@launch
            completed?.let { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() }
            (queued?.destination ?: completed?.openDestination)?.let(onOpenConversation)
        }
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
    val isRunning = closing || actionState is ShareActionState.Running || actionState is ShareActionState.Queued
    val recovering = (recovery == null || recovery.mode == ShareRecoveryState.Mode.LOADING) && actionState !is ShareActionState.Failed
    val frozen = recovery?.mode == ShareRecoveryState.Mode.PENDING
    val retryPending = frozen && !isRunning
    val allowEditing = !isRunning && !recovering && recovery?.mode == ShareRecoveryState.Mode.EDITABLE
    val textError = if (forwardRequest == null) sharedTextError(caption, internalContent) else null
    val canShareContent = internalContent || forwardRequest != null ||
        incomingShare?.let { it.items.isNotEmpty() || caption.isNotBlank() } == true
    val preview = remember(incomingShare, forwardRequest, videoRequest, postRequest) {
        when {
            postRequest != null -> postRequest.post.content.take(100).ifBlank { "Publicación de Novvin" }
            videoRequest != null -> videoRequest.video.title.ifBlank { "Video de Novvin" }
            forwardRequest != null -> {
                val count = forwardRequest.candidates.size
                "$count mensaje${if (count == 1) "" else "s"} para reenviar"
            }
            incomingShare != null -> incomingShare.previewLabel()
            else -> ""
        }
    }

    Dialog(
        onDismissRequest = { if (!isRunning) scope.launch { close() } },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = maximumDialogHeight),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
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
                    IconButton(onClick = { scope.launch { close() } }, enabled = !isRunning) {
                        Icon(Icons.Default.Close, contentDescription = "Cerrar")
                    }
                }

                if (recovering) Text("Comprobando los envíos anteriores…", modifier = Modifier.padding(vertical = 12.dp))
                if (frozen) {
                    Text("Se conservaron ${recovery!!.plan.size} envíos; ${recovery.accepted} ya aceptados. Continuar usa los destinos y el contenido originales.",
                        modifier = Modifier.padding(vertical = 10.dp))
                    recovery.plan.map { it.destination }.distinctBy { it.key }.forEach {
                        Text("${it.title} · ${it.subtitle ?: it.type.sectionTitle}", style = MaterialTheme.typography.bodySmall)
                    }
                    recovery.plan.firstOrNull()?.content?.let { content ->
                        Text(when (content) {
                            is ShareOutgoingContent.Text -> content.content
                            is ShareOutgoingContent.Attachment -> "${content.item.displayName ?: "Archivo"}\n${content.content}"
                            is ShareOutgoingContent.Forward -> content.source.preview
                        }, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                recovery?.message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
                if (recovery?.mode == ShareRecoveryState.Mode.FAILED) Button(onClick = { viewModel.recoverRequest(activeKey) }) {
                    Text("Comprobar de nuevo")
                }
                if (allowEditing && (incomingShare != null || internalContent)) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = caption,
                        onValueChange = { caption = it },
                        label = { Text(if (incomingShare?.isTextOnly == true) "Texto para compartir" else "Comentario (opcional)") },
                        modifier = Modifier.fillMaxWidth().testTag("shared_attachment_caption"),
                        maxLines = 3,
                        enabled = allowEditing,
                        isError = textError != null,
                        supportingText = textError?.let { message -> { Text(message) } },
                    )
                }
                if (allowEditing) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Buscar en todo") },
                    placeholder = { Text("Chats, grupos o comunidades") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    enabled = allowEditing
                )

                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
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
                                ShareDestinationRow(destination, checked, allowEditing) { shouldSelect ->
                                    selectedKeys = if (shouldSelect) selectedKeys + destination.key else selectedKeys - destination.key
                                    selectedPeople = if (shouldSelect) selectedPeople + (destination.key to destination) else selectedPeople - destination.key
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
                }

                (actionState as? ShareActionState.Failed)?.let { failed ->
                    Text(
                        text = failed.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (incomingShare != null && incomingShare.failedItemCount > 0) {
                    Text(if (incomingShare.hasContent) "No se pudieron leer ${incomingShare.failedItemCount} archivo(s). Puedes compartir los demás o volver a adjuntarlos."
                        else "No se pudieron preparar los archivos. Cierra Compartir y vuelve a adjuntarlos.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                }

                if (internalContent && selectedKeys.any { it.startsWith("chat:person:") }) Text(
                    "Si aún no han conversado, enviarás una solicitud de mensaje. Solo puedes enviar uno hasta que te respondan.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        if (retryPending) {
                            viewModel.retryPending(activeKey)
                        } else if (postRequest != null) {
                            viewModel.shareInternalPost(postRequest, selectedKeys.mapNotNull { key ->
                                selectedPeople[key] ?: destinations.firstOrNull { it.key == key }
                            }, caption)
                        } else if (videoRequest != null) {
                            viewModel.shareInternalVideo(videoRequest, selectedKeys.mapNotNull { key ->
                                selectedPeople[key] ?: destinations.firstOrNull { it.key == key }
                            }, caption)
                        } else if (forwardRequest != null) {
                            viewModel.forwardMessages(forwardRequest.requestId, forwardRequest.candidates, selectedKeys)
                        } else if (incomingShare != null) {
                            viewModel.sendIncomingShare(activeKey, incomingShare, selectedKeys, caption)
                        }
                    },
                    enabled = (retryPending || allowEditing && canShareContent && selectedKeys.isNotEmpty() && textError == null) && !isRunning,
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
                    Text(if (retryPending) "Reintentar pendientes" else if (forwardRequest != null) "Reenviar" else "Compartir")
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
    if (items.isEmpty()) return text?.trim()?.take(80) ?: subject?.trim()?.take(80) ?: "No se pudo leer lo compartido"
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

@Composable
fun ShareDestinationRow(destination: ShareDestination, selected: Boolean, enabled: Boolean = true, onSelected: (Boolean) -> Unit) {
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onSelected(!selected) }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(destination.type.sectionIcon, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(destination.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(destination.subtitle ?: destination.type.sectionTitle, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Checkbox(selected, onCheckedChange = onSelected, enabled = enabled)
        }
    }
}
