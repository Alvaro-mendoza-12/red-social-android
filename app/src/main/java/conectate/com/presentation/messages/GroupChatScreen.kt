package conectate.com.presentation.messages

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import conectate.com.presentation.messages.OutgoingMessageMedia
import androidx.compose.runtime.Composable

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import conectate.com.util.Constants
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import conectate.com.util.ChatContactPayload
import conectate.com.presentation.messages.stickers.StickerButton
import conectate.com.presentation.messages.stickers.StickerPanel
import conectate.com.presentation.messages.stickers.rememberStickerKeyboardPanel
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupChatScreen(
    navController: NavController,
    groupId: String,
    focusMessageId: String? = null,
    viewModel: GroupChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val isSending by viewModel.isSending.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val markersViewModel: ConversationMarkersViewModel = hiltViewModel()
    val markerFeedback by markersViewModel.feedback.collectAsStateWithLifecycle()
    LaunchedEffect(groupId) { markersViewModel.bind("group", groupId) }
    LaunchedEffect(markerFeedback) {
        markerFeedback?.let { snackbarHostState.showSnackbar(it); markersViewModel.clearFeedback() }
    }
    val listState = rememberConversationListState()
    val context = LocalContext.current
    val openCollectionInvitation = rememberCollectionInvitationOpener(navController)
    val scope = rememberCoroutineScope()
    val downloadChatMedia = rememberChatMediaDownload(viewModel::getCurrentUserId) { viewModel.getPlayableUrl(it) }
    val shareChatMedia = rememberChatMediaShare(viewModel::getCurrentUserId) { viewModel.getPlayableUrl(it) }
    val universalForwardCoordinator = LocalUniversalForwardCoordinator.current
    val currentUserId = viewModel.getCurrentUserId()
    LaunchedEffect(currentUserId) { markersViewModel.refresh() }
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Voice recording states
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val recordingDuration by viewModel.recordingDuration.collectAsStateWithLifecycle()
    val voicePlaybackProgress by viewModel.voicePlaybackProgress.collectAsStateWithLifecycle()
    val voicePlaybackDurationLabel by viewModel.voicePlaybackDurationLabel.collectAsStateWithLifecycle()
    
    // Reply/Edit states
    val replyToMessage by viewModel.replyToMessage.collectAsStateWithLifecycle()
    val editingMessage by viewModel.editingMessage.collectAsStateWithLifecycle()
    val selectedMessage by viewModel.selectedMessage.collectAsStateWithLifecycle()
    var selectedIds by remember(groupId, currentUserId) { mutableStateOf<Set<String>>(emptySet()) }
    var highlightedId by remember(groupId) { mutableStateOf<String?>(null) }
    var confirmSelectionDelete by remember { mutableStateOf(false) }
    var selectionBusy by remember { mutableStateOf(false) }
    LaunchedEffect(highlightedId) {
        if (highlightedId != null) { kotlinx.coroutines.delay(1400); highlightedId = null }
    }
    androidx.activity.compose.BackHandler(selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    fun toggleSelected(id: String) { selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id }

    val transfers by conectate.com.core.messaging.MessageUploadProgress.transfers.collectAsStateWithLifecycle()
    val favoriteStickerMediaKeys by viewModel.favoriteStickerMediaKeys.collectAsStateWithLifecycle()
    val reactionPickerMessage by viewModel.reactionPickerMessage.collectAsStateWithLifecycle()
    val forwardPickerMessage by viewModel.forwardPickerMessage.collectAsStateWithLifecycle()
    val forwardTargets by viewModel.forwardTargets.collectAsStateWithLifecycle()
    val isForwarding by viewModel.isForwarding.collectAsStateWithLifecycle()
    val pollStates by viewModel.pollStates.collectAsStateWithLifecycle()
    val isCreatingPoll by viewModel.isCreatingPoll.collectAsStateWithLifecycle()
    val messageInfoTarget by viewModel.messageInfoTarget.collectAsStateWithLifecycle()
    val messageInfoReceipts by viewModel.messageInfoReceipts.collectAsStateWithLifecycle()
    val isLoadingMessageInfo by viewModel.isLoadingMessageInfo.collectAsStateWithLifecycle()
    val pollOptionVoters by viewModel.pollOptionVoters.collectAsStateWithLifecycle()
    val chatBackgroundSettings by viewModel.chatBackgroundSettings.collectAsStateWithLifecycle()

    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showPollDialog by remember { mutableStateOf(false) }
    var showStickerCatalogDialog by remember { mutableStateOf(false) }
    val stickerKeyboardPanel = rememberStickerKeyboardPanel(showStickerCatalogDialog) { showStickerCatalogDialog = it }
    var selectedForwardTargets by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var showHeaderMenu by remember { mutableStateOf(false) }
    var showQuestions by remember { mutableStateOf(false) }
    var publicationAllowed by remember(groupId, currentUserId) { mutableStateOf(true) }
    CollectionQuestionTools("group", groupId, currentUserId, showQuestions, { showQuestions = false }, { publicationAllowed = it })
    var showWorkspace by remember(groupId) { mutableStateOf(false) }
    var workspaceKind by remember(groupId) { mutableStateOf(ChatWorkspaceKind.TASK) }
    var workspaceSource by remember(groupId) { mutableStateOf<conectate.com.data.model.Message?>(null) }
    var backgroundImageToCrop by remember { mutableStateOf<Uri?>(null) }
    val backgroundPickerLauncher = conectate.com.presentation.components.rememberInAppMediaPicker() { uri ->
        backgroundImageToCrop = uri
    }

    backgroundImageToCrop?.let { sourceUri ->
        conectate.com.presentation.components.SimpleImageCropDialog(
            sourceUri = sourceUri,
            title = "Ajustar fondo del grupo",
            initialPreset = conectate.com.presentation.components.ImageCropPreset.ORIGINAL,
            onDismiss = { backgroundImageToCrop = null },
            onCropped = { croppedUri ->
                viewModel.setChatBackground("custom", croppedUri.toString())
                backgroundImageToCrop = null
            }
        )
    }

    // FULL SCREEN MEDIA VIEWER STATE
    val messages = (uiState as? GroupChatUiState.Success)?.messages ?: emptyList()
    val chatMessages = remember(messages) { messages.map { it.asChatMessage() } }
    val replyMessages = remember(chatMessages) { chatMessages.associateBy { it.id } }
    if (confirmSelectionDelete) AlertDialog(
        onDismissRequest = { confirmSelectionDelete = false },
        title = { Text("Eliminar mensajes") },
        text = { Text("¿Eliminar ${selectedIds.size} mensajes seleccionados?") },
        confirmButton = {
            Column {
                if (MessageDeletionPolicy.canDeleteForEveryone(chatMessages, selectedIds, currentUserId)) {
                    TextButton(onClick = {
                        confirmSelectionDelete = false; selectionBusy = true
                        viewModel.deleteMessagesForEveryone(selectedIds) { deleted -> selectedIds = selectedIds - deleted; selectionBusy = false }
                    }) { Text("Eliminar para todos", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = {
                    confirmSelectionDelete = false; selectionBusy = true
                    viewModel.deleteMessagesForMe(selectedIds) { deleted -> selectedIds = selectedIds - deleted; selectionBusy = false }
                }) { Text("Eliminar para mí") }
            }
        },
        dismissButton = { TextButton(onClick = { confirmSelectionDelete = false }) { Text("Cancelar") } },
    )

    val mediaMessages = remember(messages) {
        messages.filter {
            conectate.com.presentation.components.isGalleryMediaType(it.content_type) &&
                !it.media_url.isNullOrBlank() &&
                !it.deleted_for_all
        }
    }
    val fullScreenMediaItems = remember(mediaMessages) {
        mediaMessages.map { message ->
            MediaItem(
                id = message.id,
                url = message.media_url.orEmpty(),
                type = message.content_type,
            )
        }
    }
    var fullScreenImageInitialIndex by remember { mutableStateOf<Int?>(null) }
    var oneTimeMediaItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    ObserveVisibleConversationMessages(
        messages = chatMessages, listState = listState, groupMedia = selectedIds.isEmpty() && highlightedId == null,
        onVisibleIdsChanged = viewModel::onVisibleMessageIdsChanged,
    )

    fun sendCurrentLocation() {
        scope.launch {
            val location = getBestCurrentLocation(context)
            if (location == null) {
                snackbarHostState.showSnackbar("No se pudo obtener tu ubicación. Revisa el permiso y activa la ubicación del dispositivo.")
                return@launch
            }
            viewModel.sendLocationMessage(latitude = formatCoordinate(location.latitude), longitude = formatCoordinate(location.longitude), label = "Ubicación actual")
        }
    }

    var showUnifiedMediaPicker by remember { mutableStateOf(false) }
    var showAudioPickerDialog by remember { mutableStateOf(false) }
    var pendingAudioPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDocumentUri by remember { mutableStateOf<Uri?>(null) }
    var pendingContactPayload by remember { mutableStateOf<DeviceContactPayload?>(null) }
    val audioPickerLauncher = conectate.com.presentation.documents.rememberInAppDocumentPicker() { uri ->
        if (uri != null) {
            pendingAudioPreviewUri = uri
            showAudioPickerDialog = true
        }
    }
    val showJumpToLatest by remember(messages, listState) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: messages.lastIndex
            messages.size > 2 && lastVisible < messages.lastIndex - 1
        }
    }

    var showDocumentPicker by remember { mutableStateOf(false) }
    if (showDocumentPicker) {
        conectate.com.presentation.documents.DocumentPickerSheet(
            onDismiss = { showDocumentPicker = false },
            onPicked = { uri -> showDocumentPicker = false; pendingDocumentUri = uri },
        )
    }

    val gifPickerLauncher = conectate.com.presentation.components.rememberInAppMediaPicker() { uri ->
        uri?.let { viewModel.sendMediaMessage(it, context, forcedMessageType = "gif", customContent = "🌀 GIF") }
    }

    var showMediaComposer by remember { mutableStateOf(false) }
    var composerInitialStep by remember { mutableStateOf("gallery") }
    var composerInitialUris by remember { mutableStateOf<List<Pair<Uri, String>>>(emptyList()) }

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

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.startRecording()
    }

    DisposableEffect(lifecycleOwner, groupId) {
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

    LaunchedEffect(groupId) {
        viewModel.init(groupId)
    }

    LaunchedEffect(forwardPickerMessage?.id) {
        selectedForwardTargets = emptySet()
    }

    Scaffold(
        snackbarHost = {
            conectate.com.presentation.common.KeyboardSafeSnackbarHost(snackbarHostState)
        },
        topBar = {
            if (selectedIds.isNotEmpty()) {
                MessageSelectionToolbar(selectedIds.size,
                    onCancel = { selectedIds = emptySet() },
                    onDelete = { confirmSelectionDelete = true },
                    onForward = {
                        val selected = chatMessages.filter { it.id in selectedIds && !it.deleted_for_all }
                        if (universalForwardCoordinator != null) {
                            universalForwardCoordinator.open(candidates = selected.map { it.toForwardMessagePayload() }, initiallySelectedIds = selected.map { it.id }.toSet())
                            selectedIds = emptySet()
                        } else scope.launch { snackbarHostState.showSnackbar("No se pudo abrir el selector de destinatarios.") }
                    },
                    onSave = { selectedIds.forEach { markersViewModel.toggleSaved(it) }; selectedIds = emptySet() },
                    onMore = { chatMessages.firstOrNull { it.id in selectedIds }?.let(viewModel::onMessageLongClick) },
                    busy = selectionBusy,
                )
            } else {
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
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(conectate.com.presentation.navigation.Screen.GroupDetail.createRoute(groupId))
                            }
                    ) {
                        CircularProfileImage(
                            imageUrl = conectate.com.data.localfallback.UbuntuStorageUrl.collectionAvatar("group", state?.group?.image),
                            size = 32.dp,
                            fallbackName = state?.group?.name ?: "Grupo"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = state?.group?.name ?: "Grupo",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    val invitedIds = state?.memberIds.orEmpty().filterNot {
                        it.equals(currentUserId, ignoreCase = true)
                    }
                    conectate.com.presentation.calls.CallActionButtons(
                        navController = navController,
                        title = state?.group?.name ?: "Grupo",
                        invitedUserIds = invitedIds,
                        contextType = "group",
                        contextId = groupId
                    )
                    IconButton(onClick = { showHeaderMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Opciones del grupo")
                    }
                    DropdownMenu(expanded = showHeaderMenu, onDismissRequest = { showHeaderMenu = false }) {
                        DropdownMenuItem(text = { Text("Preguntas y permisos") }, onClick = { showHeaderMenu = false; showQuestions = true })
                        DropdownMenuItem(text = { Text("Sorteos") }, onClick = {
                            showHeaderMenu = false
                            navController.navigate(conectate.com.presentation.navigation.RaffleNavigation.createRoute("group", groupId))
                        })
                        DropdownMenuItem(
                            text = { Text("Mi espacio y cronograma") },
                            enabled = state != null,
                            leadingIcon = { Icon(Icons.Default.EventNote, contentDescription = null) },
                            onClick = {
                                showHeaderMenu = false
                                workspaceSource = null
                                workspaceKind = ChatWorkspaceKind.TASK
                                showWorkspace = true
                            },
                        )
                        val amICreator = state?.group?.creator_id
                            ?.trim()
                            ?.equals(currentUserId, ignoreCase = true) == true
                        if (state != null && !amICreator) {
                            DropdownMenuItem(
                                text = { Text("Salir del grupo") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                                onClick = {
                                    showHeaderMenu = false
                                    viewModel.leaveGroup {
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            ConversationMarkersBar(markersViewModel) { messageId ->
                val index = messages.indexOfFirst { it.id.equals(messageId, ignoreCase = true) }
                scope.launch {
                    if (index >= 0) listState.animateScrollToItem(index)
                    else snackbarHostState.showSnackbar("El mensaje no está en el historial cargado.")
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                ChatBackgroundLayer(
                    backgroundType = chatBackgroundSettings.backgroundType,
                    backgroundUri = chatBackgroundSettings.backgroundUri,
                    modifier = Modifier.fillMaxSize()
                )
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
                            itemsIndexed(chatMessages, key = { _, item -> item.id }, contentType = { _, item -> item.content_type }) { index, msg ->
                                val previousIso = chatMessages.getOrNull(index - 1)?.created_at
                                if (shouldShowDateSeparator(msg.created_at, previousIso)) {
                                    DateSeparator(label = formatDateSeparatorLabel(msg.created_at))
                                }
                                if (msg.content_type == "system_membership") {
                                    CollectionSystemNotice(msg.content)
                                    return@itemsIndexed
                                }
                                val mediaGroup = remember(chatMessages, msg.id, selectedIds.isNotEmpty(), highlightedId) {
                                    if (selectedIds.isNotEmpty() || highlightedId != null) listOf(msg) else
                                    conectate.com.presentation.components.mediaGridClusterAt(chatMessages, index)
                                }
                                if (mediaGroup == null) return@itemsIndexed
                                val isFromMe = msg.sender_id == currentUserId
                                val memberProfile = memberProfiles[msg.sender_id]
                                val pollId = msg.poll_id
                                if (mediaGroup.size > 1) {
                                    conectate.com.presentation.components.MediaGridMessageBubble(
                                        onLongClick = { toggleSelected(it.id) },
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
                                } else if (msg.content_type == "question") {
                                    CommunityQuestionCard("group", groupId, msg.content, currentUserId, reactions=msg.reactions, onReact={viewModel.openReactionPicker(msg)}, onReaction={viewModel.reactToMessage(msg,it)}, onLongClick={viewModel.onMessageLongClick(msg)})
                                } else if (msg.content_type == "raffle") {
                                    conectate.com.presentation.raffles.RaffleMessageCard(msg.content, msg.sender_name) {
                                        navController.navigate(conectate.com.presentation.navigation.RaffleNavigation.createRoute("group", groupId, msg.media_url))
                                    }
                                } else {
                                    val audio = rememberChatMessageAudioState(
                                        msg.id, msg.content_type, msg.content.ifBlank { "0:00" },
                                        viewModel.currentPlayingAudioId, viewModel.isAudioPlaying,
                                        viewModel.audioProgress, viewModel.audioDurationLabel,
                                        viewModel.audioDurations, viewModel.audioPlaybackSpeed,
                                    )
                                    MessageBubble(
                                        message = msg,
                                        replyMessage = msg.reply_to_id?.let(replyMessages::get),
                                        isSelected = msg.id in selectedIds || msg.id == highlightedId,
                                        selectionMode = selectedIds.isNotEmpty(),
                                        replySenderName = msg.reply_to?.sender_id?.let { memberProfiles[it]?.displayName },
                                        uploadProgress = transfers[conectate.com.core.messaging.MessageUploadProgress.key(currentUserId, msg.id)],
                                        onRetrySend = { viewModel.retryMessage(msg) },
                                        isFromCurrentUser = isFromMe,
                                        senderName = if (!isFromMe) memberProfile?.username ?: msg.sender_name else null,
                                        senderEmail = memberProfile?.email,
                                        isAdmin = memberProfile?.is_admin == true,
                                        isVerified = memberProfile?.is_verified ?: false,
                                        userBadges = memberProfile?.badges ?: emptyList(),
                                        isAudioActive = audio.active,
                                        totalAudioDurationMs = audio.totalDurationMs,
                                        isPlaying = audio.playing,
                                        progress = audio.progress,
                                        duration = audio.duration,
                                        playbackSpeed = audio.speed,
                                        onSeekAudio = { _, fraction -> viewModel.seekAudio(fraction) },
                                        onChangeAudioSpeed = { viewModel.cycleAudioPlaybackSpeed() },
                                        onPlayAudio = { viewModel.playAudio(it) },
                                        onLongClick = { toggleSelected(msg.id) },
                                        onStickerClick = { viewModel.onMessageLongClick(msg) },
                                        onResolveUrl = { path ->
                                            viewModel.getPlayableUrl(
                                                path = path,
                                                allowOfflineMediaCache = !msg.content_type.startsWith("view_once_")
                                            )
                                        },
                                        onClick = { clickedUrl ->
                                            if (selectedIds.isNotEmpty()) { toggleSelected(msg.id) } else
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
                                        onReplyClick = { originalMessageId ->
                                            scope.launch {
                                                val originalIndex = messages.indexOfFirst {
                                                    it.id.equals(originalMessageId, ignoreCase = true)
                                                }
                                                if (originalIndex >= 0) {
                                                    listState.animateScrollToItem(originalIndex)
                                                    highlightedId = originalMessageId
                                                } else {
                                                    snackbarHostState.showSnackbar(
                                                        "El mensaje original ya no está disponible."
                                                    )
                                                }
                                            }
                                        },
                                        onSenderClick = { userId ->
                                            navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId))
                                        },
                                        onCollectionInvitationClick = { openCollectionInvitation(it) },
                                        onMentionClick = { handle -> scope.launch {
                                            val userId = viewModel.resolveMention(handle)
                                            if (userId != null) navController.navigate(conectate.com.presentation.navigation.Screen.UserProfile.createRoute(userId))
                                            else android.widget.Toast.makeText(context, "No se encontró un perfil único para esa mención.", android.widget.Toast.LENGTH_SHORT).show()
                                        } },
                                        onUrlClick = { url ->
                                            val content = conectate.com.presentation.navigation.sharedContentDestination(url)
                                            if (openCollectionInvitation(url)) Unit
                                            else if (content != null) navController.navigate(content.route)
                                            else conectate.com.data.model.videoIdFromSharedLink(url)?.let { id ->
                                                navController.navigate(conectate.com.presentation.navigation.Screen.VideoPlayer.createRoute(id))
                                            }
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
                        
                        ConversationScrollAnchor("group:$currentUserId:$groupId", messages, currentUserId, focusMessageId, listState,
                            messageId = { it.id }, senderId = { it.sender_id })
                    }
                    is GroupChatUiState.Error -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(state.message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (showJumpToLatest) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem(messages.lastIndex)
                                }
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 14.dp, bottom = 12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Ir al último mensaje")
                    }
                }
            }

            // Input Area
            val successState = uiState as? GroupChatUiState.Success
            val isMember by viewModel.isMember.collectAsStateWithLifecycle()
            
            if (successState != null && isMember) {
                val myRole by viewModel.myRole.collectAsStateWithLifecycle()
                val canTalk = publicationAllowed && (!successState.group.only_admins_can_talk ||
                             myRole == "admin" || myRole == "owner" || 
                             successState.group.creator_id == currentUserId)

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
                                        text = conectate.com.util.quotedMessagePreview(reply),
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
                                         val timeString = String.format(
                                             androidx.compose.ui.platform.LocalLocale.current.platformLocale,
                                             "%02d:%02d",
                                             minutes,
                                             seconds,
                                         )
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
                                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp))
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
                                        Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(20.dp))
                                    }
                                }
                                else -> {}
                            }
                        }
                    } else {
                        // STANDARD INPUT
                        ChatComposerDraft(viewModel.messageText) { messageText ->
                        val mentionMembers by viewModel.mentionMembers.collectAsStateWithLifecycle()
                        var mentionDraft by remember(groupId) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(messageText)) }
                        LaunchedEffect(messageText) {
                            if (mentionDraft.text != messageText) mentionDraft = androidx.compose.ui.text.input.TextFieldValue(messageText, androidx.compose.ui.text.TextRange(messageText.length))
                        }
                        val activeMention = conectate.com.data.model.activeMentionAt(mentionDraft.text, mentionDraft.selection.end)
                        LaunchedEffect(activeMention?.start) { if (activeMention != null) viewModel.refreshMentionMembers() }
                        val mentionSuggestions = if (activeMention == null) emptyList() else
                            conectate.com.data.model.roomMentionCandidates(mentionMembers, activeMention.query, viewModel.getCurrentUserId())
                        Column {
                        val spelling = conectate.com.presentation.components.rememberChatSpellCheckState(
                            mentionDraft.text, conectate.com.presentation.components.mentionInputTransformation)
                        conectate.com.presentation.components.MentionSuggestions(mentionSuggestions, onSelected = { user ->
                            activeMention?.let { active ->
                                val (text, cursor) = conectate.com.data.model.insertMention(mentionDraft.text, active, conectate.com.data.model.profilePublicHandle(user))
                                mentionDraft = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(cursor))
                                viewModel.updateMessageText(text)
                            }
                        })
                        conectate.com.presentation.components.ChatSpellingSuggestions(
                            state = spelling,
                            selection = mentionDraft.selection,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            onReplace = { issue, replacement ->
                                val corrected = spelling.replace(mentionDraft.text, issue, replacement)
                                mentionDraft = androidx.compose.ui.text.input.TextFieldValue(corrected,
                                    androidx.compose.ui.text.TextRange(issue.start + replacement.length))
                                viewModel.updateMessageText(corrected)
                            }
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp)
                        ) {
                            IconButton(onClick = { showAttachmentSheet = true }) {
                                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            StickerButton(onClick = stickerKeyboardPanel.toggle, showingStickers = showStickerCatalogDialog)
                            val composerFieldState = rememberChatComposerFieldState(mentionDraft) {
                                val textChanged = mentionDraft.text != it.text
                                mentionDraft = it
                                if (textChanged) viewModel.updateMessageText(it.text)
                            }
                            TextField(
                                state = composerFieldState,
                                inputTransformation = ChatUnicodeInputTransformation,
                                outputTransformation = rememberChatComposerOutputTransformation(spelling.issues, highlightMentions = true),
                                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                                    .contentReceiver(rememberChatImagePasteReceiver { images ->
                                        composerInitialUris = images.take(7)
                                        composerInitialStep = "editor"
                                        showMediaComposer = true
                                    })
                                    .focusRequester(stickerKeyboardPanel.inputFocusRequester)
                                    .onFocusChanged { if (it.isFocused) stickerKeyboardPanel.onInputFocused() },
                                enabled = true,
                                placeholder = { Text("Mensaje") },
                                lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                                shape = RoundedCornerShape(12.dp),
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences, autoCorrectEnabled = true, imeAction = androidx.compose.ui.text.input.ImeAction.Send),
                                onKeyboardAction = androidx.compose.foundation.text.input.KeyboardActionHandler {
                                    val currentText = composerFieldState.publishCurrentChatDraft(viewModel::updateMessageText)
                                    if (currentText.isNotBlank()) {
                                        if (editingMessage != null) viewModel.confirmEditMessage() else viewModel.sendMessage()
                                    }
                                },
                                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent),
                                trailingIcon = {
                                    IconButton(onClick = { composerInitialStep = "camera"; composerInitialUris = emptyList(); showMediaComposer = true }) {
                                        Icon(Icons.Default.CameraAlt, "Cámara", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            )
                            Spacer(Modifier.width(4.dp))
                            FloatingActionButton(
                                onClick = {
                                    val currentText = composerFieldState.publishCurrentChatDraft(viewModel::updateMessageText)
                                    if (currentText.isNotBlank()) {
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
                                val hasText = composerFieldState.text.isNotBlank()
                                Icon(imageVector = if (hasText) Icons.AutoMirrored.Filled.Send else Icons.Default.Mic, contentDescription = if (hasText) "Enviar mensaje" else "Grabar audio")
                            }
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
                            Column(Modifier.weight(1f)) { Text(if(publicationAllowed) "Solo los administradores pueden escribir" else "Solo el creador escribe. Puedes reaccionar, crear encuestas y responder preguntas.", style = MaterialTheme.typography.bodyMedium); if(!publicationAllowed) TextButton(onClick={showPollDialog=true}){Text("Crear encuesta")} }
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

                StickerPanel(
                    visible = showStickerCatalogDialog,
                    onDismiss = stickerKeyboardPanel.showKeyboard,
                    modifier = Modifier.fillMaxWidth().height(stickerKeyboardPanel.height),
                    snackbarHostState = snackbarHostState,
                    onStickerSelected = { sticker ->
                        if (!sticker.media_url.isNullOrBlank()) {
                            viewModel.sendStickerFromUrl(sticker.media_url)
                        } else {
                            viewModel.sendStickerEmoji(sticker.title.ifBlank { "🧩" })
                        }
                    },
                    onEmojiSelected = { emoji ->
                        viewModel.updateMessageText(viewModel.messageText.value + emoji)
                    },
                    onEmojiBackspace = {
                        viewModel.updateMessageText(removeLastChatGrapheme(viewModel.messageText.value))
                    },
                )
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
            onDownload = if (oneTimeMediaItems.isNotEmpty()) null else downloadChatMedia,
            onShare = if (oneTimeMediaItems.isNotEmpty()) null else shareChatMedia,
            onDismiss = {
                fullScreenImageInitialIndex = null
                oneTimeMediaItems = emptyList()
            },
            onResolveUrl = { path ->
                viewModel.getPlayableUrl(
                    path = path,
                    allowOfflineMediaCache = oneTimeMediaItems.isEmpty()
                )
            }
        )
    }

    if (showWorkspace && uiState is GroupChatUiState.Success) {
        ChatComposerDraft(viewModel.messageText) { workspaceDraft ->
        ConversationWorkspaceDialog(
            type = "group", conversationId = groupId,
            composerText = workspaceDraft, initialKind = workspaceKind, source = workspaceSource,
            onDismiss = { showWorkspace = false; workspaceSource = null },
            onLoadDraft = { draft ->
                viewModel.cancelEdit()
                viewModel.cancelReply()
                viewModel.updateMessageText(draft)
                showWorkspace = false
            },
            onOpenSource = { id ->
                val index = messages.indexOfFirst { it.id == id }
                if (index >= 0) {
                    showWorkspace = false
                    scope.launch { listState.animateScrollToItem(index) }
                } else scope.launch { snackbarHostState.showSnackbar("El mensaje no está en el historial cargado.") }
            },
        )
        }
    }

    if (selectedMessage != null) {
        ModalBottomSheet(onDismissRequest = { viewModel.dismissMessageActions() }) {
            Column(modifier = Modifier.padding(16.dp)
                .heightIn(max = 560.dp)
                .then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))) {
                MessageMarkerActions(selectedMessage, markersViewModel, viewModel::dismissMessageActions)
                WorkspaceCaptureActions(selectedMessage, currentUserId) { kind, source ->
                    workspaceKind = kind
                    workspaceSource = source
                    viewModel.dismissMessageActions()
                    showWorkspace = true
                }
                
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
                    if (MessageDeletionPolicy.canDeleteForEveryone(selectedMessage, currentUserId) &&
                        (selectedIds.isEmpty() || MessageDeletionPolicy.canDeleteForEveryone(chatMessages, selectedIds, currentUserId))) {
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
                        val stickerIsFavorite = viewModel.isStickerFavorite(selectedMessage?.media_url, favoriteStickerMediaKeys)
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
                        Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
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
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
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



    if (showAttachmentSheet) {
        ModalBottomSheet(onDismissRequest = { showAttachmentSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(text = "Compartir", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().clickable { showAttachmentSheet = false; showMediaComposer = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Fotos y videos", style = MaterialTheme.typography.bodyLarge)
                }
                ClipboardImagePasteAction(maxItems = 7) { images ->
                    composerInitialUris = images
                    composerInitialStep = "editor"
                    showAttachmentSheet = false
                    showMediaComposer = true
                }
                Row(modifier = Modifier.fillMaxWidth().clickable {
                    showAttachmentSheet = false
                    pendingAudioPreviewUri = null
                    showAudioPickerDialog = true
                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Audiotrack, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("Audio")
                }
                Row(modifier = Modifier.fillMaxWidth().clickable { showAttachmentSheet = false; showDocumentPicker = true }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
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
            enableOpenCatalog = false,
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
            onConfirmDocument = { selectedDocument ->
                viewModel.sendMediaMessage(
                    selectedDocument,
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
            initialSelectedUris = composerInitialUris,
            initialStep = composerInitialStep,
            isSending = false,
            onDismiss = { showMediaComposer = false; composerInitialStep = "gallery"; composerInitialUris = emptyList() },
            onSend = { mediaList, mediaCaption ->
                showMediaComposer = false
                composerInitialStep = "gallery"
                composerInitialUris = emptyList()
                mediaList.forEachIndexed { index, outgoingMedia ->
                    viewModel.sendMediaMessage(
                        uri = outgoingMedia.uri,
                        context = context,
                        forcedMessageType = outgoingMedia.type,
                        customContent = if (index == 0 && mediaCaption.isNotBlank()) mediaCaption else ""
                    )
                }
            }
        )
    }
}

private fun conectate.com.data.model.GroupMessage.asChatMessage(): conectate.com.data.model.Message =
    conectate.com.data.model.Message(
        id = id,
        chat_id = group_id,
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
