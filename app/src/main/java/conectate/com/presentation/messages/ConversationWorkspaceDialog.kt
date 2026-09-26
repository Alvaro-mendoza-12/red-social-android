package conectate.com.presentation.messages

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import conectate.com.data.model.Message

@Composable
internal fun ConversationWorkspaceDialog(
    type: String,
    conversationId: String,
    channelId: String? = null,
    composerText: String,
    initialKind: ChatWorkspaceKind,
    source: Message?,
    onDismiss: () -> Unit,
    onLoadDraft: (String) -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val workspace: ChatWorkspaceViewModel = hiltViewModel(key = "workspace-$type-$conversationId-${channelId.orEmpty()}")
    val state by workspace.state.collectAsStateWithLifecycle()
    LaunchedEffect(type, conversationId, channelId) { workspace.bindConversation(type, conversationId, channelId) }
    key(type, conversationId, channelId, source?.id, initialKind) {
        ChatWorkspaceSheet(
            state = state,
            initialKind = initialKind,
            initialSource = source,
            composerText = composerText,
            onDismiss = onDismiss,
            onSave = workspace::add,
            onToggleTask = workspace::toggleTask,
            onRemove = workspace::remove,
            onLoadDraft = onLoadDraft,
            onOpenSource = onOpenSource,
            onRetry = workspace::load,
            onSchedule = workspace::scheduleTask,
        )
    }
}

@Composable
internal fun WorkspaceCaptureActions(message: Message?, accountId: String, onCapture: (ChatWorkspaceKind, Message) -> Unit) {
    if (message == null || !canSaveMessageInWorkspace(message, accountId)) return
    Row(Modifier.fillMaxWidth()) {
        TextButton(onClick = { onCapture(ChatWorkspaceKind.TASK, message) }) { Text("Crear pendiente") }
        TextButton(onClick = { onCapture(ChatWorkspaceKind.CONTEXT, message) }) { Text("Guardar contexto") }
    }
}
