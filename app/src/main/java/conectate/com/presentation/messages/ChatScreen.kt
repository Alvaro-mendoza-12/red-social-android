package conectate.com.presentation.messages

import conectate.com.presentation.messages.OutgoingMessageMedia
import androidx.compose.runtime.Composable

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import conectate.com.data.model.Message
import conectate.com.data.model.ScheduledMessageJob
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.supabase.SupabaseConfig
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.AdminBadge
import conectate.com.presentation.components.TypingIndicatorMinimal
import conectate.com.presentation.components.isUserAdmin
import conectate.com.presentation.components.MessageBubble
import conectate.com.presentation.components.ImageMessageBubble
import conectate.com.presentation.components.VideoMessageBubble
import conectate.com.presentation.components.DeviceAudioPickerDialog
import conectate.com.util.Constants
import androidx.compose.material3.AlertDialog
import java.util.Locale
import java.time.LocalDateTime
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import conectate.com.presentation.components.FullScreenImageDialog
import conectate.com.presentation.components.MediaItem
import conectate.com.presentation.messages.VoiceState
import conectate.com.util.TimeUtils
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import conectate.com.util.ChatContactPayload
import conectate.com.presentation.messages.stickers.StickerButton
import conectate.com.presentation.messages.stickers.StickerPanel

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun ChatScreen(
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel(),
    initialMessage: String? = null,
    focusMessageId: String? = null
) {
    val chatState by viewModel.chatState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        viewModel.onScreenVisibilityChanged(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onScreenVisibilityChanged(true)
                Lifecycle.Event.ON_STOP -> viewModel.onScreenVisibilityChanged(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenVisibilityChanged(false)
        }
    }
    
    // Handle initial message if provided
    LaunchedEffect(initialMessage) {
        if (!initialMessage.isNullOrBlank()) {
            viewModel.updateMessageText(initialMessage)
        }
    }

    val messageText by viewModel.messageText.collectAsState()
    val otherUserReactive by viewModel.otherUserMetadata.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedMessages by viewModel.selectedMessages.collectAsState()
    val isBlocked by viewModel.isBlocked.collectAsState()
    val isBlockedByMe by viewModel.isBlockedByMe.collectAsState()
    val isBlockedByOther by viewModel.isBlockedByOther.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }
    var messageForInfo by remember { mutableStateOf<Message?>(null) }
    val reactionPickerMessage by viewModel.reactionPickerMessage.collectAsState()
    val forwardPickerMessage by viewModel.forwardPickerMessage.collectAsState()
    val forwardTargets by viewModel.forwardTargets.collectAsState()
    val forwardUserSearchQuery by viewModel.forwardUserSearchQuery.collectAsState()
    val forwardUserTargets by viewModel.forwardUserTargets.collectAsState()
    val isForwarding by viewModel.isForwarding.collectAsState()
    var selectedForwardTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedForwardUsers by remember { mutableStateOf<Set<String>>(emptySet()) }
    
    val currentUserId = viewModel.getCurrentUserId() ?: ""
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    var showStorageDisabledDialog by remember { mutableStateOf(false) }
    
    val mediaPickerViewModel: conectate.com.presentation.components.MediaPickerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val galleryMedia by mediaPickerViewModel.galleryMedia.collectAsState()
    var showUnifiedMediaPicker by remember { mutableStateOf(false) }

    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showRecentGifSheet by remember { mutableStateOf(false) }
    var showStickerCatalogDialog by remember { mutableStateOf(false) }
    // Full screen media state
    var fullScreenMediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var fullScreenImageInitialIndex by remember { mutableStateOf(0) }
    var isViewingOnceMedia by remember { mutableStateOf(false) }
    
    val voiceState by viewModel.voiceState.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val voicePlaybackProgress by viewModel.voicePlaybackProgress.collectAsState()
    val voicePlaybackDurationLabel by viewModel.voicePlaybackDurationLabel.collectAsState()
    
    // Audio Playback State
    // Audio Playback State
    val currentPlayingAudioId by viewModel.currentPlayingAudioId.collectAsState()
    val audioProgress by viewModel.audioProgress.collectAsState()
    val audioDurationLabel by viewModel.audioDurationLabel.collectAsState()
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val universalForwardCoordinator = LocalUniversalForwardCoordinator.current

    fun sendCurrentLocation() {
        val location = getBestLastKnownLocation(context)
        if (location == null) {
            scope.launch {
                snackbarHostState.showSnackbar("No se pudo obtener tu ubicación actual.")
            }
            return
        }
        viewModel.sendLocationMessage(
            latitude = formatCoordinate(location.latitude),
            longitude = formatCoordinate(location.longitude),
            label = "Ubicación actual"
        )
    }
    
    val showNicknameDialog by viewModel.showNicknameDialog.collectAsState()
    val currentNickname by viewModel.currentNickname.collectAsState()
    val showClearChatDialog by viewModel.showClearChatDialog.collectAsState()
    val advancedMessage by viewModel.advancedMessage.collectAsState()
    val chatSummary by viewModel.chatSummary.collectAsState()
    val scheduledMessages by viewModel.scheduledMessages.collectAsState()
    val autoTranslateEnabled by viewModel.autoTranslateEnabled.collectAsState()
    val autoTranslateTargetLang by viewModel.autoTranslateTargetLang.collectAsState()
    val chatBackgroundSettings by viewModel.chatBackgroundSettings.collectAsState()

    var showScheduleDialog by remember { mutableStateOf(false) }
    var showScheduledMessagesDialog by remember { mutableStateOf(false) }
    var showTranslateDialog by remember { mutableStateOf(false) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var scheduledImageToCrop by remember { mutableStateOf<Uri?>(null) }
    var backgroundImageToCrop by remember { mutableStateOf<Uri?>(null) }
    val scheduleDefaults = remember {
        val nowPlus = LocalDateTime.now().plusMinutes(5)
        nowPlus.toLocalDate().toString() to nowPlus.toLocalTime().withSecond(0).withNano(0).toString()
    }
    var scheduleDateText by remember { mutableStateOf(scheduleDefaults.first) }
    var scheduleTimeText by remember { mutableStateOf(scheduleDefaults.second.take(5)) }
    var scheduleMessageText by remember { mutableStateOf("") }
    var scheduleSelfDestructText by remember { mutableStateOf("") }
    var scheduleTranslateLang by remember { mutableStateOf("") }
    var scheduledMediaUri by remember { mutableStateOf<Uri?>(null) }
    var scheduledMediaType by remember { mutableStateOf<String?>(null) }
    var translateTargetDraft by remember { mutableStateOf(autoTranslateTargetLang) }
    
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
             viewModel.startRecording()
        }
    }
    
    var showMediaComposer by remember { mutableStateOf(false) }
    var showAudioPickerDialog by remember { mutableStateOf(false) }
    var pendingAudioPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDocumentUri by remember { mutableStateOf<Uri?>(null) }
    var pendingContactPayload by remember { mutableStateOf<DeviceContactPayload?>(null) }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            pendingAudioPreviewUri = uri
            showAudioPickerDialog = true
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        pendingDocumentUri = uri
    }

    val gifPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { viewModel.sendGifMessage(it) }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = PickPhoneContactContract()
    ) { contactUri: Uri? ->
        if (contactUri == null) return@rememberLauncherForActivityResult
        val payload = resolvePickedContact(context, contactUri)
        if (payload == null) {
            scope.launch {
                snackbarHostState.showSnackbar("No se pudo leer el contacto seleccionado.")
            }
        } else {
            pendingContactPayload = payload
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            sendCurrentLocation()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Permiso de ubicación denegado.")
            }
        }
    }

    val scheduleImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        scheduledImageToCrop = uri
    }

    val scheduleVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scheduledMediaUri = uri
            scheduledMediaType = "video"
        }
    }

    val backgroundImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        backgroundImageToCrop = uri
    }

    scheduledImageToCrop?.let { sourceUri ->
        conectate.com.presentation.components.SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Recortar imagen programada",
            initialPreset = conectate.com.presentation.components.ImageCropPreset.ORIGINAL,
            onDismiss = { scheduledImageToCrop = null },
            onCropped = { croppedUri ->
                scheduledMediaUri = croppedUri
                scheduledMediaType = "image"
                scheduledImageToCrop = null
            }
        )
    }

    backgroundImageToCrop?.let { sourceUri ->
        conectate.com.presentation.components.SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Ajustar fondo del chat",
            initialPreset = conectate.com.presentation.components.ImageCropPreset.ORIGINAL,
            onDismiss = { backgroundImageToCrop = null },
            onCropped = { croppedUri ->
                viewModel.setChatBackground("custom", croppedUri.toString())
                backgroundImageToCrop = null
            }
        )
    }
    
    // Message Actions State
    val messages = (chatState as? ChatUiState.Success)?.messages ?: emptyList()
    val recentGifUrls = remember(messages) {
        messages.asReversed()
            .filter { it.content_type == "gif" }
            .mapNotNull { it.media_url?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .take(24)
    }
    val visibleMessageIds by remember(messages, listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { item -> messages.getOrNull(item.index)?.id }
                .toSet()
        }
    }
    var focusResolved by remember(focusMessageId) { mutableStateOf(focusMessageId.isNullOrBlank()) }
    val messageIdentity = remember(messages) { messages.map { it.id } }

    LaunchedEffect(visibleMessageIds, messages.size) {
        viewModel.onVisibleMessageIdsChanged(visibleMessageIds)
    }
    
    // Auto-scroll to newest message (Bottom)
    LaunchedEffect(messageIdentity, focusMessageId) {
        if (messages.isEmpty()) return@LaunchedEffect

        if (!focusResolved && !focusMessageId.isNullOrBlank()) {
            val targetIndex = messages.indexOfFirst { msg ->
                msg.id.equals(focusMessageId, ignoreCase = true)
            }
            if (targetIndex >= 0) {
                listState.scrollToItem(targetIndex)
                focusResolved = true
                return@LaunchedEffect
            }

        }

        listState.scrollToItem(messages.size - 1)
    }

    val imeBottom = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty() &&
            listState.firstVisibleItemIndex >= (messages.lastIndex - 3).coerceAtLeast(0)
        ) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    val selectedMessage by viewModel.selectedMessage.collectAsState()
    val replyToMessage by viewModel.replyToMessage.collectAsState()

    val editingMessage by viewModel.editingMessage.collectAsState()

    LaunchedEffect(autoTranslateTargetLang) {
        translateTargetDraft = autoTranslateTargetLang
    }

    LaunchedEffect(advancedMessage) {
        val msg = advancedMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearAdvancedMessage()
    }

    LaunchedEffect(forwardPickerMessage?.id) {
        selectedForwardTargets = emptySet()
        selectedForwardUsers = emptySet()
    }

    if (selectedMessage != null) {
        ModalBottomSheet(onDismissRequest = { viewModel.dismissMessageActions() }) {
            Column(modifier = Modifier.padding(16.dp)) {
                
                // Own message?
                if (selectedMessage?.sender_id == currentUserId) {
                    
                    // Edit (Only text, not deleted)
                    if (selectedMessage?.content_type == "text" && selectedMessage?.deleted_for_all != true) {
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                viewModel.onEditMessage(selectedMessage!!) 
                                viewModel.dismissMessageActions()
                            }
                            .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Editar")
                        }
                    }

                    // Delete for Me
                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            viewModel.deleteMessageForMe(selectedMessage!!) 
                            viewModel.dismissMessageActions()
                        }
                        .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Eliminar para mí")
                    }
                    
                    // Delete for Everyone (if not already deleted)
                    if (selectedMessage?.deleted_for_all != true) {
                        Row(modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                viewModel.deleteMessageForEveryone(selectedMessage!!) 
                                viewModel.dismissMessageActions()
                            }
                            .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             // WhatsApp style: Delete for everyone is usually red or distinct? 
                             // Keeping standard icon but distinct text.
                            Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text("Eliminar para todos", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    
                } else {
                    // Other User's Message
                     Row(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            viewModel.deleteMessageForMe(selectedMessage!!) 
                            viewModel.dismissMessageActions()
                        }
                        .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Eliminar para mí")
                    }
                }
                
                // Copy Option (Always available except if deleted maybe?)
                if (selectedMessage?.content_type == "text" && selectedMessage?.deleted_for_all != true) {
                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            selectedMessage?.content?.let { viewModel.copyToClipboard(it) }
                            viewModel.dismissMessageActions()
                        }
                        .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Copiar")
                    }
                }

                if (selectedMessage?.deleted_for_all != true) {
                    if (selectedMessage?.content_type == "sticker") {
                        val stickerIsFavorite = viewModel.isStickerFavorite(selectedMessage?.media_url)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedMessage?.let { viewModel.toggleStickerFavorite(it) }
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (stickerIsFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(if (stickerIsFavorite) "Eliminar de favoritos" else "Añadir a favoritos")
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedMessage?.let { viewModel.openReactionPicker(it) }
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.EmojiEmotions, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Reaccionar")
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedMessage?.let { message ->
                                    if (universalForwardCoordinator != null) {
                                        universalForwardCoordinator.open(
                                            candidates = listOf(message.toForwardMessagePayload()),
                                            initiallySelectedIds = setOf(message.id)
                                        )
                                        viewModel.dismissMessageActions()
                                    } else {
                                        viewModel.openForwardPicker(message)
                                    }
                                }
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Reenviar")
                    }
                }

                 // Reply Option
                 Row(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { 
                        viewModel.replyToMessage(selectedMessage!!)
                        viewModel.dismissMessageActions()
                    }
                    .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Responder")
                }

                if (selectedMessage?.sender_id == currentUserId) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                messageForInfo = selectedMessage
                                viewModel.dismissMessageActions()
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text("Información del mensaje")
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (messageForInfo != null) {
        val msg = messageForInfo!!
        val deliveredAt = when {
            msg.status == "read" -> msg.read_at ?: msg.created_at
            msg.status == "delivered" || msg.status == "received" -> msg.created_at
            else -> null
        }
        val readAt = if (msg.status == "read") msg.read_at else null

        AlertDialog(
            onDismissRequest = { messageForInfo = null },
            title = { Text("Información del mensaje") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Estado: ${msg.status.ifBlank { "sent" }}")
                    Text("Enviado: ${formatMessageInfoDate(msg.created_at)}")
                    Text("Entregado: ${formatMessageInfoDate(deliveredAt)}")
                    Text("Leído: ${formatMessageInfoDate(readAt)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { messageForInfo = null }) {
                    Text("Cerrar")
                }
            }
        )
    }

    if (reactionPickerMessage != null) {
        val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏", "🔥", "👏")
        ModalBottomSheet(onDismissRequest = { viewModel.dismissReactionPicker() }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Reaccionar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                quickReactions.chunked(4).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { emoji ->
                            AssistChip(
                                onClick = { reactionPickerMessage?.let { viewModel.reactToMessage(it, emoji) } },
                                label = { Text(emoji, fontSize = 18.sp) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (forwardPickerMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissForwardPicker() },
            title = { Text("Reenviar mensaje") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = forwardUserSearchQuery,
                        onValueChange = { viewModel.updateForwardUserSearchQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Buscar usuario para nuevo chat") },
                        singleLine = true
                    )

                    if (forwardTargets.isEmpty()) {
                        Text(
                            "No hay chats disponibles para reenviar.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(forwardTargets, key = { it.chatId }) { target ->
                                val isChecked = selectedForwardTargets.contains(target.chatId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable {
                                            selectedForwardTargets = if (isChecked) {
                                                selectedForwardTargets - target.chatId
                                            } else {
                                                selectedForwardTargets + target.chatId
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            selectedForwardTargets = if (checked) {
                                                selectedForwardTargets + target.chatId
                                            } else {
                                                selectedForwardTargets - target.chatId
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = target.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        target.subtitle?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (forwardUserSearchQuery.length >= 2) {
                        if (forwardUserTargets.isEmpty()) {
                            Text(
                                "Sin resultados de usuarios.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                "Usuarios (se creará chat al reenviar)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(forwardUserTargets, key = { it.userId }) { userTarget ->
                                    val isChecked = selectedForwardUsers.contains(userTarget.userId)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(MaterialTheme.shapes.medium)
                                            .clickable {
                                                selectedForwardUsers = if (isChecked) {
                                                    selectedForwardUsers - userTarget.userId
                                                } else {
                                                    selectedForwardUsers + userTarget.userId
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = isChecked,
                                            onCheckedChange = { checked ->
                                                selectedForwardUsers = if (checked) {
                                                    selectedForwardUsers + userTarget.userId
                                                } else {
                                                    selectedForwardUsers - userTarget.userId
                                                }
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = userTarget.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            userTarget.subtitle?.let {
                                                Text(
                                                    text = it,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = (selectedForwardTargets.isNotEmpty() || selectedForwardUsers.isNotEmpty()) && !isForwarding,
                    onClick = { viewModel.forwardMessageToSelectedChats(selectedForwardTargets, selectedForwardUsers) }
                ) {
                    if (isForwarding) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Reenviar")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissForwardPicker() }) {
                    Text("Cancelar")
                }
            }
        )
    }

    
    // Pickers are defined above

     Scaffold(
        snackbarHost = {
            conectate.com.presentation.common.KeyboardSafeSnackbarHost(snackbarHostState)
        },
        topBar = {
             if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedMessages.size} seleccionados") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                            Icon(Icons.Default.Close, "Cerrar selección")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showBulkDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, "Eliminar varios", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
             } else {
                 val successState = chatState as? ChatUiState.Success
                 val chat = successState?.chat
                 val otherUser = successState?.otherUser
                 
                
                  // Use chat metadata as primary fallback to avoid "Usuario" flicker
                  val chatName = if (chat?.is_group == true) chat.group_name ?: "Grupo" 
                                else (currentNickname ?: chat?.nickname ?: otherUser?.displayName ?: otherUser?.username ?: chat?.group_name ?: "Usuario")
                  
                  val chatImage = if (chat?.is_group == true) chat.group_image 
                                 else (otherUser?.avatar_url ?: chat?.group_image)

                TopAppBar(
                    title = { 
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.clickable(enabled = !chatImage.isNullOrBlank()) {
                                    navController.navigate(conectate.com.presentation.navigation.Screen.FullScreenImage.createRoute(chatImage!!))
                                }
                            ) {
                                conectate.com.presentation.components.CircularProfileImage(
                                    imageUrl = chatImage,
                                    size = 32.dp,
                                    isVerified = if (chat?.is_group == true) false else otherUser?.is_verified ?: false,
                                    fallbackName = chatName
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            // Name and Status
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (chat?.is_group != true && otherUser != null) {
                                            navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(otherUser.id))
                                        }
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = chatName, 
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (chat?.is_group != true && (otherUser?.is_admin == true || isUserAdmin(otherUser?.username, otherUser?.email))) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        AdminBadge(showVerifiedLine = false)
                                    }
                                    if (chat?.is_group != true && otherUser?.is_verified == true) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Verificado",
                                            tint = Color(0xFF1DA1F2),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                
                                // Presence Status
                                // PRIORIDAD: Flow Reactivo > State estático
                                val currentOtherUser = otherUserReactive ?: otherUser
                                
                                if (chat?.is_group != true && currentOtherUser != null) {
                                    val isActuallyOnline = currentOtherUser.is_online &&
                                        TimeUtils.isRecentlyOnline(currentOtherUser.lastSeen)
                                    val statusText = if (isActuallyOnline) {
                                        "Activo ahora"
                                    } else {
                                        TimeUtils.getRelativeTimeSpanString(currentOtherUser.lastSeen)
                                    }
                                    
                                    val statusColor = if (isActuallyOnline) {
                                        Color(0xFF4CAF50) // Green
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    }
                                    
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = if (isActuallyOnline) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = statusColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, "Volver")
                        }
                    },
                    actions = {
                        var showMenu by remember { mutableStateOf(false) }
                        var showReportDialog by remember { mutableStateOf(false) }

                        conectate.com.presentation.calls.CallActionButtons(
                            navController = navController,
                            title = chatName,
                            invitedUserIds = listOfNotNull(otherUser?.id),
                            contextType = "dm",
                            contextId = chat?.id
                        )

                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "Opciones")
                        }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                if (chat?.is_group != true) {
                                    DropdownMenuItem(
                                        text = { Text("Apodo") },
                                        onClick = { 
                                            showMenu = false
                                            viewModel.showNicknameDialog()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Vaciar chat") },
                                    onClick = { 
                                        showMenu = false
                                        viewModel.showClearChatDialog()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Programar mensaje") },
                                    onClick = {
                                        showMenu = false
                                        scheduleMessageText = messageText
                                        showScheduleDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (scheduledMessages.isEmpty()) {
                                                "Mensajes programados"
                                            } else {
                                                "Mensajes programados (${scheduledMessages.size})"
                                            }
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        viewModel.refreshScheduledMessages()
                                        showScheduledMessagesDialog = true
                                    }
                                )
                                if (viewModel.isSecureTranslationAvailable) {
                                    DropdownMenuItem(
                                        text = { Text("Auto traducción") },
                                        onClick = {
                                            showMenu = false
                                            showTranslateDialog = true
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Resumen del chat") },
                                    onClick = {
                                        showMenu = false
                                        viewModel.generateChatSummary()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Fondo") },
                                    onClick = {
                                        showMenu = false
                                        showBackgroundSheet = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isBlockedByMe) "Desbloquear" else "Bloquear") },
                                    onClick = {
                                        showMenu = false
                                        if (isBlockedByMe) viewModel.unblockUser() else viewModel.blockUser()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reportar") },
                                    onClick = { 
                                        showMenu = false
                                        showReportDialog = true
                                    }
                                )
                            }

                        if (showReportDialog) {
                            val otherUser = (chatState as? ChatUiState.Success)?.otherUser
                            val reportedUserId = otherUser?.id ?: ""
                            val reportedUserName = otherUser?.username ?: chatName
                            
                            conectate.com.presentation.components.ReportDialog(
                                reportedUserName = reportedUserName,
                                onDismiss = { showReportDialog = false },
                                onSubmit = { reason, description, evidenceUri ->
                                    viewModel.submitReport(reportedUserId, reportedUserName, reason, description, evidenceUri)
                                   // Confirmation is handled inside ReportDialog
                                }
                            )
                        }
                    }
                )
             }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Chat Content
            Box(modifier = Modifier.weight(1f)) {
                ChatBackgroundLayer(
                    backgroundType = chatBackgroundSettings.backgroundType,
                    backgroundUri = chatBackgroundSettings.backgroundUri,
                    modifier = Modifier.fillMaxSize()
                )
                when (val state = chatState) {
                    is ChatUiState.Success -> {
                        // REMOVED redundant listState and LaunchedEffect here

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            reverseLayout = false, // Standard: Top to Bottom, we scroll to end
                            contentPadding = PaddingValues(bottom = 8.dp, top = 8.dp) 
                        ) {
                            itemsIndexed(
                                items = state.messages,
                                key = { _, item -> item.id },
                                contentType = { _, item -> item.content_type }
                            ) { index, message ->
                                val mediaGroup = remember(state.messages, message.id) {
                                    conectate.com.presentation.components.mediaGridClusterAt(
                                        messages = state.messages,
                                        index = index
                                    )
                                }

                                if (mediaGroup == null) {
                                    // Skip, already rendered in previous group
                                } else if (mediaGroup.size > 1) {
                                    if (shouldShowDateSeparator(state.messages, index)) {
                                        DateSeparator(label = formatDateSeparatorLabel(message.created_at))
                                    }
                                    conectate.com.presentation.components.MediaGridMessageBubble(
                                        messages = mediaGroup,
                                        isFromCurrentUser = message.sender_id == currentUserId,
                                        onClick = { selectedMessage ->
                                            conectate.com.presentation.components.buildMessageMediaViewerSelection(
                                                messages = state.messages,
                                                selectedMessageId = selectedMessage.id,
                                                reverseOrder = true
                                            )?.let { selection ->
                                                isViewingOnceMedia = false
                                                fullScreenImageInitialIndex = selection.initialIndex
                                                fullScreenMediaItems = selection.items
                                            }
                                        },
                                        onResolveUrl = { viewModel.getPlayableUrl(it) }
                                    )
                                } else {
                                    if (shouldShowDateSeparator(state.messages, index)) {
                                        DateSeparator(label = formatDateSeparatorLabel(message.created_at))
                                    }
                                    val isSelected = selectedMessages.contains(message.id)
                                    MessageBubble(
                                        message = message,
                                        isFromCurrentUser = message.sender_id == currentUserId,
                                    isSelected = isSelected,
                                    onLongClick = {
                                        viewModel.onMessageLongClick(message)
                                    },
                                    onClick = { url ->
                                        if (isSelectionMode) {
                                            viewModel.toggleMessageSelection(message.id)
                                        } else if ((message.content_type == "view_once_image" || message.content_type == "view_once_video") && message.media_url != null) {
                                            scope.launch {
                                                val oneTimeUrl = viewModel.consumeViewOnceMessage(message)
                                                if (oneTimeUrl == null) {
                                                    snackbarHostState.showSnackbar("Este contenido ya fue visto o no está disponible.")
                                                } else {
                                                    isViewingOnceMedia = true
                                                    fullScreenMediaItems = listOf(
                                                        conectate.com.presentation.components.MediaItem(
                                                            message.id,
                                                            oneTimeUrl,
                                                            if (message.content_type == "view_once_video") "video" else "image"
                                                        )
                                                    )
                                                    fullScreenImageInitialIndex = 0
                                                }
                                            }
                                        } else if (conectate.com.presentation.components.isGalleryMediaType(message.content_type) && message.media_url != null) {
                                            val currentMessages = (chatState as? ChatUiState.Success)?.messages ?: emptyList()
                                            conectate.com.presentation.components.buildMessageMediaViewerSelection(
                                                messages = currentMessages,
                                                selectedMessageId = message.id,
                                                reverseOrder = true
                                            )?.let { selection ->
                                                isViewingOnceMedia = false
                                                fullScreenImageInitialIndex = selection.initialIndex
                                                fullScreenMediaItems = selection.items
                                            }
                                        }
                                    },
                                    onSwipeReply = { viewModel.onSwipeToReply(it) },
                                    onPlayAudio = { viewModel.playAudio(it) },
                                    onResolveUrl = { viewModel.getPlayableUrl(it) },
                                    onSenderClick = { userId ->
                                        navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId))
                                    },
                                    isPlaying = currentPlayingAudioId == message.id,
                                    progress = if (currentPlayingAudioId == message.id) audioProgress else 0f,
                                    duration = if (currentPlayingAudioId == message.id) audioDurationLabel else message.content,
                                     senderName = null,
                                    senderEmail = if (message.sender_id != currentUserId) {
                                        state.otherUser?.email 
                                    } else {
                                        viewModel.getCurrentUserEmail()
                                    },
                                    isVerified = if (message.sender_id == currentUserId) {
                                        state.currentUser?.is_verified ?: false
                                    } else {
                                        state.otherUser?.is_verified ?: false
                                    },
                                    userBadges = if (message.sender_id == currentUserId) {
                                        state.currentUserBadges
                                    } else {
                                        state.otherUser?.badges ?: emptyList()
                                    },
                                    onUrlClick = { url ->
                                        if (url.contains("conectate.com/post/")) {
                                            val postId = url.substringAfter("post/")
                                            navController.navigate(conectate.com.presentation.navigation.Screen.PostDetail.createRoute(postId))
                                        } else if (url.contains("conectate.com/video/")) {
                                            val videoId = url.substringAfter("video/")
                                            navController.navigate(conectate.com.presentation.navigation.Screen.VideoPlayer.createRoute(videoId))
                                        }
                                    },
                                    currentUserId = currentUserId,
                                    onReactionClick = { emoji ->
                                        viewModel.reactToMessage(message, emoji)
                                    },
                                    onOpenContactChat = { payload: ChatContactPayload ->
                                        scope.launch {
                                            val userId = viewModel.resolveRegisteredContactUserId(payload)
                                            if (userId.isNullOrBlank()) {
                                                snackbarHostState.showSnackbar("Este contacto aún no tiene la aplicación.")
                                            } else {
                                                navController.navigate(conectate.com.presentation.navigation.Screen.Chat.createRoute(userId))
                                            }
                                        }
                                    },
                                    onAddToDeviceContacts = { payload: ChatContactPayload ->
                                        val insertIntent = Intent(Intent.ACTION_INSERT).apply {
                                            type = ContactsContract.RawContacts.CONTENT_TYPE
                                            putExtra(ContactsContract.Intents.Insert.NAME, payload.normalizedName)
                                            payload.normalizedPhone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
                                            payload.normalizedEmail?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
                                        }
                                        runCatching { context.startActivity(insertIntent) }
                                            .onFailure {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar("No se pudo abrir contactos del sistema.")
                                                }
                                            }
                                    }
                                )
                            }
                        }
                    }
                    }
                    is ChatUiState.Error -> {
                         Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    else -> {
                       // Loading or Error state
                       Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                           androidx.compose.material3.CircularProgressIndicator()
                       }
                    }
                }
            }
            
            // Modern Input Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding() // Keep TextField visible when keyboard is shown
                    .padding(8.dp)
            ) {
                // Reply Preview
                replyToMessage?.let { reply ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Respondiendo a",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = when {
                                    reply.deleted_for_all -> "Mensaje eliminado"
                                    reply.isImage -> "📷 Imagen"
                                    reply.isVideo -> "📹 Video"
                                    reply.isAudio -> "🎤 Audio ${reply.content}"
                                    else -> reply.content
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { viewModel.cancelReply() }) {
                            Icon(Icons.Default.Close, "Cancelar", modifier = Modifier.size(20.dp))
                        }
                    }
                }
                
                // Edit Preview
                editingMessage?.let { editMsg ->
                     Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                         Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                         Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Editando mensaje",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = editMsg.content, // Show original content
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        IconButton(onClick = { viewModel.cancelEdit() }) {
                            Icon(Icons.Default.Close, "Cancelar", modifier = Modifier.size(20.dp))
                        }
                    }
                }



                if (isBlockedByOther) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "No puedes enviar mensajes a este usuario.",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (isBlockedByMe) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Usuario Bloqueado", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                            Button(onClick = { viewModel.unblockUser() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.padding(top = 8.dp)) {
                                Text("Desbloquear")
                            }
                        }
                    }
                } else if ((chatState as? ChatUiState.Success)?.isMember == false && (chatState as? ChatUiState.Success)?.chat?.is_group == true) {
                    // BANNER DE EXPULSIÓN (WhatsApp Style)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Ya no formas parte de este grupo",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Button(
                                onClick = { 
                                    viewModel.deleteChat()
                                    navController.popBackStack() 
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("Eliminar grupo de mi lista")
                            }
                        }
                    }
                } else {
                    // INPUT NORMAL (Solo se muestra si es miembro o chat privado)
                    if (voiceState != VoiceState.IDLE) {
                         // --- RECORDING / REVIEW UI ---
                         Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .padding(horizontal = 8.dp)
                        ) {
                            // Cancel / Delete
                            IconButton(onClick = { viewModel.cancelRecording() }) {
                                Icon(Icons.Default.Delete, "Cancelar", tint = MaterialTheme.colorScheme.error)
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                when (voiceState) {
                                    VoiceState.RECORDING -> {
                                         val minutes = recordingDuration / 1000 / 60
                                         val seconds = (recordingDuration / 1000) % 60
                                         val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                                         Row(verticalAlignment = Alignment.CenterVertically) {
                                             Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                             Spacer(modifier = Modifier.width(4.dp))
                                             Text(
                                                "Grabando... $timeString", 
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Bold
                                             )
                                         }
                                    }
                                    VoiceState.PAUSED -> {
                                         Text(
                                            "Pausado", 
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    VoiceState.REVIEWING -> {
                                        // Review Player
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            IconButton(onClick = { 
                                                if (viewModel.voiceMessageManagerIsPreviewPlaying()) viewModel.pauseDraftPreview() 
                                                else viewModel.playDraftPreview()
                                            }) {
                                                Icon(
                                                    if (viewModel.voiceMessageManagerIsPreviewPlaying()) Icons.Default.Pause else Icons.Default.PlayArrow, 
                                                    "Reproducir",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            
                                            Slider(
                                                value = voicePlaybackProgress,
                                                onValueChange = { viewModel.seekDraftPreview(it) },
                                                modifier = Modifier.weight(1f)
                                            )
                                            
                                            Text(
                                                voicePlaybackDurationLabel,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(start = 8.dp)
                                            )
                                        }
                                    }
                                    else -> {}
                                }
                            }
                            
                            // Actions
                            when (voiceState) {
                                VoiceState.RECORDING -> {
                                    IconButton(onClick = { viewModel.pauseRecording() }) {
                                        Icon(Icons.Default.Pause, "Pausar", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    // Lock/Send Button
                                    FloatingActionButton(
                                        onClick = { viewModel.stopRecording(true) },
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(40.dp),
                                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                                    ) {
                                        Icon(Icons.Default.Send, "Enviar", modifier = Modifier.size(20.dp))
                                    }
                                }
                                VoiceState.PAUSED -> {
                                    IconButton(onClick = { viewModel.resumeRecording() }) {
                                        Icon(Icons.Default.Mic, "Reanudar", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { viewModel.stopRecording(false) }) {
                                        Icon(Icons.Default.Stop, "Revisar", tint = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                                VoiceState.REVIEWING -> {
                                    FloatingActionButton(
                                        onClick = { viewModel.sendCurrentRecording() },
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(40.dp),
                                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                                    ) {
                                        Icon(Icons.Default.Send, "Enviar", modifier = Modifier.size(20.dp))
                                    }
                                }
                                else -> {}
                            }
                        }
                    } else {
                        // STANDARD INPUT
                        Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Attach Button
                        IconButton(
                            onClick = { showAttachmentSheet = true },
                            enabled = !isBlocked
                        ) {
                            Icon(Icons.Default.Add, "Adjuntar", tint = MaterialTheme.colorScheme.primary)
                        }

                        StickerButton(
                            onClick = { showStickerCatalogDialog = true },
                            enabled = !isBlocked
                        )
                     
                    // Input Field with mobile keyboard support
                    TextField(
                        value = messageText,
                        onValueChange = { viewModel.updateMessageText(it) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        enabled = voiceState == VoiceState.IDLE && !isBlocked,
                        placeholder = { 
                            if (voiceState != VoiceState.IDLE) {
                                Text("Grabando...", color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("Mensaje", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) 
                            }
                        },
                        maxLines = 4,
                        shape = CircleShape,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                            imeAction = androidx.compose.ui.text.input.ImeAction.Send
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSend = {
                                if (editingMessage != null) {
                                    viewModel.confirmEditMessage()
                                } else if (messageText.isNotBlank()) {
                                    viewModel.sendTextMessage()
                                }
                            }
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        trailingIcon = {
                             Row {
                                 IconButton(onClick = {
                                     showMediaComposer = true
                                 }) {
                                     Icon(Icons.Default.CameraAlt, "Cámara", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                 }
                             }
                        }
                    )
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    // Send / Mic Button
                    val isTextEmpty = messageText.isBlank()
                    
                     FloatingActionButton(
                        onClick = { 
                            if (isBlocked) return@FloatingActionButton
                            if (editingMessage != null) {
                                viewModel.confirmEditMessage()
                            } else if (!isTextEmpty && !isSending) {
                                viewModel.sendTextMessage()
                            } else {
                                if (voiceState != VoiceState.IDLE) {
                                    viewModel.stopRecording(true)
                                } else {
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                                            context, 
                                            android.Manifest.permission.RECORD_AUDIO
                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    ) {
                                        viewModel.startRecording()
                                    } else {
                                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            }
                        },
                        containerColor = if (voiceState != VoiceState.IDLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                    ) {
                         if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp), 
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                         } else {
                             // Icon logic: Check (Confirm Edit) OR Mic/Send
                             if (editingMessage != null) {
                                 Icon(Icons.Default.Check, "Confirmar edición")
                             } else {
                                 Icon(
                                    imageVector = if (isTextEmpty) {
                                        if (voiceState != VoiceState.IDLE) Icons.Default.Stop else Icons.Default.Mic
                                    } else Icons.Default.Send,
                                    contentDescription = if (isTextEmpty) "Grabar" else "Enviar"
                                 )
                             }
                         }
                     }
                }
              }
            }
        }
        }
    }

    if (showBackgroundSheet) {
        ChatBackgroundPickerSheet(
            onDismiss = { showBackgroundSheet = false },
            onSelectPattern = { type ->
                viewModel.setChatBackground(type)
                showBackgroundSheet = false
            },
            onSelectGallery = {
                showBackgroundSheet = false
                backgroundImagePickerLauncher.launch("image/*")
            },
            onClear = {
                viewModel.clearChatBackground()
                showBackgroundSheet = false
            }
        )
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachmentSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Compartir",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            showMediaComposer = true
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Fotos y videos", style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            pendingAudioPreviewUri = null
                            showAudioPickerDialog = true
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Audio (Archivo)", style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            documentPickerLauncher.launch("*/*")
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Documento", style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            showRecentGifSheet = true
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.GifBox, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("GIF", style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            showStickerCatalogDialog = true
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.EmojiEmotions, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Stickers y packs", style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            contactPickerLauncher.launch(Unit)
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Contacto", style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            if (hasLocationPermission(context)) {
                                sendCurrentLocation()
                            } else {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Ubicación", style = MaterialTheme.typography.bodyLarge)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            scheduleMessageText = messageText
                            showScheduleDialog = true
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Programar texto", style = MaterialTheme.typography.bodyLarge)
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showRecentGifSheet) {
        RecentGifPickerSheet(
            recentUrls = recentGifUrls,
            onSelect = { url -> showRecentGifSheet = false; viewModel.sendRecentGif(url) },
            onChooseFromDevice = { showRecentGifSheet = false; gifPickerLauncher.launch("image/gif") },
            onDismiss = { showRecentGifSheet = false }
        )
    }

    if (showStickerCatalogDialog) {
        StickerPanel(
            visible = showStickerCatalogDialog,
            onDismiss = { showStickerCatalogDialog = false },
            snackbarHostState = snackbarHostState,
            onStickerSelected = { sticker ->
                if (!sticker.media_url.isNullOrBlank()) {
                    viewModel.sendStickerFromUrl(sticker.media_url)
                } else {
                    viewModel.sendStickerText(sticker.title.ifBlank { "🧩 Sticker" })
                }
            }
        )
    }

    if (showScheduleDialog) {
        AlertDialog(
            onDismissRequest = { showScheduleDialog = false },
            title = { Text("Programar mensaje") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = scheduleMessageText,
                        onValueChange = { scheduleMessageText = it.take(4000) },
                        label = { Text(if (scheduledMediaType == null) "Mensaje" else "Texto o caption") },
                        placeholder = { Text("Escribe lo que se enviará") },
                        minLines = 3,
                        maxLines = 5
                    )
                    OutlinedTextField(
                        value = scheduleDateText,
                        onValueChange = { scheduleDateText = it.trim().take(10) },
                        label = { Text("Fecha (YYYY-MM-DD)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = scheduleTimeText,
                        onValueChange = { scheduleTimeText = it.trim().take(5) },
                        label = { Text("Hora (HH:MM)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = scheduleSelfDestructText,
                        onValueChange = { scheduleSelfDestructText = it.filter(Char::isDigit).take(6) },
                        label = { Text("Autodestrucción segundos (opcional)") },
                        singleLine = true
                    )
                    if (viewModel.isSecureTranslationAvailable) {
                        OutlinedTextField(
                            value = scheduleTranslateLang,
                            onValueChange = { scheduleTranslateLang = it.trim().lowercase().take(12) },
                            label = { Text("Traducir a (opcional, ej: en)") },
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scheduleImagePickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Icon(Icons.Default.Image, "Imagen")
                            Spacer(Modifier.width(8.dp))
                            Text("Imagen")
                        }
                        OutlinedButton(onClick = { scheduleVideoPickerLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) {
                            Icon(Icons.Default.Videocam, "Video")
                            Spacer(Modifier.width(8.dp))
                            Text("Video")
                        }
                    }
                    if (scheduledMediaType != null) {
                        Text(
                            text = "Multimedia seleccionada: ${if (scheduledMediaType == "video") "video" else "imagen"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = {
                                scheduledMediaUri = null
                                scheduledMediaType = null
                            }
                        ) {
                            Text("Quitar multimedia")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val selfDestruct = scheduleSelfDestructText.toIntOrNull()
                    val translate = if (viewModel.isSecureTranslationAvailable) {
                        scheduleTranslateLang.trim().ifBlank { null }
                    } else {
                        null
                    }
                    val mediaUri = scheduledMediaUri
                    val mediaType = scheduledMediaType
                    if (mediaUri != null && mediaType != null) {
                        viewModel.scheduleMediaMessageAt(
                            mediaUri = mediaUri,
                            messageType = mediaType,
                            content = scheduleMessageText,
                            dateIso = scheduleDateText,
                            time24h = scheduleTimeText,
                            selfDestructSeconds = selfDestruct,
                            translateTo = translate
                        )
                    } else {
                        viewModel.scheduleCurrentTextMessageAt(
                            content = scheduleMessageText,
                            dateIso = scheduleDateText,
                            time24h = scheduleTimeText,
                            selfDestructSeconds = selfDestruct,
                            translateTo = translate
                        )
                    }
                    scheduledMediaUri = null
                    scheduledMediaType = null
                    scheduleMessageText = ""
                    showScheduleDialog = false
                }) {
                    Text("Programar")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showScheduleDialog = false
                    scheduledMediaUri = null
                    scheduledMediaType = null
                    scheduleMessageText = ""
                }) { Text("Cancelar") }
            }
        )
    }

    if (showScheduledMessagesDialog) {
        ScheduledMessagesDialog(
            jobs = scheduledMessages,
            onCancel = { viewModel.cancelScheduledMessage(it) },
            onDismiss = { showScheduledMessagesDialog = false }
        )
    }

    if (showTranslateDialog) {
        AlertDialog(
            onDismissRequest = { showTranslateDialog = false },
            title = { Text("Auto traducción") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Activar")
                        Switch(
                            checked = autoTranslateEnabled,
                            onCheckedChange = { checked ->
                                viewModel.setAutoTranslation(checked, translateTargetDraft)
                            }
                        )
                    }
                    OutlinedTextField(
                        value = translateTargetDraft,
                        onValueChange = { translateTargetDraft = it.trim().lowercase().take(8) },
                        label = { Text("Idioma destino") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setAutoTranslation(autoTranslateEnabled, translateTargetDraft)
                    showTranslateDialog = false
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showTranslateDialog = false }) { Text("Cerrar") }
            }
        )
    }

    if (chatSummary != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissChatSummary() },
            title = { Text("Resumen del chat") },
            text = { Text(chatSummary?.summary ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissChatSummary() }) {
                    Text("Cerrar")
                }
            }
        )
    }
    
    // ... Existing dialog code ...
    if (showStorageDisabledDialog) {
        AlertDialog(
            onDismissRequest = { showStorageDisabledDialog = false },
            title = { Text("Permiso denegado") },
            text = { Text("Se requiere permiso de almacenamiento para enviar archivos.") },
            confirmButton = {
                TextButton(onClick = { showStorageDisabledDialog = false }) {
                    Text("OK")
                }
            }
        )
    }

    // Confirm Delete Dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Eliminar mensaje") },
            text = { Text("¿Deseas eliminar este mensaje?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.selectedMessage.value?.let { viewModel.deleteMessageForMe(it) }
                }) { Text("Eliminar para mí") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancelar") }
            }
        )
    }

    // Bulk Delete Dialog
    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text("Eliminar mensajes") },
            text = { Text("¿Deseas eliminar los ${selectedMessages.size} mensajes seleccionados?") },
            confirmButton = {
                TextButton(onClick = {
                    showBulkDeleteDialog = false
                    viewModel.deleteSelectedMessages(forEveryone = false)
                }) { Text("Eliminar para mí") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteDialog = false }) { Text("Cancelar") }
            }
        )
    }
    
    // Full-screen media dialog
    if (fullScreenMediaItems.isNotEmpty()) {
        FullScreenImageDialog(
            mediaItems = fullScreenMediaItems,
            initialIndex = fullScreenImageInitialIndex,
            onResolveUrl = { path -> viewModel.getPlayableUrl(path) },
            secureContent = isViewingOnceMedia,
            onDownload = if (isViewingOnceMedia) null else { item ->
                scope.launch {
                    val resolvedUrl = viewModel.getPlayableUrl(item.url) ?: item.url
                    if (resolvedUrl.startsWith("http")) {
                        val request = android.app.DownloadManager.Request(Uri.parse(resolvedUrl))
                            .setTitle("Descargando archivo")
                            .setDescription("Descargando ${item.type}...")
                            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setAllowedOverMetered(true)
                            .setAllowedOverRoaming(true)
                        
                        // Guess filename
                        val filename = "Novvin_${System.currentTimeMillis()}.${if (item.type == "video") "mp4" else "jpg"}"
                        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, filename)
                        
                        val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                        downloadManager.enqueue(request)
                        
                        android.widget.Toast.makeText(context, "Descarga iniciada", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(context, "Error: Enlace de descarga no válido", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = {
                fullScreenMediaItems = emptyList()
                isViewingOnceMedia = false
            }
    )
}

    if (showAudioPickerDialog) {
        DeviceAudioPickerDialog(
            initialUri = pendingAudioPreviewUri,
            title = "Elegir música o audio",
            confirmLabel = "Listo y enviar",
            maxClipDurationMs = null,
            allowFullTrack = true,
            defaultToFullTrack = true,
            onBrowseFiles = { audioPickerLauncher.launch("audio/*") },
            onDismiss = {
                showAudioPickerDialog = false
                pendingAudioPreviewUri = null
            },
            onClipReady = { clip ->
                viewModel.sendMediaMessage(clip.uri, "audio")
                showAudioPickerDialog = false
                pendingAudioPreviewUri = null
            }
        )
    }

    pendingDocumentUri?.let { documentUri ->
        conectate.com.presentation.components.DocumentSharePreviewDialog(
            uri = documentUri,
            onDismiss = { pendingDocumentUri = null },
            onConfirm = {
                viewModel.sendDocumentMessage(documentUri)
                pendingDocumentUri = null
            },
        )
    }

    pendingContactPayload?.let { contact ->
        conectate.com.presentation.components.ContactSharePreviewDialog(
            name = contact.name,
            phones = contact.phones.ifEmpty { listOf(contact.phone) },
            emails = contact.emails.ifEmpty { listOfNotNull(contact.email) },
            onDismiss = { pendingContactPayload = null },
            onConfirm = {
                viewModel.sendContactMessage(
                    name = contact.name,
                    phone = contact.phone,
                    email = contact.email,
                    phones = contact.phones,
                    emails = contact.emails,
                )
                pendingContactPayload = null
            },
        )
    }

    if (showMediaComposer) {
        MessageMediaComposer(
            isSending = false,
            onDismiss = { showMediaComposer = false },
            onSend = { mediaList, mediaCaption ->
                showMediaComposer = false
                viewModel.sendMediaBatch(mediaList, mediaCaption)
            }
        )
    }

    if (showNicknameDialog) {
        var nicknameText by remember { mutableStateOf(currentNickname ?: "") }
        AlertDialog(
            onDismissRequest = { viewModel.dismissNicknameDialog() },
            title = { Text("Establecer apodo") },
            text = {
                Column {
                    Text("Los apodos son privados y solo tú los puedes ver.", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nicknameText,
                        onValueChange = { nicknameText = it },
                        placeholder = { Text("Apodo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setNickname(nicknameText) }) {
                    Text("Guardar")
                }
            },
            dismissButton = {
                Row {
                    if (!currentNickname.isNullOrBlank()) {
                        TextButton(onClick = { 
                            viewModel.setNickname(null)
                            viewModel.dismissNicknameDialog()
                        }) {
                            Text("Eliminar", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { viewModel.dismissNicknameDialog() }) {
                        Text("Cancelar")
                    }
                }
            }
        )
    }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearChatDialog() },
            title = { Text("¿Vaciar chat?") },
            text = { Text("Se eliminarán todos los mensajes de este chat. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmClearChat() }) {
                    Text("Vaciar chat", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissClearChatDialog() }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun ScheduledMessagesDialog(
    jobs: List<ScheduledMessageJob>,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mensajes programados") },
        text = {
            if (jobs.isEmpty()) {
                Text("No hay mensajes programados en este chat.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(jobs, key = { it.id }) { job ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = formatScheduledJobTitle(job),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = formatScheduledJobTime(job.scheduled_for),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (job.content.isNotBlank()) {
                                Text(
                                    text = job.content.take(180),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { onCancel(job.id) }) {
                                    Text("Cancelar", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}

private fun formatScheduledJobTitle(job: ScheduledMessageJob): String {
    val type = when (job.content_type.trim().lowercase(Locale.getDefault())) {
        "image" -> "Imagen"
        "video" -> "Video"
        "audio" -> "Audio"
        else -> "Texto"
    }
    val status = when (job.status.trim().lowercase(Locale.getDefault())) {
        "failed" -> "fallido"
        "pending" -> "pendiente"
        else -> job.status.trim().ifBlank { "pendiente" }
    }
    return "$type $status"
}

private fun formatScheduledJobTime(value: String): String {
    if (value.isBlank()) return "Sin fecha"
    return try {
        val instant = java.time.Instant.parse(value.trim())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        "Para ${instant.atZone(java.time.ZoneId.systemDefault()).format(formatter)}"
    } catch (e: Exception) {
        "Para ${value.take(16).replace("T", " ")}"
    }
}

private fun formatMessageInfoDate(value: String?): String {
    if (value.isNullOrBlank()) return "Pendiente"
    return try {
        val cleaned = value.trim().replace(" ", "T")
        val instant = java.time.Instant.parse(
            if (cleaned.endsWith("Z") || cleaned.contains("+")) cleaned else "${cleaned}Z"
        )
        val formatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
        instant.atZone(java.time.ZoneId.systemDefault()).format(formatter)
    } catch (e: Exception) {
        value.take(16).replace("T", " ")
    }
}
