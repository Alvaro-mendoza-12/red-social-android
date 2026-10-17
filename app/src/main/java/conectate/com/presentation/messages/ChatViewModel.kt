package conectate.com.presentation.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.ChatSummary
import conectate.com.data.model.Message
import conectate.com.data.repository.message.ScheduledMessagesKey
import conectate.com.data.model.TypingConversationScope
import conectate.com.data.model.TypingParticipant
import conectate.com.data.model.User
import conectate.com.core.messaging.UnreadPolicy
import conectate.com.core.network.InteractiveReadDeadlines
import conectate.com.core.network.runReadWithDeadline
import conectate.com.core.observability.AppMetrics
import conectate.com.data.repository.AdvancedMessagingRepository
import conectate.com.data.repository.BlockRelationship
import conectate.com.data.repository.ChatBackgroundSettings
import conectate.com.data.repository.ChatSettingsRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.StickerRepository
import conectate.com.data.repository.TypingRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import conectate.com.util.ChatContactPayload
import conectate.com.util.UbuntuMedia
import conectate.com.util.encodeContactPayload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.io.File
import conectate.com.util.ChatAudioPlaybackCache

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationDraftStore: conectate.com.data.local.ConversationDraftStore,
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val advancedMessagingRepository: AdvancedMessagingRepository,
    private val chatSettingsRepository: ChatSettingsRepository,
    private val userRepository: UserRepository,
    private val typingRepository: TypingRepository,
    private val badgeRepository: conectate.com.data.repository.BadgeRepository,
    private val authService: AuthService,
    private val groupRepository: conectate.com.data.repository.GroupRepository,
    private val communityRepository: conectate.com.data.repository.CommunityRepository,
    private val stickerRepository: StickerRepository,
    private val voiceMessageManager: VoiceMessageManager,
    private val appMetrics: AppMetrics,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {
    val isSecureTranslationAvailable: Boolean
        get() = advancedMessagingRepository.isSecureTranslationAvailable()

    private val otherUserId: String = savedStateHandle.get<String>("userId").orEmpty().trim()
    private val initialMessage: String? = savedStateHandle["initialMessage"]
    private var initialMessageSent = false
    private var chatId: String? = null
    private var isPlaceholder: Boolean = false
    private var isDirectConversation = true
    private var resolvedTargetUserId: String? = null
    private val ownerAccountId = authService.getCurrentUserId()?.trim()?.lowercase()

    // UI State
    private val _chatState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()
    private val _isScreenActive = MutableStateFlow(false)
    private val _visibleMessageIds = MutableStateFlow<Set<String>>(emptySet())
    private val _chatBackgroundSettings = MutableStateFlow(ChatBackgroundSettings())
    val chatBackgroundSettings: StateFlow<ChatBackgroundSettings> = _chatBackgroundSettings.asStateFlow()
    private val _typingParticipants = MutableStateFlow<List<TypingParticipant>>(emptyList())
    val typingParticipants: StateFlow<List<TypingParticipant>> = _typingParticipants.asStateFlow()

    private val textComposer = QueuedMessageComposer<Message>()
    private val _messageText = textComposer.text
    val messageText: StateFlow<String> = _messageText

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
    private val _messageRequest = MutableStateFlow(conectate.com.data.model.DirectMessageRequestState())
    val messageRequest = _messageRequest.asStateFlow()
    private val _requestWarningAcknowledged = MutableStateFlow(false)
    val requestWarningAcknowledged = _requestWarningAcknowledged.asStateFlow()
    private val _requestDecisionBusy = MutableStateFlow(false)
    val requestDecisionBusy = _requestDecisionBusy.asStateFlow()
    private var requestRefreshJob: Job? = null
    private var requestPollJob: Job? = null
    private var introductionReservedAtMillis = 0L

    private fun reserveIntroduction() {
        if (_messageRequest.value.status == "new") {
            introductionReservedAtMillis = System.currentTimeMillis()
            _messageRequest.value = conectate.com.data.model.DirectMessageRequestState("pending_outgoing")
        }
    }

    fun acknowledgeMessageRequestWarning() { _requestWarningAcknowledged.value = true }

    fun refreshMessageRequest() {
        if (requestRefreshJob?.isActive == true) return
        val cid = chatId ?: return
        val target = resolvedTargetUserId ?: otherUserId
        if (!isDirectConversation) {
            _messageRequest.value = conectate.com.data.model.DirectMessageRequestState("open"); return
        }
        requestRefreshJob = viewModelScope.launch {
            val result = messageRepository.loadDirectMessageRequest(if (isPlaceholder) null else cid, target)
            if (ownerAccountId != authService.getCurrentUserId()?.trim()?.lowercase()) return@launch
            result.fold(
                onSuccess = { state ->
                    val unsent = (_chatState.value as? ChatUiState.Success)?.messages.orEmpty().any {
                        it.sender_id.equals(authService.getCurrentUserId(), true) && it.status in setOf("pending", "sending", "queued", "uploading")
                    }
                    val reserved = unsent || _isSending.value || System.currentTimeMillis() - introductionReservedAtMillis < 15_000L
                    if (!(state.status == "new" && _messageRequest.value.status == "pending_outgoing" && reserved))
                        _messageRequest.value = state
                },
                onFailure = { _messageRequest.value = _messageRequest.value.afterVerificationFailure() },
            )
        }
    }

    fun decideMessageRequest(accept: Boolean) {
        if (_requestDecisionBusy.value || _messageRequest.value.status != "pending_incoming") return
        val cid = chatId ?: return
        _requestDecisionBusy.value = true
        viewModelScope.launch {
            try {
                val result = messageRepository.decideDirectMessageRequest(cid, accept)
                if (ownerAccountId != authService.getCurrentUserId()?.trim()?.lowercase()) return@launch
                result.fold(onSuccess = { _messageRequest.value = it }, onFailure = {
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo actualizar la solicitud. Inténtalo nuevamente.")
                })
            } finally { _requestDecisionBusy.value = false }
        }
    }

    
    private val _isBlocked = MutableStateFlow(false)
    val isBlocked: StateFlow<Boolean> = _isBlocked.asStateFlow()
    private val _isBlockedByMe = MutableStateFlow(false)
    val isBlockedByMe: StateFlow<Boolean> = _isBlockedByMe.asStateFlow()
    private val _isBlockedByOther = MutableStateFlow(false)
    val isBlockedByOther: StateFlow<Boolean> = _isBlockedByOther.asStateFlow()
    
    // Voice Manager State Expose
    val voiceState = voiceMessageManager.state
    val recordingDuration = voiceMessageManager.duration
    val voicePlaybackProgress = voiceMessageManager.playbackProgress
    val voicePlaybackDurationLabel = voiceMessageManager.playbackDurationLabel

    private val _selectedMessages = MutableStateFlow<Set<String>>(emptySet())
    val selectedMessages: StateFlow<Set<String>> = _selectedMessages.asStateFlow()
    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()
    private val _selectedMessage = MutableStateFlow<Message?>(null)
    val selectedMessage: StateFlow<Message?> = _selectedMessage.asStateFlow()
    private val _replyToMessage = textComposer.reply
    val replyToMessage: StateFlow<Message?> = _replyToMessage
    private val _editingMessage = MutableStateFlow<Message?>(null)
    val editingMessage: StateFlow<Message?> = _editingMessage.asStateFlow()
    private val _reactionPickerMessage = MutableStateFlow<Message?>(null)
    val reactionPickerMessage: StateFlow<Message?> = _reactionPickerMessage.asStateFlow()
    private val _forwardPickerMessage = MutableStateFlow<Message?>(null)
    val forwardPickerMessage: StateFlow<Message?> = _forwardPickerMessage.asStateFlow()
    private val _forwardUserSearchQuery = MutableStateFlow("")
    val forwardUserSearchQuery: StateFlow<String> = _forwardUserSearchQuery.asStateFlow()
    private val _forwardUserTargets = MutableStateFlow<List<ForwardUserTarget>>(emptyList())
    val forwardUserTargets: StateFlow<List<ForwardUserTarget>> = _forwardUserTargets.asStateFlow()
    private val _isForwarding = MutableStateFlow(false)
    val isForwarding: StateFlow<Boolean> = _isForwarding.asStateFlow()
    private val _currentPlayingAudioId = MutableStateFlow<String?>(null)
    val currentPlayingAudioId: StateFlow<String?> = _currentPlayingAudioId.asStateFlow()
    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()
    private val _audioPlaybackSpeed = MutableStateFlow(1f)
    val audioPlaybackSpeed: StateFlow<Float> = _audioPlaybackSpeed.asStateFlow()
    private val _audioProgress = MutableStateFlow(0f)
    val audioProgress: StateFlow<Float> = _audioProgress.asStateFlow()
    private val _audioDuration = MutableStateFlow(0)
    val audioDuration: StateFlow<Int> = _audioDuration.asStateFlow()
    private val _audioDurationLabel = MutableStateFlow("0:00")
    val audioDurationLabel: StateFlow<String> = _audioDurationLabel.asStateFlow()

    // Nickname & Clear Chat state
    private val _showNicknameDialog = MutableStateFlow(false)
    val showNicknameDialog = _showNicknameDialog.asStateFlow()
    
    private val _currentNickname = MutableStateFlow<String?>(null)
    val currentNickname = _currentNickname.asStateFlow()

    private val _showClearChatDialog = MutableStateFlow(false)
    val showClearChatDialog = _showClearChatDialog.asStateFlow()

    private val _advancedMessage = MutableStateFlow<String?>(null)
    val advancedMessage: StateFlow<String?> = _advancedMessage.asStateFlow()

    private val scheduledController = ScheduledMessagesController(
        scope = viewModelScope,
        cached = advancedMessagingRepository::getCachedScheduledJobs,
        refresh = advancedMessagingRepository::getScheduledJobs,
        isCurrent = { key -> key == scheduledMessagesKey() },
    )
    val scheduledMessagesState = scheduledController.state

    private val _chatSummary = MutableStateFlow<ChatSummary?>(null)
    val chatSummary: StateFlow<ChatSummary?> = _chatSummary.asStateFlow()

    private val _autoTranslateEnabled = MutableStateFlow(false)
    val autoTranslateEnabled: StateFlow<Boolean> = _autoTranslateEnabled.asStateFlow()

    private val _autoTranslateTargetLang = MutableStateFlow("en")
    val autoTranslateTargetLang: StateFlow<String> = _autoTranslateTargetLang.asStateFlow()

    private val _otherUserMetadata = MutableStateFlow<User?>(null)
    val otherUserMetadata = _otherUserMetadata.asStateFlow()
    
    private val _currentUserMetadata = MutableStateFlow<User?>(null)
    val currentUserMetadata = _currentUserMetadata.asStateFlow()
    
    private val _currentUserBadgesMetadata = MutableStateFlow<List<conectate.com.data.model.Badge>>(emptyList())
    val currentUserBadgesMetadata = _currentUserBadgesMetadata.asStateFlow()

    val forwardTargets: StateFlow<List<ForwardChatTarget>> =
        combine(
            messageRepository.getUserChatsFlow(),
            groupRepository.getGroupsFlow(),
            communityRepository.getCommunitiesFlow()
        ) { chats, groups, communities ->
            val currentChatId = chatId?.trim()?.lowercase()
            val targets = mutableListOf<ForwardChatTarget>()

            // Add chats (excluding current chat)
            chats.forEach { item ->
                val chat = item.chat
                val id = chat.id.trim().lowercase()
                if (id.isNotBlank() && id != currentChatId) {
                    val title = if (chat.is_group) {
                        chat.group_name?.ifBlank { null } ?: "Grupo"
                    } else {
                        chat.nickname
                            ?: item.nickname
                            ?: item.otherUserName
                            ?: "Chat"
                    }
                    targets.add(
                        ForwardChatTarget(
                            chatId = id,
                            title = title,
                            subtitle = if (chat.is_group) "Grupo" else "Chat privado"
                        )
                    )
                }
            }

            // Add groups
            groups.forEach { entry ->
                val group = entry.group
                targets.add(
                    ForwardChatTarget(
                        chatId = "group:${group.id}",
                        title = group.name,
                        subtitle = "Grupo"
                    )
                )
            }

            // Add communities
            communities.forEach { entry ->
                val community = entry.community
                targets.add(
                    ForwardChatTarget(
                        chatId = "community:${community.id}",
                        title = community.name,
                        subtitle = "Comunidad"
                    )
                )
            }

            targets.distinctBy { it.chatId }.sortedBy { it.title.lowercase() }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var mediaPlayer: android.media.MediaPlayer? = null
    private val audioPlaybackCache by lazy { ChatAudioPlaybackCache(File(context.cacheDir, "audio_messages")) }
    private var audioPlaybackGeneration = 0L
    private var progressJob: Job? = null
    private var forwardSearchJob: Job? = null

    private var messageCollectionJob: Job? = null
    private var presenceJob: Job? = null
    private var metadataJob: Job? = null
    private var chatSettingsJob: Job? = null
    private var blockRelationshipJob: Job? = null
    private var typingObserverJob: Job? = null
    private var typingStopJob: Job? = null
    private var typingActive = false

    init {
        voiceMessageManager.reset()
        var scheduledAccount = authService.getCurrentUserId()?.trim()?.lowercase()
        viewModelScope.launch {
            authService.currentSession.map { authService.getCurrentUserId()?.trim()?.lowercase() }
                .distinctUntilChanged().collect { account ->
                    if (account != scheduledAccount) scheduledController.clear()
                    scheduledAccount = account
                    if (account != ownerAccountId) {
                        _chatState.value = ChatUiState.Error("La cuenta cambió. Vuelve a abrir la conversación.")
                        textComposer.reset()
                        _editingMessage.value = null
                        _selectedMessage.value = null
                        _selectedMessages.value = emptySet()
                        _otherUserMetadata.value = null
                        _currentUserMetadata.value = null
                        _isBlocked.value = true
                        stopAudio()
                        voiceMessageManager.reset()
                        messageRepository.setActiveChatSession(null, false)
                        // Cancel old account loads as well as listeners before they can republish private data.
                        viewModelScope.coroutineContext.cancelChildren()
                    }
                }
        }
        
        initializeChat()
        updateMyOnlineStatus(true)
    }

    private fun updateMyOnlineStatus(isOnline: Boolean) {
        viewModelScope.launch {
            val uid = authService.getCurrentUserId() ?: return@launch
            userRepository.setOnlineStatus(uid, isOnline)
        }
    }

    private fun initializeChat() {
        metadataJob?.cancel()
        presenceJob?.cancel()
        viewModelScope.launch {
            runReadWithDeadline(InteractiveReadDeadlines.ConversationHistoryMillis) {
                initializeChatWithinDeadline()
            }.onFailure { error ->
                if (_chatState.value is ChatUiState.Loading) {
                    android.util.Log.w("ChatViewModel", "Conversation initialization failed", error)
                    _chatState.value = ChatUiState.Error(
                        "No se pudo abrir esta conversación. Revisa tu conexión e inténtalo nuevamente."
                    )
                }
            }
        }
    }

    private suspend fun initializeChatWithinDeadline() {
            if (otherUserId.isBlank()) {
                _chatState.value = ChatUiState.Error("No se pudo identificar esta conversación. Vuelve al perfil e inténtalo nuevamente.")
                return
            }
            val currentId = authService.getCurrentUserId()
            if (currentId.isNullOrBlank()) {
                _chatState.value = ChatUiState.Error("Sesion no valida. Inicia sesion de nuevo.")
                return
            }
            val argId = otherUserId 
            
            var resolvedChat: conectate.com.data.model.Chat? = null
            var targetUserId: String? = argId

            // 1. Try to treat as Chat ID first
            val chatResult = messageRepository.getChatById(argId)
            if (chatResult.isSuccess) {
                resolvedChat = chatResult.getOrNull()
            }
            
            // Extract Target User ID from Chat (if found) or use Arg (if it's a user ID)
            if (resolvedChat != null) {
                targetUserId = resolvedChat.participants.firstOrNull { it.trim().lowercase() != currentId.trim().lowercase() }
            } else {
                // Not a known Chat ID? Treat as User ID and check for existing chat
                resolvedTargetUserId = targetUserId
                val fallbackTargetId = targetUserId ?: argId

                // Canonical participants for 1-1 search
                val normalizedParticipants = listOf(currentId, fallbackTargetId).map { it.lowercase().trim() }.sorted()
                val findResult = messageRepository.findChatByParticipants(normalizedParticipants)
                
                if (findResult.isSuccess) {
                    resolvedChat = findResult.getOrNull()
                } else {
                    // NO CHAT EXISTS -> PLACEHOLDER MODE
                    isPlaceholder = true
                    resolvedChat = conectate.com.data.model.Chat(
                        id = "placeholder_${fallbackTargetId}",
                        participants = normalizedParticipants,
                        is_group = false,
                        last_message_at = null
                    )
                }
            }
            // SAFETY: In some weird navigation states, targetUserId might end up being the Chat ID.
            // We MUST ensure it's a User ID by checking if it matches the current chatId.
            if (targetUserId != null && targetUserId == resolvedChat?.id) {
                targetUserId = resolvedChat.participants.firstOrNull { it.trim().lowercase() != currentId.trim().lowercase() }
            }
            
            resolvedTargetUserId = targetUserId
            resetBlockState()
            
            if (resolvedChat != null) {
                chatId = resolvedChat.id

                val draftScope = if (resolvedChat.is_group) "group" else "chat"
                val draftRoom = if (isPlaceholder) targetUserId ?: resolvedChat.id else resolvedChat.id
                val restoredDraft = conversationDraftStore.readAndMigrate(ownerAccountId, draftScope, draftRoom, if (resolvedChat.is_group) null else targetUserId)
                textComposer.bindDraft(restoredDraft) { text ->
                    if (ownerAccountId == authService.getCurrentUserId()?.trim()?.lowercase()) {
                        conversationDraftStore.write(ownerAccountId, draftScope, draftRoom, text)
                    }
                }

                isDirectConversation = !resolvedChat.is_group
                _messageRequest.value = if (resolvedChat.is_group) conectate.com.data.model.DirectMessageRequestState("open")
                    else conectate.com.data.model.DirectMessageRequestState()
                refreshMessageRequest()
                val isRealChat = !isPlaceholder && !resolvedChat.id.startsWith("placeholder_", ignoreCase = true)
                val cleanChatId = chatId?.trim()?.lowercase()
                if (isRealChat && !cleanChatId.isNullOrBlank()) {
                    messageRepository.setActiveChatSession(chatId, _isScreenActive.value)
                }

                if (!resolvedChat.is_group && !targetUserId.isNullOrBlank()) {
                    startObservingBlockRelationship(targetUserId)
                } else {
                    blockRelationshipJob?.cancel()
                }
                
                // 3. Resolve initial profile from CACHE to avoid "Usuario" flickering
                val cachedOtherUser = if (!resolvedChat.is_group && targetUserId != null) {
                    messageRepository.getCachedUserProfile(resolvedChat.id) ?: 
                    messageRepository.getCachedUserProfileByUserId(targetUserId)
                } else null

                // Pre-fill metadata flows
                _otherUserMetadata.value = cachedOtherUser
                _currentNickname.value = cachedOtherUser?.display_name ?: resolvedChat.nickname

                if (isRealChat) {
                    // Start observing messages only for real chats. Placeholder chats must not touch server/Room.
                    startObservingMessages(resolvedChat.id, resolvedChat)
                    loadTranslationSettings(resolvedChat.id)
                    // Opening a conversation must not make an optional scheduled-jobs RPC.
                    scheduledMessagesKey()?.let { scheduledController.load(it, refreshRemote = false) }
                } else {
                    scheduledController.clear()
                    _chatState.value = ChatUiState.Success(
                        messages = emptyList(),
                        otherUser = cachedOtherUser,
                        currentUser = null,
                        chat = resolvedChat
                    )
                }

                // 4. Fetch Profiles and Badges in Parallel from Server (Refresh metadata)
                metadataJob = viewModelScope.launch {
                    val otherUserDeferred = async {
                        optionalConversationData {
                        if (!resolvedChat.is_group && targetUserId != null) {
                            var u = userRepository.getUserById(targetUserId).getOrNull()
                            if (u == null) u = messageRepository.getCachedUserProfile(resolvedChat.id)
                            if (u == null) u = messageRepository.getCachedUserProfileByUserId(targetUserId)
                            
                            if (u != null) {
                                val badgesResult = badgeRepository.getUserBadges(u.id)
                                if (badgesResult.isSuccess) {
                                    u = u.copy(badges = badgesResult.getOrThrow())
                                }
                            }
                            u
                        } else null
                        }
                    }

                    val currentUserDeferred = async {
                        optionalConversationData { userRepository.getUserById(currentId).getOrNull() }
                    }

                    val currentBadgesDeferred = async {
                        optionalConversationData { badgeRepository.getUserBadges(currentId).getOrNull() } ?: emptyList()
                    }

                    val otherUser = otherUserDeferred.await()
                    val currentUserObj = currentUserDeferred.await()
                    val currentBadges = currentBadgesDeferred.await()
                    if (ownerAccountId != authService.getCurrentUserId()?.trim()?.lowercase() ||
                        chatId != resolvedChat.id
                    ) return@launch
                    
                    val userWithBadges = currentUserObj?.copy(badges = currentBadges)

                    _otherUserMetadata.value = otherUser ?: _otherUserMetadata.value
                    _currentUserMetadata.value = userWithBadges
                    _currentUserBadgesMetadata.value = currentBadges

                    if (!isRealChat) {
                        _chatState.value = ChatUiState.Success(
                            messages = emptyList(),
                            otherUser = _otherUserMetadata.value,
                            currentUser = userWithBadges,
                            chat = resolvedChat,
                            currentUserBadges = currentBadges
                        )
                    }

                    // Observe presence in real-time
                    if (targetUserId != null) {
                        presenceJob?.cancel()
                        presenceJob = viewModelScope.launch {
                            userRepository.observeUserPresence(targetUserId)
                                .catch { error ->
                                    if (error is CancellationException) throw error
                                    android.util.Log.w("ChatViewModel", "Presence unavailable", error)
                                }.collectLatest { updatedUser ->
                                if (ownerAccountId != authService.getCurrentUserId()?.trim()?.lowercase() ||
                                    chatId != resolvedChat.id
                                ) return@collectLatest
                                _otherUserMetadata.update { current ->
                                    // Si current es null (porque falló el fetch inicial), creamos uno básico
                                    // con los datos frescos del polling
                                    val base = current ?: updatedUser
                                    base.copy(
                                        is_online = updatedUser.is_online,
                                        lastSeen = updatedUser.lastSeen
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. Populate Initial Message if provided (User will send manually)
                if (!initialMessageSent) {
                    initialMessageSent = true
                    if (textComposer.applyInitialText(initialMessage)) syncTypingPresence(initialMessage.orEmpty())
                }
            }
        }


    private fun startObservingMessages(id: String, initialChat: conectate.com.data.model.Chat) {
        val cleanId = id.trim().lowercase()
        observeChatBackgroundSettings(cleanId)
        observeTypingForChat(initialChat)
        messageCollectionJob?.cancel()
        messageCollectionJob = viewModelScope.launch {
            launch {
                val reported = mutableSetOf<String>()
                messageRepository.getMessageDeletionFailures(cleanId).collect { failures ->
                    failures.filter { reported.add(it.revision) }.forEach { failure ->
                        conectate.com.presentation.common.AppFeedbackBus.showError(
                            failure.error ?: "No se pudo eliminar el mensaje"
                        )
                    }
                }
            }
            
            // Combine Metadata and Messages for a stable Success state (WhatsApp Style)
            val groupMetadataFlow = groupRepository.getGroupByIdFlow(cleanId).onStart { emit(null as conectate.com.data.local.entity.GroupEntity?) }
            val chatMetadataFlow = messageRepository.getChatFlow(cleanId).onStart { emit(null as conectate.com.data.model.Chat?) }

            val messagesFlow = messageRepository.getMessagesFlow(cleanId)
            data class ChatBaseState(
                val group: conectate.com.data.local.entity.GroupEntity?,
                val chatMeta: conectate.com.data.model.Chat?,
                val messages: List<Message>,
                val otherUser: User?,
                val currentUser: User?
            )

            val metadataFlow = combine(
                groupMetadataFlow,
                chatMetadataFlow,
                messagesFlow,
                _otherUserMetadata,
                _currentUserMetadata
            ) { group, chatMeta, messages, otherUser, currentUser ->
                ChatBaseState(
                    group = group,
                    chatMeta = chatMeta,
                    messages = messages,
                    otherUser = otherUser,
                    currentUser = currentUser
                )
            }

            combine(metadataFlow, _currentUserBadgesMetadata) { base, badges ->
                val group = base.group
                val chatMeta = base.chatMeta
                val messages = base.messages
                val otherUser = base.otherUser
                val currentUser = base.currentUser

                val sorted = messages.sortedBy { it.sortKey }
                
                val enrichedChat = (chatMeta ?: initialChat).copy(
                    is_group = group?.is_member != null || initialChat.is_group
                )
                
                val isMember = if (enrichedChat.is_group) (group?.is_member ?: false) else true
                
                ChatUiState.Success(
                    messages = sorted,
                    otherUser = otherUser,
                    currentUser = currentUser,
                    chat = enrichedChat,
                    currentUserBadges = badges,
                    isMember = isMember
                )
            }.collect { successState ->
                _chatState.value = successState
                
                // Auto-read only while the chat screen is actively visible
                maybeMarkChatAsRead(cleanId, successState.messages, _visibleMessageIds.value)
                
                // Update nickname state for UI consistency
                _currentNickname.value = successState.chat?.nickname
            }
        }
    }

    private fun observeTypingForChat(chat: conectate.com.data.model.Chat) {
        typingObserverJob?.cancel()
        _typingParticipants.value = emptyList()

        val cleanChatId = chat.id.trim().lowercase()
        if (cleanChatId.isBlank() || chat.is_group) return

        typingObserverJob = viewModelScope.launch {
            typingRepository.observeTyping(
                scopeType = TypingConversationScope.DIRECT_MESSAGE,
                conversationId = cleanChatId
            ).collectLatest { participants ->
                _typingParticipants.value = participants
            }
        }
    }

    private fun syncTypingPresence(text: String) {
        val activeChatId = chatId?.trim()?.lowercase() ?: return
        val state = chatState.value as? ChatUiState.Success ?: return
        if (state.chat?.is_group == true) return

        val trimmed = text.trim()
        typingStopJob?.cancel()

        if (trimmed.isEmpty()) {
            stopTypingPresence()
            return
        }

        if (!typingActive) {
            sendTypingPresence(isTyping = true)
        }

        typingStopJob = viewModelScope.launch {
            delay(2800)
            if (_messageText.value.trim().isNotEmpty() && activeChatId == chatId?.trim()?.lowercase()) {
                stopTypingPresence()
            }
        }
    }

    private fun sendTypingPresence(isTyping: Boolean) {
        val activeChatId = chatId?.trim()?.lowercase() ?: return
        val state = chatState.value as? ChatUiState.Success ?: return
        if (state.chat?.is_group == true) return

        val displayName = currentUserMetadata.value?.displayName
            ?: currentUserMetadata.value?.username
            ?: authService.getCurrentUserEmail()?.substringBefore("@")
            ?: "Usuario"

        typingActive = isTyping
        viewModelScope.launch {
            typingRepository.sendTyping(
                scopeType = TypingConversationScope.DIRECT_MESSAGE,
                conversationId = activeChatId,
                isTyping = isTyping,
                displayName = displayName
            )
        }
    }

    private fun stopTypingPresence() {
        typingStopJob?.cancel()
        if (!typingActive) return
        sendTypingPresence(isTyping = false)
    }

    private fun observeChatBackgroundSettings(chatId: String) {
        val cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return
        chatSettingsJob?.cancel()
        chatSettingsJob = viewModelScope.launch {
            chatSettingsRepository.observeChatBackground(cleanId).collectLatest { settings ->
                _chatBackgroundSettings.value = settings
            }
        }
    }

    fun setChatBackground(backgroundType: String, backgroundUri: String? = null) {
        val cleanId = chatId?.trim()?.lowercase() ?: return
        viewModelScope.launch {
            chatSettingsRepository.setChatBackground(cleanId, backgroundType, backgroundUri)
        }
    }

    fun clearChatBackground() {
        val cleanId = chatId?.trim()?.lowercase() ?: return
        viewModelScope.launch {
            chatSettingsRepository.clearChatBackground(cleanId)
        }
    }

    private fun loadTranslationSettings(chatId: String) {
        viewModelScope.launch {
            val setting = advancedMessagingRepository.getChatTranslation(chatId)
            _autoTranslateEnabled.value = setting?.enabled ?: false
            _autoTranslateTargetLang.value = setting?.target_lang?.ifBlank { "en" } ?: "en"
        }
    }

    fun clearAdvancedMessage() {
        _advancedMessage.value = null
    }

    fun dismissChatSummary() {
        _chatSummary.value = null
    }

    private fun resetBlockState() {
        _isBlocked.value = false
        _isBlockedByMe.value = false
        _isBlockedByOther.value = false
    }

    private fun applyBlockRelationship(
        relationship: BlockRelationship,
        announceWhenBlockedByOther: Boolean = true
    ) {
        val wasBlockedByOther = _isBlockedByOther.value
        _isBlockedByMe.value = relationship.blockedByMe
        _isBlockedByOther.value = relationship.blockedMe
        _isBlocked.value = relationship.isBlocked
        if (announceWhenBlockedByOther && !wasBlockedByOther && relationship.blockedMe) {
            _advancedMessage.value = "No puedes enviar mensajes a este usuario."
        }
    }

    private fun startObservingBlockRelationship(targetUserId: String) {
        val cleanTarget = targetUserId.trim().lowercase()
        if (cleanTarget.isBlank()) {
            blockRelationshipJob?.cancel()
            resetBlockState()
            return
        }

        blockRelationshipJob?.cancel()
        blockRelationshipJob = viewModelScope.launch {
            userRepository.observeBlockRelationship(cleanTarget).collectLatest { relation ->
                applyBlockRelationship(relation)
            }
        }
    }

    private fun normalizeSendError(raw: String): String {
        val clean = raw.trim()
        return if (clean.contains("MESSAGE_REQUEST_PENDING", ignoreCase = true)) {
            _messageRequest.value = conectate.com.data.model.DirectMessageRequestState("pending_outgoing")
            "Espera a que acepte o responda tu solicitud."
        } else if (clean.contains("MESSAGE_REQUEST_REJECTED", ignoreCase = true)) {
            _messageRequest.value = conectate.com.data.model.DirectMessageRequestState("rejected")
            "La solicitud de mensaje fue rechazada."
        } else if (clean.contains("chat_blocked_by_recipient", ignoreCase = true)) {
            applyBlockRelationship(
                relationship = BlockRelationship(
                    blockedByMe = _isBlockedByMe.value,
                    blockedMe = true
                )
            )
            "No puedes enviar mensajes a este usuario."
        } else if (
            clean.contains("unable to resolve host", ignoreCase = true) ||
            clean.contains("unknown host", ignoreCase = true) ||
            clean.contains("failed to connect", ignoreCase = true) ||
            clean.contains("network", ignoreCase = true)
        ) {
            "Sin conexión. El mensaje quedó guardado y se enviará automáticamente."
        } else if (clean.contains("job was cancelled", ignoreCase = true)) {
            "El envío quedó en espera y continuará automáticamente."
        } else if (clean.isBlank()) {
            "No se pudo completar el envío."
        } else {
            "No se pudo enviar el mensaje. Inténtalo nuevamente."
        }
    }

    private fun showCreateChatError(error: Throwable?) {
        val message = normalizeSendError(error?.message.orEmpty())
        android.util.Log.e("ChatViewModel", "Create chat failed: $message", error)
        conectate.com.presentation.common.AppFeedbackBus.showError("Error al crear chat: $message")
    }

    fun updateMessageText(text: String) {
        if (ownerAccountId != authService.getCurrentUserId()?.trim()?.lowercase()) return
        textComposer.updateText(text)
        syncTypingPresence(text)
    }

    fun sendTextMessage(forcedContent: String? = null) {
        if (!ensureNotBlockedForSending()) return
        if (chatId == null) return
        val submission = textComposer.beginSend(forcedContent) ?: return
        reserveIntroduction()
        viewModelScope.launch {
            
            try {
                // 1. Create chat if it's a placeholder
                if (isPlaceholder) {
                    val targetId = resolvedTargetUserId ?: otherUserId
                    val createResult = messageRepository.getOrCreateChat(targetId)
                    if (createResult.isSuccess) {
                        val newChat = createResult.getOrThrow()
                        chatId = newChat.id
                        messageRepository.setActiveChatSession(chatId, _isScreenActive.value)
                        isPlaceholder = false
                        // Restart observation so we get real-time messages for the now-valid chat
                        startObservingMessages(newChat.id, newChat)
                    } else {
                        showCreateChatError(createResult.exceptionOrNull())
                        return@launch
                    }
                }

                val cid = chatId ?: return@launch
                val outboundText = advancedMessagingRepository.applyAutoTranslation(cid, submission.text)
                if (ownerAccountId != authService.getCurrentUserId()?.trim()?.lowercase()) return@launch
                val result = messageRepository.sendMessage(
                    chatId = cid,
                    content = outboundText,
                    replyToId = submission.replyTo?.id,
                    onQueued = {
                        withContext(Dispatchers.Main.immediate) {
                            if (ownerAccountId == authService.getCurrentUserId()?.trim()?.lowercase()) {
                                textComposer.queued(submission)
                                if (_messageText.value.isBlank()) stopTypingPresence()
                            } else {
                                textComposer.finished(submission)
                            }
                        }
                    }
                )
                if (result.isFailure && ownerAccountId == authService.getCurrentUserId()?.trim()?.lowercase()) {
                    val error = normalizeSendError(result.exceptionOrNull()?.message.orEmpty())
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo enviar: $error")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Send error", e)
                conectate.com.presentation.common.AppFeedbackBus.showError("Error al enviar mensaje")
            } finally {
                textComposer.finished(submission)
            }
        }
    }

    fun sendMediaMessage(uri: Uri, messageType: String, content: String = "") {
        if (!ensureNotBlockedForSending()) return
        if (chatId == null) return
        val replySnapshot = textComposer.captureReply()
        reserveIntroduction()
        viewModelScope.launch {
            _isSending.value = true
            try {
                if (isPlaceholder) {
                    val targetId = resolvedTargetUserId ?: otherUserId
                    val createResult = messageRepository.getOrCreateChat(targetId)
                    if (createResult.isSuccess) {
                        val newChat = createResult.getOrThrow()
                        chatId = newChat.id
                        messageRepository.setActiveChatSession(chatId, _isScreenActive.value)
                        isPlaceholder = false
                        startObservingMessages(newChat.id, newChat)
                    } else {
                        showCreateChatError(createResult.exceptionOrNull())
                        return@launch
                    }
                }

                val cid = chatId ?: return@launch
                val outboundContent = if (messageType in setOf("document", "file")) {
                    val providerName = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            }
                        }.getOrNull() ?: uri.lastPathSegment
                    }
                    conectate.com.util.documentMessageLabel(content, providerName)
                } else content.trim().ifBlank { defaultMediaCaption(messageType) }
                val result = messageRepository.sendMessage(
                    chatId = cid,
                    content = outboundContent,
                    mediaUri = uri,
                    mediaType = messageType,
                    replyToId = replySnapshot.replyTo?.id,
                    onQueued = { withContext(Dispatchers.Main.immediate) { textComposer.queuedReply(replySnapshot) } }
                )
                if (result.isFailure) {
                    val error = normalizeSendError(result.exceptionOrNull()?.message.orEmpty())
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo enviar archivo: $error")
                } else if (messageType == "audio") {
                    val sent = result.getOrNull()
                    val mediaUrl = sent?.media_url?.trim().orEmpty()
                    if (mediaUrl.startsWith("http", ignoreCase = true)) {
                        cacheAudioFromUri(mediaUrl, uri)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Media send error", e)
                conectate.com.presentation.common.AppFeedbackBus.showError("Error al enviar archivo")
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendMediaBatch(items: List<OutgoingMessageMedia>, caption: String = "") {
        if (!ensureNotBlockedForSending()) return
        val mediaItems = items.filter { it.uri != Uri.EMPTY && it.type.isNotBlank() }.let { if (_messageRequest.value.status == "new") it.take(1) else it }
        if (mediaItems.isEmpty()) return
        val replySnapshot = textComposer.captureReply()
        reserveIntroduction()
        viewModelScope.launch {
            _isSending.value = true
            try {
                if (isPlaceholder) {
                    val targetId = resolvedTargetUserId ?: otherUserId
                    val createResult = messageRepository.getOrCreateChat(targetId)
                    if (createResult.isSuccess) {
                        val newChat = createResult.getOrThrow()
                        chatId = newChat.id
                        messageRepository.setActiveChatSession(chatId, _isScreenActive.value)
                        isPlaceholder = false
                        startObservingMessages(newChat.id, newChat)
                    } else {
                        showCreateChatError(createResult.exceptionOrNull())
                        return@launch
                    }
                }

                val cid = chatId ?: return@launch
                var failedCount = 0
                mediaItems.forEachIndexed { index, outgoing ->
                    val messageType = outgoing.type.trim().lowercase()
                    val outboundContent = when {
                        index == 0 && caption.isNotBlank() -> caption.trim()
                        mediaItems.size > 1 -> ""
                        else -> defaultMediaCaption(messageType)
                    }
                    val result = messageRepository.sendMessage(
                        chatId = cid,
                        content = outboundContent,
                        mediaUri = outgoing.uri,
                        mediaType = messageType,
                        allowRapidMediaBatch = mediaItems.size > 1,
                        replyToId = replySnapshot.replyTo?.id,
                        onQueued = { withContext(Dispatchers.Main.immediate) { textComposer.queuedReply(replySnapshot) } }
                    )
                    if (result.isFailure) {
                        failedCount += 1
                        android.util.Log.w(
                            "ChatViewModel",
                            "Batch media send failed at index=$index: ${result.exceptionOrNull()?.message}"
                        )
                    }
                }

                if (failedCount > 0) {
                    val sentCount = mediaItems.size - failedCount
                    val message = if (sentCount > 0) {
                        "Se enviaron $sentCount de ${mediaItems.size} archivos."
                    } else {
                        "No se pudieron enviar los archivos."
                    }
                    conectate.com.presentation.common.AppFeedbackBus.showError(message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Batch media send error", e)
                conectate.com.presentation.common.AppFeedbackBus.showError("Error al enviar archivos")
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendDocumentMessage(uri: Uri, caption: String = "") {
        sendMediaMessage(uri = uri, messageType = "document", content = caption)
    }

    fun sendGifMessage(uri: Uri, caption: String = "🌀 GIF") {
        sendMediaMessage(uri = uri, messageType = "gif", content = caption)
    }

    fun sendRecentGif(mediaUrl: String) {
        if (!ensureNotBlockedForSending()) return
        val cid = chatId ?: return
        reserveIntroduction()
        viewModelScope.launch {
            _isSending.value = true
            val result = messageRepository.sendMessage(
                chatId = cid,
                content = "🌀 GIF",
                mediaType = "gif",
                existingMediaUrl = mediaUrl.trim()
            )
            if (result.isFailure) {
                conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo reenviar el GIF")
            }
            _isSending.value = false
        }
    }

    fun sendStickerMessage(uri: Uri, caption: String = "🧩 Sticker") {
        sendMediaMessage(uri = uri, messageType = "sticker", content = caption)
    }

    fun sendStickerText(sticker: String) {
        sendStructuredMessage(
            content = sticker.trim().ifBlank { "🧩 Sticker" },
            contentType = "sticker",
            errorPrefix = "No se pudo enviar sticker"
        )
    }

    fun sendStickerFromUrl(mediaUrl: String, caption: String = "") {
        if (!ensureNotBlockedForSending()) return
        if (_isSending.value) return
        val cleanUrl = mediaUrl.trim()
        if (cleanUrl.isBlank()) {
            conectate.com.presentation.common.AppFeedbackBus.showError("Sticker inválido")
            return
        }
        val replySnapshot = textComposer.captureReply()
        reserveIntroduction()
        viewModelScope.launch {
            _isSending.value = true
            try {
                if (isPlaceholder) {
                    val createResult = messageRepository.getOrCreateChat(resolvedTargetUserId ?: otherUserId)
                    if (createResult.isSuccess) {
                        val newChat = createResult.getOrThrow()
                        chatId = newChat.id
                        messageRepository.setActiveChatSession(chatId, _isScreenActive.value)
                        isPlaceholder = false
                        startObservingMessages(newChat.id, newChat)
                    } else {
                        showCreateChatError(createResult.exceptionOrNull())
                        return@launch
                    }
                }

                val cid = chatId ?: return@launch
                val result = messageRepository.sendMessage(
                    chatId = cid,
                    content = caption,
                    mediaType = "sticker",
                    existingMediaUrl = cleanUrl,
                    replyToId = replySnapshot.replyTo?.id,
                    onQueued = { withContext(Dispatchers.Main.immediate) { textComposer.queuedReply(replySnapshot) } }
                )
                if (result.isFailure) {
                    val error = normalizeSendError(result.exceptionOrNull()?.message.orEmpty())
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo enviar sticker: $error")
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Sticker send error", e)
                conectate.com.presentation.common.AppFeedbackBus.showError("Error al enviar sticker")
            } finally {
                _isSending.value = false
            }
        }
    }

    val favoriteStickerMediaKeys = stickerRepository.favoriteMediaKeys

    fun isStickerFavorite(mediaUrl: String?, mediaKeys: Set<String>): Boolean =
        conectate.com.data.repository.StickerIdentity.mediaKey(mediaUrl) in mediaKeys

    fun toggleStickerFavorite(message: Message) {
        val url = message.media_url?.trim().orEmpty()
        if (url.isBlank()) {
            conectate.com.presentation.common.AppFeedbackBus.showError("Sticker sin archivo para guardar.")
            return
        }
        viewModelScope.launch {
            val change = if (stickerRepository.isFavoriteMediaUrl(url)) {
                stickerRepository.removeFavoriteByMedia(url)
            } else {
                stickerRepository.addFavoriteByMedia(url, message.content.ifBlank { "Sticker" })
            }
            Toast.makeText(
                context,
                when (change) {
                    StickerRepository.FavoriteChange.ADDED -> "Sticker añadido a favoritos"
                    StickerRepository.FavoriteChange.ALREADY_PRESENT -> "Ya tienes este sticker en favoritos"
                    StickerRepository.FavoriteChange.REMOVED -> "Sticker quitado de favoritos"
                    else -> "No se pudo comprobar el sticker. Inténtalo de nuevo"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun retryFailedSticker(message: Message) = retryMessage(message)

    fun retryMessage(message: Message) {
        val ownerId = authService.getCurrentUserId().orEmpty()
        if (message.status !in setOf("failed", "sending", "pending") ||
            !message.sender_id.equals(ownerId, ignoreCase = true) ||
            chatId?.equals(message.chat_id, ignoreCase = true) != true) return
        if (!ensureNotBlockedForSending(allowPendingRetry = true) || _isSending.value) return
        viewModelScope.launch {
            _isSending.value = true
            try {
                // Reuse the original ID so a delayed server acknowledgement cannot
                // produce a duplicate after the user taps retry.
                val result = messageRepository.retryMessage(message.id, message.chat_id)
                if (result.isFailure) {
                    conectate.com.presentation.common.AppFeedbackBus.showError(
                        "No se pudo reenviar: ${normalizeSendError(result.exceptionOrNull()?.message.orEmpty())}"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w("ChatViewModel", "Sticker retry failed", error)
                conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo reenviar. Inténtalo otra vez.")
            } finally {
                _isSending.value = false
            }
        }
    }

    fun sendContactMessage(
        name: String,
        phone: String,
        email: String? = null,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList()
    ) {
        val contactName = name.trim()
        val contactPhone = phone.trim()
        val contactEmail = email?.trim()?.lowercase()?.ifBlank { null }
        val normalizedPhones = (phones + contactPhone)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val normalizedEmails = (emails + listOfNotNull(contactEmail))
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (contactName.isBlank() || (normalizedPhones.isEmpty() && normalizedEmails.isEmpty())) {
            conectate.com.presentation.common.AppFeedbackBus.showError(
                "Completa nombre y al menos teléfono o correo"
            )
            return
        }
        val payload = ChatContactPayload(
            name = contactName,
            phone = normalizedPhones.firstOrNull(),
            email = normalizedEmails.firstOrNull(),
            phones = normalizedPhones,
            emails = normalizedEmails
        )
        sendStructuredMessage(
            content = encodeContactPayload(payload),
            contentType = "contact",
            errorPrefix = "No se pudo enviar contacto"
        )
    }

    suspend fun resolveRegisteredContactUserId(payload: ChatContactPayload): String? {
        val byEmail = payload.normalizedEmail
            ?.let { userRepository.getUserByEmail(it).getOrNull() }
            ?.id
            ?.trim()
            ?.ifBlank { null }
        if (!byEmail.isNullOrBlank()) return byEmail

        val byPhone = payload.normalizedPhone
            ?.let { userRepository.getUserByPhone(it).getOrNull() }
            ?.id
            ?.trim()
            ?.ifBlank { null }
        if (!byPhone.isNullOrBlank()) return byPhone

        val byUsername = payload.username
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { userRepository.getUserByUsername(it).getOrNull() }
            ?.id
            ?.trim()
            ?.ifBlank { null }
        return byUsername
    }

    fun sendLocationMessage(latitude: String, longitude: String, label: String?) {
        val lat = latitude.trim()
        val lon = longitude.trim()
        if (lat.isBlank() || lon.isBlank()) {
            conectate.com.presentation.common.AppFeedbackBus.showError("Completa latitud y longitud")
            return
        }
        val marker = label?.trim().orEmpty()
        val mapUrl = "https://maps.google.com/?q=$lat,$lon"
        val content = if (marker.isNotBlank()) {
            "📍 $marker\n$mapUrl"
        } else {
            "📍 Ubicación compartida\n$mapUrl"
        }
        sendStructuredMessage(
            content = content,
            contentType = "location",
            errorPrefix = "No se pudo enviar ubicación",
            mediaUrl = mapUrl
        )
    }

    fun setAutoTranslation(enabled: Boolean, targetLang: String) {
        val cid = chatId ?: return
        val cleanLang = normalizeLanguageCode(targetLang) ?: "en"
        if (enabled && !advancedMessagingRepository.isSecureTranslationAvailable()) {
            _autoTranslateEnabled.value = false
            _advancedMessage.value = "La traducción automática aún no está disponible en producción."
            return
        }
        viewModelScope.launch {
            val result = advancedMessagingRepository.setChatTranslation(
                chatId = cid,
                enabled = enabled,
                targetLang = cleanLang
            )
            if (result.isSuccess) {
                _autoTranslateEnabled.value = enabled
                _autoTranslateTargetLang.value = cleanLang
                _advancedMessage.value = if (enabled) {
                    "Traducción automática activada ($cleanLang)."
                } else {
                    "Traducción automática desactivada."
                }
            } else {
                _advancedMessage.value = "No se pudo actualizar traducción."
            }
        }
    }

    fun scheduleCurrentTextMessage(
        delayMinutes: Int,
        selfDestructSeconds: Int? = null,
        translateTo: String? = null
    ) {
        if (_messageRequest.value.status != "open") {
            _advancedMessage.value = "Acepta la solicitud de mensaje antes de programar envíos."
            return
        }
        if (!ensureNotBlockedForSending()) return
        if (_isSending.value) return
        val text = _messageText.value.trim()
        if (text.isBlank()) {
            _advancedMessage.value = "Escribe un mensaje para programarlo."
            return
        }
        val whenMs = System.currentTimeMillis() + (delayMinutes.coerceAtLeast(1) * 60_000L)
        scheduleTextMessageInternal(
            text = text,
            whenMs = whenMs,
            selfDestructSeconds = selfDestructSeconds,
            translateTo = translateTo
        )
    }

    fun scheduleCurrentTextMessageAt(
        content: String? = null,
        dateIso: String,
        time24h: String,
        selfDestructSeconds: Int? = null,
        translateTo: String? = null
    ) {
        if (_messageRequest.value.status != "open") {
            _advancedMessage.value = "Acepta la solicitud de mensaje antes de programar envíos."
            return
        }
        if (!ensureNotBlockedForSending()) return
        val text = (content ?: _messageText.value).trim()
        if (text.isBlank()) {
            _advancedMessage.value = "Escribe un mensaje para programarlo."
            return
        }
        val scheduledMs = parseDateTimeToEpochMillis(dateIso, time24h)
        if (scheduledMs == null) {
            _advancedMessage.value = "Fecha u hora inválida. Usa formato 2026-02-25 y 14:30."
            return
        }
        if (scheduledMs <= System.currentTimeMillis()) {
            _advancedMessage.value = "La fecha/hora programada debe ser futura."
            return
        }
        scheduleTextMessageInternal(
            text = text,
            whenMs = scheduledMs,
            selfDestructSeconds = selfDestructSeconds,
            translateTo = translateTo
        )
    }

    fun scheduleMediaMessageAt(
        mediaUri: Uri,
        messageType: String,
        content: String? = null,
        dateIso: String,
        time24h: String,
        selfDestructSeconds: Int? = null,
        translateTo: String? = null
    ) {
        if (_messageRequest.value.status != "open") {
            _advancedMessage.value = "Acepta la solicitud de mensaje antes de programar envíos."
            return
        }
        if (!ensureNotBlockedForSending()) return
        val scheduledMs = parseDateTimeToEpochMillis(dateIso, time24h)
        if (scheduledMs == null) {
            _advancedMessage.value = "Fecha u hora inválida. Usa formato 2026-02-25 y 14:30."
            return
        }
        if (scheduledMs <= System.currentTimeMillis()) {
            _advancedMessage.value = "La fecha/hora programada debe ser futura."
            return
        }

        viewModelScope.launch {
            if (isPlaceholder) {
                val createResult = messageRepository.getOrCreateChat(resolvedTargetUserId ?: otherUserId)
                if (createResult.isFailure) {
                    _advancedMessage.value = "No se pudo crear chat para programar multimedia."
                    return@launch
                }
                val newChat = createResult.getOrThrow()
                chatId = newChat.id
                messageRepository.setActiveChatSession(chatId, _isScreenActive.value)
                isPlaceholder = false
                startObservingMessages(newChat.id, newChat)
                loadTranslationSettings(newChat.id)
            }

            val cid = chatId ?: return@launch
            val normalizedTranslate = normalizeLanguageCode(translateTo)
            if (!translateTo.isNullOrBlank() && normalizedTranslate == null) {
                _advancedMessage.value = "Idioma inválido. Usa formato como en, es o pt-br."
                return@launch
            }

            val upload = messageRepository.uploadChatMediaForScheduledMessage(
                chatId = cid,
                mediaUri = mediaUri,
                mediaType = messageType
            )
            if (upload.isFailure) {
                _advancedMessage.value = upload.exceptionOrNull()?.message ?: "No se pudo preparar multimedia."
                return@launch
            }

            val caption = content?.trim()?.ifBlank { null }
                ?: _messageText.value.trim().ifBlank { defaultScheduleCaption(messageType) }
            val scheduled = advancedMessagingRepository.scheduleMessageJob(
                scopeType = "dm",
                targetId = cid,
                content = caption,
                contentType = messageType,
                mediaUrl = upload.getOrThrow(),
                scheduledForEpochMs = scheduledMs,
                translateTo = normalizedTranslate,
                selfDestructSeconds = selfDestructSeconds?.takeIf { it > 0 }
            )

            if (scheduled.isSuccess) {
                textComposer.updateText("")
                stopTypingPresence()
                refreshScheduledMessages()
                val scheduledAt = runCatching {
                    Instant.ofEpochMilli(scheduledMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                        .toString()
                        .replace('T', ' ')
                }.getOrElse { "futuro" }
                _advancedMessage.value = "Multimedia programada para $scheduledAt."
            } else {
                _advancedMessage.value = scheduled.exceptionOrNull()?.message ?: "No se pudo programar multimedia."
            }
        }
    }

    private fun scheduledMessagesKey(): ScheduledMessagesKey? {
        val cid = chatId?.trim()?.lowercase()
        if (cid.isNullOrBlank() || isPlaceholder || cid.startsWith("placeholder_")) {
            return null
        }
        return ScheduledMessagesKey.create(authService.getCurrentUserId(), "dm", cid)
    }

    fun refreshScheduledMessages() {
        val key = scheduledMessagesKey()
        if (key == null) {
            scheduledController.clear()
        } else {
            scheduledController.load(key, refreshRemote = true)
        }
    }

    fun stopRefreshingScheduledMessages() = scheduledController.stop()

    fun cancelScheduledMessage(jobId: String) {
        val key = scheduledMessagesKey() ?: return
        if (!scheduledController.beginCancellation(key, jobId)) return
        viewModelScope.launch {
            try {
                val result = advancedMessagingRepository.cancelScheduledJob(jobId, key.accountId)
                if (scheduledMessagesKey() != key) return@launch
                if (result.isSuccess) {
                    _advancedMessage.value = "Mensaje programado cancelado."
                    refreshScheduledMessages()
                } else {
                    scheduledController.showActionError(key, "No se pudo confirmar la cancelación. Revisa tu conexión y reintenta.")
                }
            } finally {
                scheduledController.finishCancellation(key, jobId)
            }
        }
    }

    fun generateChatSummary() {
        val cid = chatId ?: return
        val state = chatState.value as? ChatUiState.Success
        val currentMessages = state?.messages.orEmpty()
        val expectedParticipants = if (state?.chat?.is_group == true) null else 2
        viewModelScope.launch {
            val summaryResult = advancedMessagingRepository.summarizeChat(
                chatId = cid,
                messages = currentMessages,
                expectedParticipants = expectedParticipants
            )
            if (summaryResult.isSuccess) {
                _chatSummary.value = summaryResult.getOrNull()
            } else {
                _advancedMessage.value = "No se pudo generar resumen."
            }
        }
    }

    private fun sendStructuredMessage(
        content: String,
        contentType: String,
        errorPrefix: String,
        mediaUrl: String? = null
    ) {
        if (!ensureNotBlockedForSending()) return
        if (_isSending.value) return
        val replySnapshot = textComposer.captureReply()
        reserveIntroduction()
        viewModelScope.launch {
            _isSending.value = true
            try {
                if (isPlaceholder) {
                    val targetId = resolvedTargetUserId ?: otherUserId
                    val createResult = messageRepository.getOrCreateChat(targetId)
                    if (createResult.isSuccess) {
                        val newChat = createResult.getOrThrow()
                        chatId = newChat.id
                        messageRepository.setActiveChatSession(chatId, _isScreenActive.value)
                        isPlaceholder = false
                        startObservingMessages(newChat.id, newChat)
                    } else {
                        showCreateChatError(createResult.exceptionOrNull())
                        return@launch
                    }
                }

                val cid = chatId ?: return@launch
                val result = messageRepository.sendMessage(
                    chatId = cid,
                    content = content,
                    mediaType = contentType,
                    existingMediaUrl = mediaUrl,
                    replyToId = replySnapshot.replyTo?.id,
                    onQueued = { withContext(Dispatchers.Main.immediate) { textComposer.queuedReply(replySnapshot) } }
                )
                if (result.isFailure) {
                    val error = normalizeSendError(result.exceptionOrNull()?.message.orEmpty())
                    conectate.com.presentation.common.AppFeedbackBus.showError("$errorPrefix: $error")
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Structured send error ($contentType)", e)
                conectate.com.presentation.common.AppFeedbackBus.showError(errorPrefix)
            } finally {
                _isSending.value = false
            }
        }
    }

    // Audio Logic
    fun playAudio(message: Message) {
        val cid = message.id
        val url = message.media_url ?: return

        // Stop draft preview if active
        voiceMessageManager.pausePreview()

        if (_currentPlayingAudioId.value == cid) {
            // Already playing or paused this one
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    _isAudioPlaying.value = false
                } else {
                    applyAudioPlaybackSpeed(it)
                    it.start()
                    _isAudioPlaying.value = true
                    startProgressTracker()
                }
                return
            }
        }

        // Play new audio
        stopAudio()
        val generation = audioPlaybackGeneration
        viewModelScope.launch {
            try {
                val playableUrl = getPlayableUrl(url) ?: return@launch
                if (generation != audioPlaybackGeneration) return@launch
                playUrl(playableUrl, cid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error playing audio", e)
                stopAudio()
            }
        }
    }
    
    // Delegate to Manager
    fun startRecording() {
        stopAudio() // Stop playback of other messages
        voiceMessageManager.startRecording()
    }
    
    fun pauseRecording() {
        voiceMessageManager.pauseRecording()
    }
    
    fun resumeRecording() {
        voiceMessageManager.resumeRecording()
    }
    
    fun cancelRecording() {
        voiceMessageManager.cancelRecording()
    }
    
    fun playDraftPreview() {
        // Stop other audio
        stopAudio()
        voiceMessageManager.playPreview()
    }
    
    fun pauseDraftPreview() {
        voiceMessageManager.pausePreview()
    }
    
    fun seekDraftPreview(fraction: Float) {
        voiceMessageManager.seekPreview(fraction)
    }

    fun stopRecording(send: Boolean = false) {
        if (!send) {
            // Just stop and review
            voiceMessageManager.stopRecordingForReview()
        } else {
            viewModelScope.launch {
                val (file, duration) = voiceMessageManager.stopAndGetFile()
                if (file != null) {
                    val uri = Uri.fromFile(file)
                    val durationLabel = voiceMessageManager.formatDuration(duration)
                    sendMediaMessage(uri, "audio", durationLabel)
                }
            }
        }
    }
    
    // Explicit send from preview
    fun sendCurrentRecording() {
         val file = voiceMessageManager.getCurrentFile()
         val duration = recordingDuration.value
         
         if (file != null) {
            val uri = Uri.fromFile(file)
            val durationLabel = voiceMessageManager.formatDuration(duration)
            sendMediaMessage(uri, "audio", durationLabel)
            voiceMessageManager.reset()
         }
    }
    
    private fun playUrl(url: String, id: String) {
        val generation = audioPlaybackGeneration
        viewModelScope.launch {
            try {
                val source = resolveAudioPlaybackSource(url)
                if (generation != audioPlaybackGeneration) return@launch
                playResolvedAudioSource(source, id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation != audioPlaybackGeneration) return@launch
                appMetrics.recordFailure("chat_audio_playback", error)
                stopAudio()
                conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo reproducir el audio. Inténtalo de nuevo.")
            }
        }
    }

    private suspend fun resolveAudioPlaybackSource(url: String): String = withContext(Dispatchers.IO) {
        val cleanUrl = UbuntuMedia.resolveUrl(url) ?: url
        if (!cleanUrl.startsWith("http", ignoreCase = true)) return@withContext cleanUrl

        audioPlaybackCache.download(cleanUrl).absolutePath
    }

    private fun playResolvedAudioSource(source: String, id: String) {
        try {
            stopAudio()
            mediaPlayer = android.media.MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnPreparedListener {
                    _currentPlayingAudioId.value = id
                    _audioDuration.value = duration
                    applyAudioPlaybackSpeed(this)
                    start()
                    _isAudioPlaying.value = true
                    startProgressTracker()
                }
                setOnCompletionListener {
                    stopAudio()
                }
                setOnErrorListener { _, _, _ ->
                    stopAudio()
                    conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo reproducir el audio. Inténtalo de nuevo.")
                    true
                }
                setDataSource(source)
                prepareAsync()
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Audio message playback failed", e)
            appMetrics.recordFailure("chat_audio_playback", e)
            stopAudio()
        }
    }

    private fun cacheAudioFromUri(mediaUrl: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    audioPlaybackCache.store(UbuntuMedia.resolveUrl(mediaUrl) ?: mediaUrl, input)
                }
            }.onFailure {
                android.util.Log.w("ChatViewModel", "Could not cache sent audio", it)
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        val progress = it.currentPosition.toFloat() / it.duration.toFloat()
                        _audioProgress.value = progress
                        _audioDurationLabel.value = formatDuration(it.currentPosition.toLong())
                    }
                }
                delay(100)
            }
        }
    }

    fun seekAudio(fraction: Float) {
        val player = mediaPlayer ?: return
        if (_currentPlayingAudioId.value == null || player.duration <= 0) return
        val safeFraction = fraction.coerceIn(0f, 1f)
        player.seekTo((player.duration * safeFraction).toInt())
        _audioProgress.value = safeFraction
        _audioDurationLabel.value = formatDuration(player.currentPosition.toLong())
    }

    fun cycleAudioPlaybackSpeed() {
        val next = when (_audioPlaybackSpeed.value) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        _audioPlaybackSpeed.value = next
        mediaPlayer?.let(::applyAudioPlaybackSpeed)
    }

    private fun applyAudioPlaybackSpeed(player: android.media.MediaPlayer) {
        runCatching {
            player.playbackParams = player.playbackParams
                .setSpeed(_audioPlaybackSpeed.value)
                .setPitch(1f)
        }
    }

    fun retryInitializeChat() {
        if (_chatState.value !is ChatUiState.Error) return
        isPlaceholder = false
        _chatState.value = ChatUiState.Loading
        initializeChat()
    }

    private fun stopAudio() {
        audioPlaybackGeneration++
        progressJob?.cancel()
        _currentPlayingAudioId.value = null
        _isAudioPlaying.value = false
        _audioProgress.value = 0f
        _audioDurationLabel.value = "0:00"
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun formatDuration(ms: Long): String {
        return voiceMessageManager.formatDuration(ms)
    }

    fun onScreenVisibilityChanged(isVisible: Boolean) {
        if (ownerAccountId != authService.getCurrentUserId()?.trim()?.lowercase()) return
        _isScreenActive.value = isVisible
        requestPollJob?.cancel()
        if (isVisible) {
            refreshMessageRequest()
            requestPollJob = viewModelScope.launch {
                while (isActive) {
                    delay(4_000L)
                    if (_messageRequest.value.status != "open") refreshMessageRequest()
                }
            }
        }
        if (isPlaceholder || chatId?.startsWith("placeholder_", ignoreCase = true) == true) return
        val cleanChatId = chatId?.trim()?.lowercase() ?: return
        if (cleanChatId.isBlank()) return
        if (!isVisible) {
            messageRepository.setActiveChatSession(null, false)
            return
        }
        messageRepository.setActiveChatSession(cleanChatId, true)
        onVisibleMessageIdsChanged(_visibleMessageIds.value)
    }

    fun onVisibleMessageIdsChanged(visibleMessageIds: Set<String>) {
        _visibleMessageIds.value = visibleMessageIds
        if (!_isScreenActive.value) return
        val cid = chatId?.trim()?.lowercase() ?: return
        if (cid.startsWith("placeholder_")) return
        val state = _chatState.value as? ChatUiState.Success ?: return
        maybeMarkChatAsRead(cid, state.messages, visibleMessageIds)
    }

    private fun maybeMarkChatAsRead(chatId: String, messages: List<Message>, visibleMessageIds: Set<String>) {
        if (!_isScreenActive.value || messages.isEmpty()) return
        if (visibleMessageIds.isEmpty()) return
        val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        val readableVisibleIds = messages.asSequence()
            .filter { msg ->
                msg.sender_id.trim().lowercase() != currentUserId &&
                    visibleMessageIds.contains(msg.id) &&
                    UnreadPolicy.isEligibleStatusForReadTransition(msg.status)
            }
            .map { it.id.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (readableVisibleIds.isEmpty()) return
        viewModelScope.launch {
            messageRepository.markMessagesAsRead(chatId, readableVisibleIds)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
        stopTypingPresence()
        voiceMessageManager.reset()
        presenceJob?.cancel()
        chatSettingsJob?.cancel()
        blockRelationshipJob?.cancel()
        typingObserverJob?.cancel()
        typingStopJob?.cancel()
        updateMyOnlineStatus(false)
        messageRepository.setActiveChatSession(null, false)
    }

    // Message Actions Implementations
    fun toggleSelectionMode() {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) _selectedMessages.value = emptySet()
    }

    fun toggleMessageSelection(messageId: String) {
        val current = _selectedMessages.value.toMutableSet()
        if (current.contains(messageId)) current.remove(messageId) else current.add(messageId)
        _selectedMessages.value = current
        _isSelectionMode.value = current.isNotEmpty()
    }

    fun onSwipeToReply(message: Message) { 
        if (message.deleted_for_all) return
        textComposer.updateReply(message)
    }

    fun onMessageLongClick(message: Message) {
        if (message.deleted_for_all && message.sender_id != getCurrentUserId()) return
        _selectedMessage.value = message
    }

    fun dismissMessageActions() { 
        _selectedMessage.value = null 
    }

    fun openReactionPicker(message: Message) {
        if (message.deleted_for_all) return
        _reactionPickerMessage.value = message
        _selectedMessage.value = null
    }

    fun dismissReactionPicker() {
        _reactionPickerMessage.value = null
    }

    fun reactToMessage(message: Message, emoji: String) {
        viewModelScope.launch {
            val result = messageRepository.toggleMessageReaction(message.id, emoji)
            if (result.isFailure) {
                conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo reaccionar")
            }
            _reactionPickerMessage.value = null
        }
    }

    fun openForwardPicker(message: Message) {
        if (message.deleted_for_all) return
        _forwardPickerMessage.value = message
        _forwardUserSearchQuery.value = ""
        _forwardUserTargets.value = emptyList()
        _selectedMessage.value = null
    }

    fun dismissForwardPicker() {
        _forwardPickerMessage.value = null
        _forwardUserSearchQuery.value = ""
        _forwardUserTargets.value = emptyList()
        forwardSearchJob?.cancel()
    }

    fun updateForwardUserSearchQuery(query: String) {
        val clean = query.trim()
        _forwardUserSearchQuery.value = clean
        forwardSearchJob?.cancel()
        if (clean.length < 2) {
            _forwardUserTargets.value = emptyList()
            return
        }
        forwardSearchJob = viewModelScope.launch {
            delay(220)
            if (_forwardUserSearchQuery.value != clean) return@launch
            val me = authService.getCurrentUserId()?.trim()?.lowercase()
            val result = userRepository.searchUsers(clean)
            val targets = result.getOrDefault(emptyList())
                .filterNot { user -> user.id.trim().lowercase() == me }
                .map { user ->
                    ForwardUserTarget(
                        userId = user.id.trim().lowercase(),
                        title = user.displayName.ifBlank { user.username ?: "Usuario" },
                        subtitle = user.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
                            ?: user.email.takeIf { it.isNotBlank() }
                    )
                }
                .distinctBy { it.userId }
                .sortedBy { it.title.lowercase() }
            _forwardUserTargets.value = targets
        }
    }

    fun forwardMessageToSelectedChats(
        targetChatIds: Set<String>,
        targetUserIds: Set<String> = emptySet()
    ) {
        val message = _forwardPickerMessage.value ?: return
        if (targetChatIds.isEmpty() && targetUserIds.isEmpty()) {
            conectate.com.presentation.common.AppFeedbackBus.showError("Selecciona al menos un chat")
            return
        }
        if (_isForwarding.value) return

        viewModelScope.launch {
            _isForwarding.value = true
            val chatTargets = linkedSetOf<String>()
            val groupTargets = linkedSetOf<String>()
            val communityTargets = linkedSetOf<String>()

            targetChatIds.forEach { targetId ->
                val id = targetId.trim().lowercase()
                when {
                    id.startsWith("group:") -> groupTargets.add(id.removePrefix("group:"))
                    id.startsWith("community:") -> communityTargets.add(id.removePrefix("community:"))
                    else -> chatTargets.add(id)
                }
            }

            targetUserIds
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .forEach { uid ->
                    val created = messageRepository.getOrCreateChat(uid)
                    if (created.isSuccess) {
                        chatTargets.add(created.getOrThrow().id.trim().lowercase())
                    }
                }

            val current = chatId?.trim()?.lowercase()
            if (!current.isNullOrBlank()) {
                chatTargets.remove(current)
            }

            if (chatTargets.isEmpty()) {
                conectate.com.presentation.common.AppFeedbackBus.showError(
                    "No hay destino válido para reenviar"
                )
                _isForwarding.value = false
                return@launch
            }

            var successCount = 0

            // Forward to chats
            if (chatTargets.isNotEmpty()) {
                val result = messageRepository.forwardMessageToChats(message, chatTargets.toList())
                if (result.isSuccess) {
                    successCount += result.getOrNull() ?: 0
                }
            }

            if (successCount > 0) {
                Toast.makeText(context, "Reenviado a $successCount destino(s)", Toast.LENGTH_SHORT).show()
                _forwardPickerMessage.value = null
                _forwardUserSearchQuery.value = ""
                _forwardUserTargets.value = emptyList()
            } else {
                conectate.com.presentation.common.AppFeedbackBus.showError(
                    "No se pudo reenviar el mensaje"
                )
            }
            _isForwarding.value = false
        }
    }

    fun replyToMessage(message: Message) { 
        textComposer.updateReply(message)
        _selectedMessage.value = null
    }

    fun cancelReply() { textComposer.updateReply(null) }

    fun onEditMessage(message: Message) {
        if (message.content_type != "text" || message.deleted_for_all) return
        _editingMessage.value = message
        textComposer.beginEditing(message.content)
        _selectedMessage.value = null
    }

    fun cancelEdit() {
        _editingMessage.value = null
        textComposer.finishEditing()
        stopTypingPresence()
    }

    fun confirmEditMessage() {
        val msg = _editingMessage.value ?: return
        val newContent = _messageText.value
        if (newContent.isBlank() || newContent == msg.content) {
            cancelEdit()
            return
        }

        viewModelScope.launch {
            _isSending.value = true
            val result = messageRepository.editMessage(msg, newContent)
            if (result.isSuccess) {
                cancelEdit()
            } else {
                conectate.com.presentation.common.AppFeedbackBus.showError("Error al editar")
            }
            _isSending.value = false
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Mensaje", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
    }

    fun deleteMessageForEveryone(message: Message) {
        viewModelScope.launch {
            val result = messageRepository.deleteMessage(message.id, true)
            if (result.isFailure) {
                conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo eliminar para todos")
            }
        }
    }

    fun deleteMessageForMe(message: Message) {
        viewModelScope.launch {
            val result = messageRepository.deleteMessage(message.id, false)
            if (result.isFailure) {
                conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo eliminar para mí")
            }
        }
    }

    fun deleteSelectedMessages(forEveryone: Boolean) {
        val selected = _selectedMessages.value.toList()
        viewModelScope.launch {
            var failed = 0
            selected.forEach { id ->
                if (messageRepository.deleteMessage(id, forEveryone).isFailure) failed++
            }
            if (failed > 0) conectate.com.presentation.common.AppFeedbackBus.showError("No se pudieron eliminar $failed mensajes")
            toggleSelectionMode()
        }
    }

    fun blockUser() {
        val uid = resolvedTargetUserId ?: otherUserId
        if (uid.isBlank()) return
        viewModelScope.launch {
             val result = userRepository.blockUser(uid)
             if (result.isSuccess) {
                 applyBlockRelationship(
                     relationship = BlockRelationship(
                         blockedByMe = true,
                         blockedMe = _isBlockedByOther.value
                     ),
                     announceWhenBlockedByOther = false
                 )
                 _advancedMessage.value = "Usuario bloqueado."
             } else {
                 _advancedMessage.value = "No se pudo bloquear usuario."
             }
        }
    }

    fun unblockUser() {
        val uid = resolvedTargetUserId ?: otherUserId
        if (uid.isBlank()) return
        viewModelScope.launch {
             val result = userRepository.unblockUser(uid)
             if (result.isSuccess) {
                 applyBlockRelationship(
                     relationship = BlockRelationship(
                         blockedByMe = false,
                         blockedMe = _isBlockedByOther.value
                     ),
                     announceWhenBlockedByOther = false
                 )
                 _advancedMessage.value = if (_isBlockedByOther.value) {
                     "El otro usuario te tiene bloqueado."
                 } else {
                     "Usuario desbloqueado."
                 }
             } else {
                 _advancedMessage.value = "No se pudo desbloquear usuario."
             }
        }
    }
    fun deleteChat() {
        val cid = chatId ?: return
        viewModelScope.launch {
            messageRepository.deleteChat(cid)
        }
    }
    fun reportChat(@Suppress("UNUSED_PARAMETER") reason: String) {}
    fun submitReport(uid: String, name: String, reason: String, desc: String, uri: Uri?) {
        viewModelScope.launch {
            messageRepository.submitReport(uid, name, reason, desc, uri)
        }
    }

    suspend fun getPlayableUrl(
        path: String,
        allowOfflineMediaCache: Boolean = true
    ): String? = messageRepository.getPlayableUrl(path, allowOfflineMediaCache).getOrNull()

    suspend fun consumeViewOnceMessage(message: Message): String? {
        val mediaPath = message.media_url ?: return null
        return messageRepository.consumeViewOnceMedia(
            scopeType = "dm",
            messageId = message.id,
            mediaPath = mediaPath
        ).getOrNull()
    }
    fun getCurrentUserId(): String? = authService.getCurrentUserId()
    fun getCurrentUserEmail(): String? = authService.getCurrentUserEmail()

    // --- Nickname Actions ---
    fun showNicknameDialog() { _showNicknameDialog.value = true }
    fun dismissNicknameDialog() { _showNicknameDialog.value = false }
    
    fun setNickname(nickname: String?) {
        val targetId = resolvedTargetUserId ?: otherUserId
        
        viewModelScope.launch {
            val result = messageRepository.setNickname(targetId, nickname)
            if (result.isSuccess) {
                _currentNickname.value = nickname
                dismissNicknameDialog()
            } else {
                conectate.com.presentation.common.AppFeedbackBus.showError("Error al establecer apodo")
            }
        }
    }

    // --- Clear Chat Actions ---
    fun showClearChatDialog() { _showClearChatDialog.value = true }
    fun dismissClearChatDialog() { _showClearChatDialog.value = false }
    
    fun confirmClearChat() {
        val cid = chatId ?: return
        viewModelScope.launch {
            val result = messageRepository.clearChat(cid)
            if (result.isSuccess) {
                dismissClearChatDialog()
                Toast.makeText(context, "Chat vaciado", Toast.LENGTH_SHORT).show()
            } else {
                conectate.com.presentation.common.AppFeedbackBus.showError("Error al vaciar chat")
            }
        }
    }
    
    // Voice Manager helpers
    fun voiceMessageManagerIsPreviewPlaying(): Boolean {
        return voiceMessageManager.isPreviewPlaying()
    }

    private fun normalizeLanguageCode(raw: String?): String? {
        val cleaned = raw
            ?.trim()
            ?.lowercase()
            ?.replace('_', '-')
            ?.ifBlank { null }
            ?: return null
        if (cleaned == "auto") return null
        val valid = Regex("^[a-z]{2,3}(-[a-z0-9]{2,8})?$").matches(cleaned)
        return if (valid) cleaned else null
    }

    private fun ensureNotBlockedForSending(allowPendingRetry: Boolean = false): Boolean {
        if (ownerAccountId.isNullOrBlank() || ownerAccountId != authService.getCurrentUserId()?.trim()?.lowercase()) {
            conectate.com.presentation.common.AppFeedbackBus.showError("La cuenta cambió. Vuelve a abrir la conversación.")
            return false
        }
        if ((!_messageRequest.value.permitsLocalQueue && !(allowPendingRetry && _messageRequest.value.status == "pending_outgoing")) ||
            (_messageRequest.value.needsFirstMessageWarning && !_requestWarningAcknowledged.value) || _requestDecisionBusy.value) {
            conectate.com.presentation.common.AppFeedbackBus.showError(
                if (_messageRequest.value.status == "pending_outgoing") "Espera a que acepte o responda tu solicitud."
                else if (_messageRequest.value.status == "rejected") "La solicitud de mensaje fue rechazada."
                else "Revisa la solicitud de mensaje antes de enviar."
            )
            return false
        }
        if (!_isBlocked.value) return true
        val reason = if (_isBlockedByOther.value) {
            "No puedes enviar mensajes a este usuario."
        } else {
            "Desbloquea al usuario para enviar mensajes."
        }
        conectate.com.presentation.common.AppFeedbackBus.showError(reason)
        return false
    }

    private fun parseDateTimeToEpochMillis(dateIso: String, time24h: String): Long? {
        return runCatching {
            val date = LocalDate.parse(dateIso.trim())
            val time = LocalTime.parse(time24h.trim())
            LocalDateTime.of(date, time)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private fun scheduleTextMessageInternal(
        text: String,
        whenMs: Long,
        selfDestructSeconds: Int? = null,
        translateTo: String? = null
    ) {
        viewModelScope.launch {
            if (isPlaceholder) {
                val createResult = messageRepository.getOrCreateChat(resolvedTargetUserId ?: otherUserId)
                if (createResult.isFailure) {
                    _advancedMessage.value = "No se pudo crear chat para programar mensaje."
                    return@launch
                }
                val newChat = createResult.getOrThrow()
                chatId = newChat.id
                messageRepository.setActiveChatSession(chatId, _isScreenActive.value)
                isPlaceholder = false
                startObservingMessages(newChat.id, newChat)
                loadTranslationSettings(newChat.id)
            }

            val cid = chatId ?: return@launch
            val normalizedTranslate = normalizeLanguageCode(translateTo)
            if (!translateTo.isNullOrBlank() && normalizedTranslate == null) {
                _advancedMessage.value = "Idioma inválido. Usa formato como en, es o pt-br."
                return@launch
            }
            val scheduled = advancedMessagingRepository.scheduleMessageJob(
                scopeType = "dm",
                targetId = cid,
                content = text,
                contentType = "text",
                scheduledForEpochMs = whenMs,
                translateTo = normalizedTranslate,
                selfDestructSeconds = selfDestructSeconds?.takeIf { it > 0 }
            )

            if (scheduled.isSuccess) {
                textComposer.updateText("")
                stopTypingPresence()
                refreshScheduledMessages()
                val scheduledAt = runCatching {
                    Instant.ofEpochMilli(whenMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()
                        .toString()
                        .replace('T', ' ')
                }.getOrElse { "futuro" }
                _advancedMessage.value = "Mensaje programado para $scheduledAt."
            } else {
                _advancedMessage.value = scheduled.exceptionOrNull()?.message ?: "No se pudo programar mensaje."
            }
        }
    }

    private fun defaultScheduleCaption(messageType: String): String {
        return defaultMediaCaption(messageType)
    }

    private fun defaultMediaCaption(messageType: String): String {
        return when (messageType.trim().lowercase()) {
            "image", "view_once_image" -> "📷 Imagen"
            "video", "view_once_video" -> "🎥 Video"
            "audio" -> "🎵 Audio"
            "gif" -> "🌀 GIF"
            "sticker" -> "🧩 Sticker"
            "document", "file" -> "📎 Archivo"
            else -> "Archivo"
        }
    }
}

data class ForwardChatTarget(
    val chatId: String,
    val title: String,
    val subtitle: String? = null
)

data class ForwardUserTarget(
    val userId: String,
    val title: String,
    val subtitle: String? = null
)

// UI State definition
sealed class ChatUiState {
    object Loading : ChatUiState()
    data class Success(
        val messages: List<Message>,
        val otherUser: User?,
        val currentUser: User? = null,
        val chat: conectate.com.data.model.Chat? = null,
        val currentUserBadges: List<conectate.com.data.model.Badge> = emptyList(),
        val isMember: Boolean = true
    ) : ChatUiState()
    data class Error(val message: String) : ChatUiState()
}
