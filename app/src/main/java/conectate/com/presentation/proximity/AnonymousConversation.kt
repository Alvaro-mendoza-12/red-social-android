package conectate.com.presentation.proximity

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import conectate.com.data.localfallback.AnonymousChatMessage
import conectate.com.data.localfallback.AnonymousChatSession
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.rememberInAppMediaPicker
import conectate.com.presentation.documents.rememberInAppDocumentPicker
import conectate.com.presentation.messages.StickerPickerDialog

/** The keyboard resizes the whole conversation, never the text field alone. */
@Composable
internal fun AnonymousConversation(
    session: AnonymousChatSession,
    messages: List<AnonymousChatMessage>,
    onSend: (String) -> Unit,
    onSendSticker: (String?, String) -> Unit,
    onSendMedia: (Uri, String) -> Unit,
    onEnd: () -> Unit,
    onBack: () -> Unit,
    normalChatPeerId: String?,
    onOpenProfile: (String) -> Unit,
    onOpenNormalChat: (String) -> Unit,
) {
    var draft by rememberSaveable(session.id) { mutableStateOf("") }
    var showStickerPicker by remember(session.id) { mutableStateOf(false) }
    var showAttachments by remember(session.id) { mutableStateOf(false) }
    var showDetails by rememberSaveable(session.id) { mutableStateOf(false) }
    var confirmEnd by rememberSaveable(session.id) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imagePicker = rememberInAppMediaPicker { uri -> uri?.let { onSendMedia(it, "image") } }
    val audioPicker = rememberInAppDocumentPicker { uri -> uri?.let { onSendMedia(it, "audio") } }
    val listState = rememberLazyListState()
    LaunchedEffect(session.id, messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }
    val unlockStatus = when {
        session.messageCount >= 12 -> "Chat normal desbloqueado"
        anonymousMediaUnlocked(session.messageCount, "image") -> "Fotos desbloqueadas"
        anonymousMediaUnlocked(session.messageCount, "sticker") -> "Stickers desbloqueados"
        anonymousMediaUnlocked(session.messageCount, "audio") -> "Audios desbloqueados"
        else -> "Conversación anónima"
    }
    if (showStickerPicker) StickerPickerDialog(
        onDismiss = { showStickerPicker = false },
        onEmojiSelected = { emoji ->
            onSend(emoji)
            showStickerPicker = false
        },
        onStickerSelected = { sticker ->
            onSendSticker(sticker.media_url, sticker.title.ifBlank { "Sticker" })
            showStickerPicker = false
        },
    )
    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text("¿Terminar esta conversación?") },
        text = { Text("Se cerrará para ambos. Para conservarla y volver después, usa la flecha de volver.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; onEnd() }) { Text("Terminar") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Conservar chat") } },
    )
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text("Sobre esta conversación") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Confianza: ${session.friendshipPercent}% · Identidad: ${session.peerRevealLevel}/3")
                LinearProgressIndicator(progress = { session.friendshipPercent / 100f }, modifier = Modifier.fillMaxWidth())
                Text(unlockStatus)
                if (session.messageCount < 12) Text("Sigan conversando y compartiendo identidad para desbloquear opciones.")
                val interests = (session.peer.interests + session.peer.hobbies).distinct().take(6)
                if (interests.isNotEmpty()) Text(interests.joinToString(" · "))
                session.peer.userId?.let { userId ->
                    OutlinedButton(onClick = { showDetails = false; onOpenProfile(userId) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Ver perfil compartido")
                    }
                }
                normalChatPeerId?.let { userId ->
                    Button(onClick = { showDetails = false; onOpenNormalChat(userId) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Continuar en chats")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Cerrar") } },
    )
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver a chats anónimos")
            }
            if (session.peer.avatarUrl != null) {
                CircularProfileImage(ownerId = session.peer.userId, imageUrl = session.peer.avatarUrl, size = 36.dp, showBorder = true)
            } else {
                Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(session.peer.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(unlockStatus, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { keyboard?.hide(); showDetails = true }) {
                Icon(Icons.Default.Info, contentDescription = "Detalles de la conversación")
            }
            IconButton(onClick = { keyboard?.hide(); confirmEnd = true }) {
                Icon(Icons.Default.Cancel, contentDescription = "Finalizar chat")
            }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages.asReversed(), key = { it.id.ifBlank { it.clientMessageId } }) { message -> AnonymousMessageBubble(message) }
        }
        Surface(tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (anonymousMediaUnlocked(session.messageCount, "audio")) Box {
                    IconButton(onClick = { showAttachments = true }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Adjuntar")
                    }
                    DropdownMenu(expanded = showAttachments, onDismissRequest = { showAttachments = false }) {
                        DropdownMenuItem(text = { Text("Audio") }, leadingIcon = { Icon(Icons.Default.Audiotrack, null) },
                            onClick = { showAttachments = false; audioPicker.launch("audio/*") })
                        if (anonymousMediaUnlocked(session.messageCount, "sticker")) DropdownMenuItem(
                            text = { Text("Sticker") }, leadingIcon = { Icon(Icons.Default.AttachFile, null) },
                            onClick = { showAttachments = false; showStickerPicker = true })
                        if (anonymousMediaUnlocked(session.messageCount, "image")) DropdownMenuItem(
                            text = { Text("Foto") }, leadingIcon = { Icon(Icons.Default.Image, null) },
                            onClick = { showAttachments = false; imagePicker.launch("image/*") })
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(1000) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                    placeholder = { Text("Mensaje") },
                    minLines = 1,
                    maxLines = 4,
                )
                IconButton(enabled = draft.isNotBlank(), onClick = {
                    val content = draft.trim()
                    if (content.isNotEmpty()) { draft = ""; onSend(content) }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}
