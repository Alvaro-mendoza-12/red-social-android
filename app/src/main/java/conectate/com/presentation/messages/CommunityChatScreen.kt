package conectate.com.presentation.messages

import conectate.com.presentation.messages.OutgoingMessageMedia
import androidx.compose.runtime.Composable

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
import conectate.com.presentation.components.DeviceAudioPickerDialog
import conectate.com.data.model.CommunityChannel
import conectate.com.util.Constants
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import conectate.com.util.ChatContactPayload
import conectate.com.presentation.messages.stickers.StickerButton
import conectate.com.presentation.messages.stickers.StickerPanel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CommunityChatScreen(
    navController: NavController,
    communityId: String,
    focusMessageId: String? = null,
    viewModel: CommunityChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val universalForwardCoordinator = LocalUniversalForwardCoordinator.current
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
    val chatBackgroundSettings by viewModel.chatBackgroundSettings.collectAsState()
    val channels by viewModel.channels.collectAsState()
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    var focusResolved by remember(focusMessageId) { mutableStateOf(focusMessageId.isNullOrBlank()) }

    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showRecentGifSheet by remember { mutableStateOf(false) }
    var showPollDialog by remember { mutableStateOf(false) }
    var showStickerCatalogDialog by remember { mutableStateOf(false) }
    var selectedForwardTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    val mediaPickerViewModel: conectate.com.presentation.components.MediaPickerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val galleryMedia by mediaPickerViewModel.galleryMedia.collectAsState()
    var showUnifiedMediaPicker by remember { mutableStateOf(false) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showCreateChannelDialog by remember { mutableStateOf(false) }
    var showChannelActionsDialog by remember { mutableStateOf(false) }
    var showPauseChannelDialog by remember { mutableStateOf(false) }
    var backgroundImageToCrop by remember { mutableStateOf<android.net.Uri?>(null) }
    val backgroundPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        backgroundImageToCrop = uri
    }

    backgroundImageToCrop?.let { sourceUri ->
        conectate.com.presentation.components.SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Ajustar fondo de la comunidad",
            initialPreset = conectate.com.presentation.components.ImageCropPreset.ORIGINAL,
            onDismiss = { backgroundImageToCrop = null },
            onCropped = { croppedUri ->
                viewModel.setChatBackground("custom", croppedUri.toString())
                backgroundImageToCrop = null
            }
        )
    }

    // FULL SCREEN MEDIA VIEWER STATE
    val allMessages = (uiState as? CommunityChatUiState.Success)?.messages ?: emptyList()
    val messages = remember(allMessages, selectedChannel?.id, selectedChannel?.isDefault) {
        val channel = selectedChannel
        when {
            channel == null -> allMessages
            channel.isDefault -> allMessages.filter {
                it.channel_id.isNullOrBlank() || it.channel_id.equals(channel.id, ignoreCase = true)
            }
            else -> allMessages.filter { it.channel_id.equals(channel.id, ignoreCase = true) }
        }
    }
    val chatMessages = remember(messages) { messages.map { it.asChatMessage() } }
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
    val mediaMessages = remember(messages) {
        messages.filter {
            conectate.com.presentation.components.isGalleryMediaType(it.content_type) &&
                !it.media_url.isNullOrBlank() &&
                !it.deleted_for_all
        }
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
    var showMediaComposer by remember { mutableStateOf(false) }
    var showAudioPickerDialog by remember { mutableStateOf(false) }
    var pendingAudioPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDocumentUri by remember { mutableStateOf<Uri?>(null) }
    var pendingContactPayload by remember { mutableStateOf<DeviceContactPayload?>(null) }
    var composerInitialUris by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }
    var composerInitialFilter by remember { mutableStateOf("all") }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingAudioPreviewUri = uri
            showAudioPickerDialog = true
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        pendingDocumentUri = uri
    }

    val gifPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.sendMediaMessage(it, context, forcedMessageType = "gif", customContent = "🌀 GIF") }
    }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = PickPhoneContactContract()
    ) { contactUri: android.net.Uri? ->
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

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.startRecording()
    }

    DisposableEffect(lifecycleOwner, communityId) {
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

    LaunchedEffect(viewModel.errorMessage) {
        viewModel.errorMessage.collect { msg ->
            msg?.let { snackbarHostState.showSnackbar(it) }
        }
    }

    LaunchedEffect(communityId) {
        viewModel.init(communityId)
    }

    LaunchedEffect(forwardPickerMessage?.id) {
        selectedForwardTargets = emptySet()
    }

    Scaffold(
        snackbarHost = {
            conectate.com.presentation.common.KeyboardSafeSnackbarHost(snackbarHostState)
        },
        topBar = {
            val state = uiState as? CommunityChatUiState.Success
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(conectate.com.presentation.navigation.Screen.CommunityDetail.createRoute(communityId))
                            }
                    ) {
                        CircularProfileImage(
                            imageUrl = state?.community?.image,
                            size = 32.dp,
                            fallbackName = state?.community?.name ?: "Comunidad"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = state?.community?.name ?: "Comunidad",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, "Volver")
                    }
                },
                actions = {
                    val invitedIds = state?.memberIds.orEmpty().filterNot {
                        it.equals(currentUserId, ignoreCase = true)
                    }
                    conectate.com.presentation.calls.CallActionButtons(
                        navController = navController,
                        title = state?.community?.name ?: "Comunidad",
                        invitedUserIds = invitedIds,
                        contextType = "community",
                        contextId = communityId
                    )
                    IconButton(onClick = { showHeaderMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Opciones de la comunidad")
                    }
                    DropdownMenu(expanded = showHeaderMenu, onDismissRequest = { showHeaderMenu = false }) {
                        val amICreator = state?.community?.creator_id
                            ?.trim()
                            ?.equals(currentUserId, ignoreCase = true) == true
                        if (state?.isMember == true && !amICreator) {
                            DropdownMenuItem(
                                text = { Text("Salir de la comunidad") },
                                leadingIcon = { Icon(Icons.Default.ExitToApp, contentDescription = null) },
                                onClick = {
                                    showHeaderMenu = false
                                    viewModel.leaveCommunity {
                                        navController.navigate(conectate.com.presentation.navigation.Screen.Messages.route) {
                                            popUpTo(conectate.com.presentation.navigation.Screen.Messages.route) { inclusive = false }
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Fondo") },
                            leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null) },
                            onClick = {
                                showHeaderMenu = false
                                showBackgroundSheet = true
                            }
                        )
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
            if (channels.isNotEmpty()) {
                Surface(tonalElevation = 1.dp) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        items(channels, key = { it.id }) { channel ->
                            FilterChip(
                                selected = selectedChannel?.id == channel.id,
                                onClick = { viewModel.selectChannel(channel) },
                                label = {
                                    Text(
                                        text = when (channel.type) {
                                            "media" -> "▧ ${channel.name}"
                                            "voice" -> "◉ ${channel.name}"
                                            else -> "# ${channel.name}"
                                        },
                                        maxLines = 1
                                    )
                                },
                                leadingIcon = if (channel.isPaused) {
                                    { Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
                        }
                        val canCreate = channels.any { it.canManage }
                        if (canCreate) {
                            item {
                                AssistChip(
                                    onClick = { showCreateChannelDialog = true },
                                    label = { Text("Nuevo") },
                                    leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                                )
                            }
                        }
                        val selected = selectedChannel
                        if (selected != null && (selected.canManage || selected.canPause)) {
                            item {
                                IconButton(onClick = { showChannelActionsDialog = true }) {
                                    Icon(Icons.Default.Settings, "Administrar canal")
                                }
                            }
                        }
                    }
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ChatBackgroundLayer(
                    backgroundType = chatBackgroundSettings.backgroundType,
                    backgroundUri = chatBackgroundSettings.backgroundUri,
                    modifier = Modifier.fillMaxSize()
                )
                when (val state = uiState) {
                    is CommunityChatUiState.Loading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is CommunityChatUiState.Success -> {
                        val memberProfiles = state.memberProfiles
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(bottom = 16.dp, top = 8.dp)
                        ) {
                            itemsIndexed(chatMessages, key = { _, item -> item.id }) { index, msg ->
                                val previousIso = chatMessages.getOrNull(index - 1)?.created_at
                                if (shouldShowDateSeparator(msg.created_at, previousIso)) {
                                    DateSeparator(label = formatDateSeparatorLabel(msg.created_at))
                                }
                                val mediaGroup = remember(chatMessages, msg.id) {
                                    conectate.com.presentation.components.mediaGridClusterAt(chatMessages, index)
                                }
                                if (mediaGroup == null) return@itemsIndexed
                                val isFromMe = msg.sender_id == currentUserId
                                val memberProfile = memberProfiles[msg.sender_id]
                                val pollId = msg.poll_id
                                if (mediaGroup.size > 1) {
                                    conectate.com.presentation.components.MediaGridMessageBubble(
                                        messages = mediaGroup,
                                        isFromCurrentUser = isFromMe,
                                        onClick = { selectedMessage ->
                                            fullScreenMediaItems.indexOfFirst { it.id == selectedMessage.id }
                                                .takeIf { it >= 0 }
                                                ?.let { fullScreenImageInitialIndex = it }
                                        },
                                        onResolveUrl = { viewModel.getPlayableUrl(it) }
                                    )
                                } else if (msg.content_type == "poll" && !pollId.isNullOrBlank()) {
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
                                                val index = fullScreenMediaItems.indexOfFirst { it.id == msg.id }
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
                        
                        if (messages.isNotEmpty()) {
                            val messageIdentity = remember(messages) { messages.map { it.id } }
                            LaunchedEffect(messageIdentity, focusMessageId) {
                                if (!focusResolved && !focusMessageId.isNullOrBlank()) {
                                    val index = messages.indexOfFirst {
                                        it.id.equals(focusMessageId, ignoreCase = true)
                                    }
                                    if (index >= 0) {
                                        listState.animateScrollToItem(index)
                                        focusResolved = true
                                        return@LaunchedEffect
                                    }

                                }
                                listState.animateScrollToItem(messages.size - 1)
                            }
                            val imeBottom = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
                            LaunchedEffect(imeBottom) {
                                if (imeBottom > 0 &&
                                    listState.firstVisibleItemIndex >= (messages.lastIndex - 3).coerceAtLeast(0)
                                ) {
                                    listState.scrollToItem(messages.lastIndex)
                                }
                            }
                        }
                    }
                    is CommunityChatUiState.Error -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // Input Area
            val successState = uiState as? CommunityChatUiState.Success
            if (successState != null && successState.isMember) {
                val activeChannel = selectedChannel
                if (activeChannel?.type == "voice") {
                    Surface(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(activeChannel.name, fontWeight = FontWeight.Bold)
                                Text("Canal de voz: entra a las salas para conversar.")
                            }
                            Button(onClick = {
                                navController.navigate(conectate.com.presentation.navigation.Screen.VoiceRooms.route)
                            }) {
                                Icon(Icons.Default.Mic, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Abrir")
                            }
                        }
                    }
                } else if (activeChannel?.isPaused == true) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Canal pausado temporalmente", fontWeight = FontWeight.Bold)
                                activeChannel.pauseReason?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            }
                        }
                    }
                } else {
                val myRole by viewModel.myRole.collectAsState()
                val canTalk = !successState.community.only_admins_can_talk || 
                             myRole == "admin" || myRole == "owner" || 
                             successState.community.creator_id == currentUserId

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
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp).height(64.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(horizontal = 8.dp)
                        ) {
                            IconButton(onClick = { viewModel.cancelRecording() }) {
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
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
                                             Spacer(Modifier.width(4.dp))
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
                                    IconButton(onClick = {
                                        showMediaComposer = true
                                    }) {
                                        Icon(Icons.Default.CameraAlt, "Cámara", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
            } else if (successState != null) {
                if (successState.community.is_public) {
                    Button(
                        onClick = { viewModel.joinCommunity() },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp)
                    ) {
                        Text("Unirse a la comunidad")
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        tonalElevation = 2.dp
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Ya no eres parte de esta comunidad", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.deleteCommunityLocally { navController.popBackStack() } },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Delete, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Borrar comunidad de mi lista")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateChannelDialog) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("text") }
        AlertDialog(
            onDismissRequest = { showCreateChannelDialog = false },
            title = { Text("Nuevo canal") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(60) },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(240) },
                        label = { Text("Descripción (opcional)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Tipo", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "text" to "Texto",
                            "media" to "Multimedia",
                            "voice" to "Voz"
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = type == value,
                                onClick = { type = value },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = name.isNotBlank() && !isSending,
                    onClick = {
                        viewModel.createChannel(name, description.ifBlank { null }, type)
                        showCreateChannelDialog = false
                    }
                ) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateChannelDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showChannelActionsDialog && selectedChannel != null) {
        val channel = selectedChannel!!
        AlertDialog(
            onDismissRequest = { showChannelActionsDialog = false },
            title = { Text(channel.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (channel.type) {
                            "media" -> "Canal multimedia"
                            "voice" -> "Canal de voz"
                            else -> "Canal de texto"
                        }
                    )
                    if (channel.isPaused) {
                        Text(
                            channel.pauseReason?.let { "Pausado: $it" } ?: "Canal pausado",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (channel.canPause) {
                        OutlinedButton(
                            onClick = {
                                showChannelActionsDialog = false
                                if (channel.isPaused) {
                                    viewModel.setChannelPaused(channel, paused = false)
                                } else {
                                    showPauseChannelDialog = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(if (channel.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (channel.isPaused) "Reanudar canal" else "Pausar canal")
                        }
                    }
                    if (channel.canManage && !channel.isDefault) {
                        OutlinedButton(
                            onClick = {
                                showChannelActionsDialog = false
                                viewModel.deleteChannel(channel)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Delete, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Eliminar canal")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChannelActionsDialog = false }) { Text("Cerrar") }
            }
        )
    }

    if (showPauseChannelDialog && selectedChannel != null) {
        val channel = selectedChannel!!
        var reason by remember { mutableStateOf("") }
        var minutes by remember { mutableIntStateOf(60) }
        AlertDialog(
            onDismissRequest = { showPauseChannelDialog = false },
            title = { Text("Pausar ${channel.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(240) },
                        label = { Text("Motivo") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Duración")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(30 to "30 min", 60 to "1 h", 1440 to "24 h").forEach { (value, label) ->
                            FilterChip(
                                selected = minutes == value,
                                onClick = { minutes = value },
                                label = { Text(label) }
                            )
                        }
                    }
                    Text(
                        "Durante la pausa el canal queda en modo lectura.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = reason.isNotBlank() && !isSending,
                    onClick = {
                        viewModel.setChannelPaused(channel, true, reason, minutes)
                        showPauseChannelDialog = false
                    }
                ) { Text("Pausar") }
            },
            dismissButton = {
                TextButton(onClick = { showPauseChannelDialog = false }) { Text("Cancelar") }
            }
        )
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
                backgroundPickerLauncher.launch("image/*")
            },
            onClear = {
                viewModel.clearChatBackground()
                showBackgroundSheet = false
            }
        )
    }

    if (fullScreenImageInitialIndex != null) {
        val activeMediaItems = if (oneTimeMediaItems.isNotEmpty()) oneTimeMediaItems else fullScreenMediaItems
        FullScreenImageDialog(
            mediaItems = activeMediaItems,
            initialIndex = fullScreenImageInitialIndex!!,
            secureContent = oneTimeMediaItems.isNotEmpty(),
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
                        Text("No hay comunidades disponibles para reenviar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(forwardTargets, key = { it.communityId }) { target ->
                                val checked = selectedForwardTargets.contains(target.communityId)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable {
                                            selectedForwardTargets = if (checked) {
                                                selectedForwardTargets - target.communityId
                                            } else {
                                                selectedForwardTargets + target.communityId
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { isChecked ->
                                            selectedForwardTargets = if (isChecked) {
                                                selectedForwardTargets + target.communityId
                                            } else {
                                                selectedForwardTargets - target.communityId
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
                    onClick = { viewModel.forwardMessageToSelectedCommunities(selectedForwardTargets) }
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



    if (showRecentGifSheet) {
        RecentGifPickerSheet(
            recentUrls = recentGifUrls,
            onSelect = { url -> showRecentGifSheet = false; viewModel.sendRecentGif(url) },
            onChooseFromDevice = { showRecentGifSheet = false; gifPickerLauncher.launch("image/gif") },
            onDismiss = { showRecentGifSheet = false }
        )
    }

    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = "Compartir", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().clickable {
                    showAttachmentSheet = false
                    composerInitialUris = emptyList()
                    composerInitialFilter = "all"
                    showMediaComposer = true
                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Image, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Fotos y videos")
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
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Audiotrack, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Música o audio")
                }
                Row(modifier = Modifier.fillMaxWidth().clickable { showAttachmentSheet = false; documentPickerLauncher.launch("*/*") }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Documento")
                }
                Row(modifier = Modifier.fillMaxWidth().clickable { showAttachmentSheet = false; showRecentGifSheet = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    Text("Stickers y packs")
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
                    viewModel.sendStickerFromUrl(sticker.media_url)
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
                viewModel.sendMediaMessage(
                    clip.uri,
                    context,
                    forcedMessageType = "audio",
                    customContent = null
                )
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
                viewModel.sendMediaMessage(
                    documentUri,
                    context,
                    forcedMessageType = "document",
                    customContent = "📎 Archivo",
                )
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
            initialFilter = composerInitialFilter,
            initialSelectedUris = composerInitialUris,
            initialStep = if (composerInitialUris.isNotEmpty()) "editor" else "gallery",
            isSending = false,
            onDismiss = {
                showMediaComposer = false
                composerInitialUris = emptyList()
            },
            onSend = { mediaList, mediaCaption ->
                showMediaComposer = false
                mediaList.forEachIndexed { index, outgoingMedia ->
                    viewModel.sendMediaMessage(
                        uri = outgoingMedia.uri,
                        context = context,
                        forcedMessageType = outgoingMedia.type,
                        customContent = if (index == 0 && mediaCaption.isNotBlank()) mediaCaption else ""
                    )
                }
                composerInitialUris = emptyList()
            }
        )
    }
}

private fun conectate.com.data.model.CommunityMessage.asChatMessage(): conectate.com.data.model.Message =
    conectate.com.data.model.Message(
        id = id,
        chat_id = community_id,
        sender_id = sender_id,
        content = content,
        content_type = content_type,
        media_url = media_url,
        poll_id = poll_id,
        created_at = created_at,
        status = status,
        reply_to_id = reply_to_id,
        is_edited = is_edited,
        deleted_for_all = deleted_for_all,
        deleted_by = deleted_by,
        reactions = reactions,
        is_forwarded = is_forwarded,
        forwarded_from_message_id = forwarded_from_message_id,
        forwarded_from_user_id = forwarded_from_user_id,
        sender_name = sender_name,
        reply_to = reply_to?.asChatMessage()
    )
