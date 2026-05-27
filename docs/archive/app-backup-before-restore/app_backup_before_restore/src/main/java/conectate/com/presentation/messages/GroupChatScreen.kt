package conectate.com.presentation.messages

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.components.MessageBubble
import conectate.com.presentation.messages.VoiceState
import conectate.com.presentation.components.FullScreenImageDialog
import conectate.com.presentation.components.MediaItem
import conectate.com.util.Constants
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import conectate.com.util.ChatContactPayload
import conectate.com.presentation.messages.stickers.StickerButton
import conectate.com.presentation.messages.stickers.StickerPanel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupChatScreen(
    navController: NavController,
    groupId: String,
    focusMessageId: String? = null,
    viewModel: GroupChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentUserId = viewModel.getCurrentUserId()
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Voice recording states
    val voiceState by viewModel.voiceState.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val voicePlaybackProgress by viewModel.voicePlaybackProgress.collectAsState()
    val voicePlaybackDurationLabel by viewModel.voicePlaybackDurationLabel.collectAsState()
    
    // Reply/Edit states
    val replyToMessage by viewModel.replyToMessage.collectAsState()
    val editingMessage by viewModel.editingMessage.collectAsState()
    val currentPlayingAudioId by viewModel.currentPlayingAudioId.collectAsState()
    val audioProgress by viewModel.audioProgress.collectAsState()
    val audioDurationLabel by viewModel.audioDurationLabel.collectAsState()
    val selectedMessage by viewModel.selectedMessage.collectAsState()
    val reactionPickerMessage by viewModel.reactionPickerMessage.collectAsState()
    val forwardPickerMessage by viewModel.forwardPickerMessage.collectAsState()
    val forwardTargets by viewModel.forwardTargets.collectAsState()
    val isForwarding by viewModel.isForwarding.collectAsState()
    val pollStates by viewModel.pollStates.collectAsState()
    val isCreatingPoll by viewModel.isCreatingPoll.collectAsState()
    val messageInfoTarget by viewModel.messageInfoTarget.collectAsState()
    val messageInfoReceipts by viewModel.messageInfoReceipts.collectAsState()
    val isLoadingMessageInfo by viewModel.isLoadingMessageInfo.collectAsState()
    val pollOptionVoters by viewModel.pollOptionVoters.collectAsState()
    var focusResolved by remember(focusMessageId) { mutableStateOf(focusMessageId.isNullOrBlank()) }
    var focusAttempts by remember(focusMessageId) { mutableStateOf(0) }

    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showPollDialog by remember { mutableStateOf(false) }
    var showStickerCatalogDialog by remember { mutableStateOf(false) }
    var selectedForwardTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingMediaUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingMediaType by remember { mutableStateOf<String?>(null) }
    var pendingMediaCaption by remember { mutableStateOf("") }

    // FULL SCREEN MEDIA VIEWER STATE
    val messages = (uiState as? GroupChatUiState.Success)?.messages ?: emptyList()
    val visibleMessageIds by remember(messages, listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .mapNotNull { item -> messages.getOrNull(item.index)?.id }
                .toSet()
        }
    }
    val mediaMessages = remember(messages) {
        messages.filter { (it.content_type == "image" || it.content_type == "video" || it.content_type == "gif" || it.content_type == "sticker") && !it.deleted_for_all }
    }
    val fullScreenMediaItems = remember(mediaMessages) {
        mediaMessages.map { msg ->
            MediaItem(
                id = msg.id,
                url = msg.media_url ?: "",
                type = msg.content_type
            )
        }
    }
    var fullScreenImageInitialIndex by remember { mutableStateOf<Int?>(null) }
    var oneTimeMediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    LaunchedEffect(visibleMessageIds, messages.size) {
        viewModel.onVisibleMessageIdsChanged(visibleMessageIds)
    }

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

    // Media Launchers
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingMediaUri = uri
            pendingMediaType = "image"
            pendingMediaCaption = ""
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingMediaUri = uri
            pendingMediaType = "video"
            pendingMediaCaption = ""
        }
    }

    val viewOnceImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingMediaUri = uri
            pendingMediaType = "view_once_image"
            pendingMediaCaption = ""
        }
    }

    val viewOnceVideoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingMediaUri = uri
            pendingMediaType = "view_once_video"
            pendingMediaCaption = ""
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.sendMediaMessage(it, context) }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.sendMediaMessage(it, context, forcedMessageType = "document", customContent = "📎 Archivo") }
    }

    val gifPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.sendMediaMessage(it, context, forcedMessageType = "gif", customContent = "🌀 GIF") }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri: Uri? ->
        if (contactUri == null) return@rememberLauncherForActivityResult
        val payload = resolvePickedContact(context, contactUri)
        if (payload == null) {
            scope.launch {
                snackbarHostState.showSnackbar("No se pudo leer el contacto seleccionado.")
            }
        } else {
            viewModel.sendContactMessage(
                name = payload.name,
                phone = payload.phone,
                email = payload.email,
                phones = payload.phones,
                emails = payload.emails
            )
        }
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            contactPickerLauncher.launch(null)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Permiso de contactos denegado.")
            }
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

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.startRecording()
    }

    DisposableEffect(lifecycleOwner, groupId) {
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

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage.collect { msg ->
            msg?.let { snackbarHostState.showSnackbar(it) }
        }
    }

    LaunchedEffect(groupId) {
        viewModel.init(groupId)
    }

    LaunchedEffect(forwardPickerMessage?.id) {
        selectedForwardTargets = emptySet()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            val state = uiState as? GroupChatUiState.Success
            val membersSubtitle = remember(state?.memberIds, state?.memberProfiles, currentUserId) {
                val successState = state ?: return@remember null
                val me = currentUserId.trim().lowercase(Locale.ROOT)
                val displayNames = successState.memberIds.mapNotNull { memberId ->
                    val normalizedId = memberId.trim().lowercase(Locale.ROOT)
                    val profile = successState.memberProfiles[memberId]
                        ?: successState.memberProfiles[normalizedId]
                        ?: successState.memberProfiles.entries.firstOrNull {
                            it.key.trim().lowercase(Locale.ROOT) == normalizedId
                        }?.value
                    when {
                        normalizedId == me -> "Tú"
                        !profile?.displayName.isNullOrBlank() -> profile?.displayName
                        !profile?.username.isNullOrBlank() -> profile?.username
                        !profile?.full_name.isNullOrBlank() -> profile?.full_name
                        !profile?.email.isNullOrBlank() -> profile?.email
                        else -> null
                    }
                }.distinct()

                if (displayNames.isEmpty()) {
                    null
                } else {
                    val visible = displayNames.take(4)
                    val hiddenCount = displayNames.size - visible.size
                    if (hiddenCount > 0) "${visible.joinToString(", ")} +$hiddenCount"
                    else visible.joinToString(", ")
                }
            }
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            navController.navigate(conectate.com.presentation.navigation.Screen.GroupDetail.createRoute(groupId))
                        }
                    ) {
                        state?.group?.image?.let {
                            CircularProfileImage(imageUrl = it, size = 32.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Column {
                            Text(state?.group?.name ?: "Grupo")
                            membersSubtitle?.let { subtitle ->
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when (val state = uiState) {
                    is GroupChatUiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is GroupChatUiState.Success -> {
                        val memberProfiles = state.memberProfiles
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
                        ) {
                            itemsIndexed(state.messages, key = { _, item -> item.id }) { index, groupMsg ->
                                val previousIso = state.messages.getOrNull(index - 1)?.created_at
                                if (shouldShowDateSeparator(groupMsg.created_at, previousIso)) {
                                    DateSeparator(label = formatDateSeparatorLabel(groupMsg.created_at))
                                }
                                val msg = conectate.com.data.model.Message(
                                    id = groupMsg.id,
                                    chat_id = groupMsg.group_id,
                                    sender_id = groupMsg.sender_id,
                                    content = groupMsg.content,
                                    content_type = groupMsg.content_type,
                                    media_url = groupMsg.media_url,
                                    poll_id = groupMsg.poll_id,
                                    created_at = groupMsg.created_at,
                                    status = groupMsg.status,
                                    reply_to_id = groupMsg.reply_to_id,
                                    is_edited = groupMsg.is_edited,
                                    deleted_for_all = groupMsg.deleted_for_all,
                                    deleted_by = groupMsg.deleted_by,
                                    reactions = groupMsg.reactions,
                                    is_forwarded = groupMsg.is_forwarded,
                                    forwarded_from_message_id = groupMsg.forwarded_from_message_id,
                                    forwarded_from_user_id = groupMsg.forwarded_from_user_id,
                                    sender_name = groupMsg.sender_name,
                                    reply_to = groupMsg.reply_to?.let { quoted ->
                                        conectate.com.data.model.Message(
                                            id = quoted.id,
                                            chat_id = quoted.group_id,
                                            sender_id = quoted.sender_id,
                                            content = quoted.content,
                                            content_type = quoted.content_type,
                                            media_url = quoted.media_url,
                                            created_at = quoted.created_at,
                                            deleted_for_all = quoted.deleted_for_all,
                                            reactions = quoted.reactions,
                                            is_forwarded = quoted.is_forwarded,
                                            forwarded_from_message_id = quoted.forwarded_from_message_id,
                                            forwarded_from_user_id = quoted.forwarded_from_user_id
                                        )
                                    }
                                )
                                val isFromMe = msg.sender_id == currentUserId
                                val memberProfile = memberProfiles[msg.sender_id]
                                val pollId = msg.poll_id
                                if (msg.content_type == "poll" && !pollId.isNullOrBlank()) {
                                    PollMessageCard(
                                        message = msg,
                                        pollState = pollStates[pollId],
                                        isFromCurrentUser = isFromMe,
                                        onVote = { optionId, selected ->
                                            viewModel.votePollOption(pollId, optionId, selected)
                                        },
                                        onOptionDetails = { option ->
                                            viewModel.openPollOptionVoters(pollId, option)
                                        }
                                    )
                                } else {
                                    MessageBubble(
                                        message = msg,
                                        isFromCurrentUser = isFromMe,
                                        senderName = if (!isFromMe) memberProfile?.username ?: msg.sender_name else null,
                                        senderEmail = memberProfile?.email,
                                        isVerified = memberProfile?.is_verified ?: false,
                                        userBadges = memberProfile?.badges ?: emptyList(),
                                        isPlaying = currentPlayingAudioId == msg.id,
                                        progress = if (currentPlayingAudioId == msg.id) audioProgress else 0f,
                                        duration = if (currentPlayingAudioId == msg.id) audioDurationLabel else (msg.content.ifBlank { "0:00" }),
                                        onPlayAudio = { viewModel.playAudio(it) },
                                        onLongClick = { viewModel.onMessageLongClick(msg) },
                                        onResolveUrl = { viewModel.getPlayableUrl(it) },
                                        onClick = { clickedUrl ->
                                            if ((msg.content_type == "view_once_image" || msg.content_type == "view_once_video") && msg.media_url != null) {
                                                scope.launch {
                                                    val oneTimeUrl = viewModel.consumeViewOnceMessage(msg)
                                                    if (oneTimeUrl == null) {
                                                        snackbarHostState.showSnackbar("Este contenido ya fue visto o no está disponible.")
                                                    } else {
                                                        oneTimeMediaItems = listOf(
                                                            MediaItem(
                                                                id = msg.id,
                                                                url = oneTimeUrl,
                                                                type = if (msg.content_type == "view_once_video") "video" else "image"
                                                            )
                                                        )
                                                        fullScreenImageInitialIndex = 0
                                                    }
                                                }
                                            } else if (clickedUrl != null) {
                                                val index = fullScreenMediaItems.indexOfFirst { it.url == clickedUrl || it.url == msg.media_url }
                                                if (index != -1) {
                                                    fullScreenImageInitialIndex = index
                                                }
                                            }
                                        },
                                        onSwipeReply = { viewModel.onSwipeToReply(it) },
                                        onSenderClick = { userId ->
                                            navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId))
                                        },
                                        currentUserId = currentUserId,
                                        onReactionClick = { emoji ->
                                            viewModel.reactToMessage(msg, emoji)
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
                        
                        if (state.messages.isNotEmpty()) {
                            LaunchedEffect(state.messages.size, focusMessageId) {
                                if (!focusResolved && !focusMessageId.isNullOrBlank()) {
                                    val index = state.messages.indexOfFirst {
                                        it.id.equals(focusMessageId, ignoreCase = true)
                                    }
                                    if (index >= 0) {
                                        listState.animateScrollToItem(index)
                                        focusResolved = true
                                        return@LaunchedEffect
                                    }

                                    focusAttempts += 1
                                    if (focusAttempts < 3) return@LaunchedEffect
                                    focusResolved = true
                                }
                                listState.animateScrollToItem(state.messages.size - 1)
                            }
                        }
                    }
                    is GroupChatUiState.Error -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Input Area
            val successState = uiState as? GroupChatUiState.Success
            val isMember by viewModel.isMember.collectAsState()
            
            if (successState != null && isMember) {
                val myRole by viewModel.myRole.collectAsState()
                val canTalk = !successState.group.only_admins_can_talk || 
                             myRole == "admin" || myRole == "owner" || 
                             successState.group.creator_id == currentUserId

                if (canTalk) {
                    // Reply Preview
                    replyToMessage?.let { reply ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.width(4.dp).height(40.dp).background(MaterialTheme.colorScheme.primary))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Respondiendo a", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = if (reply.deleted_for_all) "Mensaje eliminado" else reply.content,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                IconButton(onClick = { viewModel.cancelReply() }) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    // Edit Indicator
                    editingMessage?.let {
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Editando mensaje", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                IconButton(onClick = { viewModel.cancelEdit() }) {
                                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }

                    if (voiceState != VoiceState.IDLE) {
                        // --- RECORDING / REVIEW UI ---
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(8.dp)
                                .height(64.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                .padding(horizontal = 8.dp)
                        ) {
                            IconButton(onClick = { viewModel.cancelRecording() }) {
                                Icon(Icons.Default.Delete, "Cancelar", tint = MaterialTheme.colorScheme.error)
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                when (voiceState) {
                                    VoiceState.RECORDING -> {
                                         val minutes = recordingDuration / 1000 / 60
                                         val seconds = (recordingDuration / 1000) % 60
                                         val timeString = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                                         Row(verticalAlignment = Alignment.CenterVertically) {
                                             Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                             Spacer(modifier = Modifier.width(4.dp))
                                             Text("Grabando... $timeString", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                         }
                                    }
                                    VoiceState.PAUSED -> Text("Pausado", style = MaterialTheme.typography.bodyMedium)
                                    VoiceState.REVIEWING -> {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            IconButton(onClick = { 
                                                if (viewModel.voiceMessageManagerIsPreviewPlaying()) viewModel.pauseDraftPreview() 
                                                else viewModel.playDraftPreview()
                                            }) {
                                                Icon(if (viewModel.voiceMessageManagerIsPreviewPlaying()) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                            Slider(value = voicePlaybackProgress, onValueChange = { viewModel.seekDraftPreview(it) }, modifier = Modifier.weight(1f))
                                            Text(voicePlaybackDurationLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }
                                    else -> {}
                                }
                            }
                            when (voiceState) {
                                VoiceState.RECORDING -> {
                                    IconButton(onClick = { viewModel.pauseRecording() }) {
                                        Icon(Icons.Default.Pause, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                    FloatingActionButton(onClick = { viewModel.stopRecording(true) }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp), elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)) {
                                        Icon(Icons.Default.Send, null, modifier = Modifier.size(20.dp))
                                    }
                                }
                                VoiceState.PAUSED -> {
                                    IconButton(onClick = { viewModel.resumeRecording() }) {
                                        Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { viewModel.stopRecording(false) }) {
                                        Icon(Icons.Default.Stop, null, tint = MaterialTheme.colorScheme.secondary)
                                    }
                                }
                                VoiceState.REVIEWING -> {
                                    FloatingActionButton(onClick = { viewModel.sendCurrentRecording() }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp), elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)) {
                                        Icon(Icons.Default.Send, null, modifier = Modifier.size(20.dp))
                                    }
                                }
                                else -> {}
                            }
                        }
                    } else {
                        // STANDARD INPUT
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp)
                        ) {
                            IconButton(onClick = { showAttachmentSheet = true }) {
                                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            StickerButton(onClick = { showStickerCatalogDialog = true })
                            TextField(
                                value = messageText,
                                onValueChange = { viewModel.updateMessageText(it) },
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                enabled = true,
                                placeholder = { Text("Mensaje") },
                                maxLines = 4,
                                shape = CircleShape,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { if (messageText.isNotBlank()) { if (editingMessage != null) viewModel.confirmEditMessage() else viewModel.sendMessage() } }),
                                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                                trailingIcon = {
                                    IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                                        Icon(Icons.Default.CameraAlt, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            FloatingActionButton(
                                onClick = {
                                    if (messageText.isNotBlank()) {
                                        if (editingMessage != null) viewModel.confirmEditMessage()
                                        else viewModel.sendMessage()
                                    } else {
                                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            viewModel.startRecording()
                                        } else {
                                            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                        }
                                    }
                                },
                                modifier = Modifier.size(48.dp),
                                containerColor = if (voiceState != VoiceState.IDLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                shape = CircleShape,
                                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp)
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                } else {
                                    Icon(imageVector = if (messageText.isNotBlank()) Icons.Default.Send else Icons.Default.Mic, contentDescription = null)
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 1.dp
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Solo los administradores pueden escribir", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else if (successState != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                    color = MaterialTheme.colorScheme.errorContainer,
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Ya no eres parte de este grupo", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.deleteGroupLocally { navController.popBackStack() } },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Borrar grupo de mi lista")
                        }
                    }
                }
            }
        }
    }

    if (fullScreenImageInitialIndex != null) {
        val activeMediaItems = if (oneTimeMediaItems.isNotEmpty()) oneTimeMediaItems else fullScreenMediaItems
        FullScreenImageDialog(
            mediaItems = activeMediaItems,
            initialIndex = fullScreenImageInitialIndex!!,
            onDismiss = {
                fullScreenImageInitialIndex = null
                oneTimeMediaItems = emptyList()
            },
            onResolveUrl = { viewModel.getPlayableUrl(it) }
        )
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
                
                // Copy Option
                if (selectedMessage?.content_type == "text" && selectedMessage?.deleted_for_all != true) {
                    Row(modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            selectedMessage?.content?.let { viewModel.copyToClipboard(context, it) }
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
                                selectedMessage?.let { viewModel.openForwardPicker(it) }
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
                        viewModel.onSwipeToReply(selectedMessage!!)
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
                                viewModel.openMessageInfo(selectedMessage!!)
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
                                label = { Text(emoji) }
                            )
                        }
                    }
                }
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
                    if (forwardTargets.isEmpty()) {
                        Text("No hay grupos disponibles para reenviar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(forwardTargets, key = { it.groupId }) { target ->
                                val checked = selectedForwardTargets.contains(target.groupId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable {
                                            selectedForwardTargets = if (checked) {
                                                selectedForwardTargets - target.groupId
                                            } else {
                                                selectedForwardTargets + target.groupId
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            selectedForwardTargets = if (isChecked) {
                                                selectedForwardTargets + target.groupId
                                            } else {
                                                selectedForwardTargets - target.groupId
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(target.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedForwardTargets.isNotEmpty() && !isForwarding,
                    onClick = { viewModel.forwardMessageToSelectedGroups(selectedForwardTargets) }
                ) {
                    if (isForwarding) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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

    if (pendingMediaUri != null && pendingMediaType != null) {
        MediaCaptionPreviewDialog(
            mediaUri = pendingMediaUri!!,
            mediaType = pendingMediaType!!,
            caption = pendingMediaCaption,
            onCaptionChange = { pendingMediaCaption = it },
            onDismiss = {
                pendingMediaUri = null
                pendingMediaType = null
                pendingMediaCaption = ""
            },
            onConfirm = { editedUri, editedCaption ->
                val type = pendingMediaType ?: return@MediaCaptionPreviewDialog
                viewModel.sendMediaMessage(
                    uri = editedUri,
                    context = context,
                    forcedMessageType = type,
                    customContent = editedCaption.trim().ifBlank { null }
                )
                pendingMediaUri = null
                pendingMediaType = null
                pendingMediaCaption = ""
            },
            isSending = isSending
        )
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = "Compartir", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().clickable { showAttachmentSheet = false; imagePickerLauncher.launch("image/*") }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Imagen")
                }
                Row(modifier = Modifier.fillMaxWidth().clickable { showAttachmentSheet = false; videoPickerLauncher.launch("video/*") }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Video")
                }
                Row(modifier = Modifier.fillMaxWidth().clickable { showAttachmentSheet = false; audioPickerLauncher.launch("audio/*") }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Audiotrack, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Audio")
                }
                Row(modifier = Modifier.fillMaxWidth().clickable { showAttachmentSheet = false; documentPickerLauncher.launch("*/*") }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Documento")
                }
                Row(modifier = Modifier.fillMaxWidth().clickable { showAttachmentSheet = false; gifPickerLauncher.launch("image/gif") }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GifBox, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("GIF")
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
                    Icon(Icons.Default.EmojiEmotions, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Sticker")
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
                    Icon(Icons.Default.Collections, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Sticker Pro (packs)")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            viewOnceImagePickerLauncher.launch("image/*")
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Foto ver una vez")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            viewOnceVideoPickerLauncher.launch("video/*")
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Visibility, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Video ver una vez")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            if (androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.READ_CONTACTS
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                contactPickerLauncher.launch(null)
                            } else {
                                contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Contacto")
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
                    Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Ubicación")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showAttachmentSheet = false
                            showPollDialog = true
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Poll, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Encuesta")
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showStickerCatalogDialog) {
        StickerPanel(
            visible = showStickerCatalogDialog,
            onDismiss = { showStickerCatalogDialog = false },
            snackbarHostState = snackbarHostState,
            onStickerSelected = { sticker ->
                if (!sticker.media_url.isNullOrBlank()) {
                    viewModel.sendStickerFromUrl(sticker.media_url, sticker.title.ifBlank { "🧩 Sticker" })
                } else {
                    viewModel.sendStickerEmoji(sticker.title.ifBlank { "🧩" })
                }
            }
        )
    }

    if (showPollDialog) {
        PollComposerDialog(
            isSending = isCreatingPoll,
            onDismiss = { showPollDialog = false },
            onCreatePoll = { question, options, allowsMultiple ->
                viewModel.createPoll(question, options, allowsMultiple)
                showPollDialog = false
            }
        )
    }

    messageInfoTarget?.let { infoMessage ->
        MessageReadInfoDialog(
            message = infoMessage,
            receipts = messageInfoReceipts,
            isLoading = isLoadingMessageInfo,
            onDismiss = { viewModel.closeMessageInfo() }
        )
    }

    pollOptionVoters?.let { details ->
        PollOptionVotersDialog(
            title = details.optionText,
            voteCount = details.voteCount,
            voters = details.voters,
            isLoading = details.isLoading,
            onDismiss = { viewModel.dismissPollOptionVoters() }
        )
    }
}
