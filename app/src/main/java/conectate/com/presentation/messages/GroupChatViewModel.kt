package conectate.com.presentation.messages

import conectate.com.core.media.readMediaBytesWithLimit

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.local.dao.GroupDao
import conectate.com.core.messaging.UnreadPolicy
import conectate.com.data.model.Group
import conectate.com.data.model.GroupMessage
import conectate.com.data.model.MessagePollOptionState
import conectate.com.data.model.MessageReadReceipt
import conectate.com.data.model.PollVoteUser
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.model.User
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.StickerRepository
import conectate.com.data.repository.ChatBackgroundSettings
import conectate.com.data.repository.ChatSettingsRepository
import conectate.com.data.repository.message.MessageSessionStateStore
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import conectate.com.util.ChatContactPayload
import conectate.com.util.UbuntuMedia
import conectate.com.util.encodeContactPayload
import conectate.com.presentation.common.toUserFacingMessage

sealed class GroupChatUiState {
    object Loading : GroupChatUiState()
    data class Success(
        val group: Group,
        val messages: List<GroupMessage>,
        val memberProfiles: Map<String, User> = emptyMap(),
        val memberIds: List<String> = emptyList()
    ) : GroupChatUiState()
    data class Error(val message: String) : GroupChatUiState()
}

data class GroupPollOptionVotersUiState(
    val pollId: String,
    val optionId: String,
    val optionText: String,
    val voteCount: Long,
    val voters: List<PollVoteUser> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class GroupChatViewModel @Inject constructor(
    private val conversationDraftStore: conectate.com.data.local.ConversationDraftStore,
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository,
    private val groupDao: GroupDao,
    private val authService: AuthService,
    private val voiceMessageManager: VoiceMessageManager,
    private val messageRepository: MessageRepository,
    private val stickerRepository: StickerRepository,
    private val chatSettingsRepository: ChatSettingsRepository,
    private val messageSessionStateStore: MessageSessionStateStore
) : ViewModel() {

    private val _uiState = MutableStateFlow<GroupChatUiState>(GroupChatUiState.Loading)
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    private val ownerAccountId = authService.getCurrentUserId()
    private val textComposer = QueuedMessageComposer<conectate.com.data.model.Message>()
    private val _messageText = textComposer.text
    val messageText: StateFlow<String> = _messageText

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val pollVoteMutex = kotlinx.coroutines.sync.Mutex()
    private val _pollStates = MutableStateFlow<Map<String, List<MessagePollOptionState>>>(emptyMap())
    val pollStates: StateFlow<Map<String, List<MessagePollOptionState>>> = _pollStates.asStateFlow()
    private val pollRefresh = VisiblePollRefreshController(
        scope = viewModelScope,
        isCurrentAccount = { ownerAccountId != null && authService.getCurrentUserId().equals(ownerAccountId, true) },
        fetch = groupRepository::getPollState,
        apply = { id, state -> _pollStates.update { it + (id to state) } },
    )

    private val _isCreatingPoll = MutableStateFlow(false)
    val isCreatingPoll: StateFlow<Boolean> = _isCreatingPoll.asStateFlow()

    private val _messageInfoTarget = MutableStateFlow<conectate.com.data.model.Message?>(null)
    val messageInfoTarget: StateFlow<conectate.com.data.model.Message?> = _messageInfoTarget.asStateFlow()

    private val _messageInfoReceipts = MutableStateFlow<List<MessageReadReceipt>>(emptyList())
    val messageInfoReceipts: StateFlow<List<MessageReadReceipt>> = _messageInfoReceipts.asStateFlow()

    private val _isLoadingMessageInfo = MutableStateFlow(false)
    val isLoadingMessageInfo: StateFlow<Boolean> = _isLoadingMessageInfo.asStateFlow()
    private val _pollOptionVoters = MutableStateFlow<GroupPollOptionVotersUiState?>(null)
    val pollOptionVoters: StateFlow<GroupPollOptionVotersUiState?> = _pollOptionVoters.asStateFlow()

    // Voice Manager State Expose
    val voiceState = voiceMessageManager.state
    val recordingDuration = voiceMessageManager.duration
    val voicePlaybackProgress = voiceMessageManager.playbackProgress
    val voicePlaybackDurationLabel = voiceMessageManager.playbackDurationLabel

    private val _currentPlayingAudioId = MutableStateFlow<String?>(null)
    val currentPlayingAudioId: StateFlow<String?> = _currentPlayingAudioId.asStateFlow()
    private val _isAudioPlaying = MutableStateFlow(false)
    val isAudioPlaying: StateFlow<Boolean> = _isAudioPlaying.asStateFlow()
    private val _audioPlaybackSpeed = MutableStateFlow(1f)
    val audioPlaybackSpeed: StateFlow<Float> = _audioPlaybackSpeed.asStateFlow()

    private val _audioProgress = MutableStateFlow(0f)
    val audioProgress: StateFlow<Float> = _audioProgress.asStateFlow()

    private val _audioDurationLabel = MutableStateFlow("0:00")
    val audioDurationLabel: StateFlow<String> = _audioDurationLabel.asStateFlow()
    private val _audioDurations = MutableStateFlow<Map<String, Long>>(emptyMap())
    val audioDurations: StateFlow<Map<String, Long>> = _audioDurations.asStateFlow()

    private val _myRole = MutableStateFlow<String?>(null)
    val myRole: StateFlow<String?> = _myRole.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    private val _isMember = MutableStateFlow(true)
    val isMember: StateFlow<Boolean> = _isMember.asStateFlow()

    private val _replyToMessage = textComposer.reply
    val replyToMessage: StateFlow<conectate.com.data.model.Message?> = _replyToMessage

    private val _editingMessage = MutableStateFlow<conectate.com.data.model.Message?>(null)
    val editingMessage: StateFlow<conectate.com.data.model.Message?> = _editingMessage.asStateFlow()

    private val _selectedMessage = MutableStateFlow<conectate.com.data.model.Message?>(null)
    val selectedMessage: StateFlow<conectate.com.data.model.Message?> = _selectedMessage.asStateFlow()
    private val _reactionPickerMessage = MutableStateFlow<conectate.com.data.model.Message?>(null)
    val reactionPickerMessage: StateFlow<conectate.com.data.model.Message?> = _reactionPickerMessage.asStateFlow()
    private val _forwardPickerMessage = MutableStateFlow<conectate.com.data.model.Message?>(null)
    val forwardPickerMessage: StateFlow<conectate.com.data.model.Message?> = _forwardPickerMessage.asStateFlow()
    private val _isForwarding = MutableStateFlow(false)
    val isForwarding: StateFlow<Boolean> = _isForwarding.asStateFlow()
    private val _activeGroupId = MutableStateFlow<String?>(null)
    private val _chatBackgroundSettings = MutableStateFlow(ChatBackgroundSettings())
    val chatBackgroundSettings = _chatBackgroundSettings.asStateFlow()
    private var chatBackgroundJob: kotlinx.coroutines.Job? = null

    private var mediaPlayer: conectate.com.util.MessageAudioPlayer? = null
    private var progressJob: kotlinx.coroutines.Job? = null

    private var groupId: String = ""
    private val _visibleMessageIds = MutableStateFlow<Set<String>>(emptySet())
    private val visibleMessageLookup = VisibleMessageLookup<GroupMessage>(GroupMessage::id)
    private var knownPollSnapshot: List<GroupMessage>? = null
    private var knownPollIds: Set<String> = emptySet()
    private val _isScreenActive = MutableStateFlow(false)

    val forwardTargets: StateFlow<List<GroupForwardTarget>> = combine(
        groupRepository.getGroupsFlow(),
        _activeGroupId
    ) { groups, currentGroupId ->
        val current = currentGroupId?.trim()?.lowercase()
        groups
            .mapNotNull { item ->
                val id = item.group.id.trim().lowercase()
                if (id.isBlank() || id == current) return@mapNotNull null
                GroupForwardTarget(
                    groupId = id,
                    title = item.group.name.ifBlank { "Grupo" }
                )
            }
            .distinctBy { it.groupId }
            .sortedBy { it.title.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _mentionMembers = MutableStateFlow<List<User>>(emptyList())
    val mentionMembers: StateFlow<List<User>> = _mentionMembers.asStateFlow()

    init {
        viewModelScope.launch {
            groupRepository.observePollUpdates().collect { pollRefresh.requestRefresh(it) }
        }
    }

    fun init(groupId: String) {
        val cleanId = groupId.trim().lowercase()
        if (cleanId.isBlank()) return
        if (this.groupId != cleanId) {
            textComposer.bindDraft(conversationDraftStore.read(ownerAccountId, "group", cleanId)) { text ->
                if (authService.getCurrentUserId().equals(ownerAccountId, true)) {
                    conversationDraftStore.write(ownerAccountId, "group", cleanId, text)
                }
            }
        }
        if (this.groupId != cleanId) {
            pollRefresh.stop()
            _pollStates.value = emptyMap()
            _visibleMessageIds.value = emptySet()
        }
        this.groupId = cleanId
        observeMentionMembers()
        _activeGroupId.value = cleanId
        observeChatBackground(cleanId)
        voiceMessageManager.reset()
        
        loadGroup()
        viewModelScope.launch {
            groupRepository.setActiveGroup(cleanId)
            groupRepository.markMessagesAsDelivered(cleanId)
        }
    }

    private fun backgroundKey(id: String) = "group:${id.trim().lowercase()}"

    private fun observeChatBackground(id: String) {
        chatBackgroundJob?.cancel()
        chatBackgroundJob = viewModelScope.launch {
            chatSettingsRepository.observeChatBackground(backgroundKey(id)).collectLatest {
                _chatBackgroundSettings.value = it
            }
        }
    }

    fun setChatBackground(type: String, uri: String? = null) {
        val id = groupId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch { chatSettingsRepository.setChatBackground(backgroundKey(id), type, uri) }
    }

    fun clearChatBackground() {
        val id = groupId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch { chatSettingsRepository.clearChatBackground(backgroundKey(id)) }
    }

    fun onScreenVisibilityChanged(isVisible: Boolean) {
        if (!isVisible) stopAudio()
        _isScreenActive.value = isVisible
        refreshVisiblePolls()
        val cleanId = groupId.trim().lowercase()
        if (cleanId.isBlank()) return
        if (isVisible) {
            messageSessionStateStore.setActiveConversation("group", cleanId, true)
            viewModelScope.launch {
                groupRepository.setActiveGroup(cleanId)
                groupRepository.markMessagesAsDelivered(cleanId)
                }
        } else {
            messageSessionStateStore.setActiveConversation("group", cleanId, false)
            groupRepository.setActiveGroup(null)
        }
    }

    fun onVisibleMessageIdsChanged(visibleMessageIds: Set<String>) {
        _visibleMessageIds.value = visibleMessageIds
        refreshVisiblePolls()
        if (!_isScreenActive.value) return
        val cleanId = groupId.trim().lowercase()
        if (cleanId.isBlank()) return
        val state = _uiState.value as? GroupChatUiState.Success ?: return
        maybeMarkAsRead(cleanId, state.messages, visibleMessageIds)
    }

    private fun maybeMarkAsRead(groupId: String, messages: List<GroupMessage>, visibleMessageIds: Set<String>) {
        if (!_isScreenActive.value || messages.isEmpty()) return
        if (visibleMessageIds.isEmpty()) return
        val currentUserId = authService.getCurrentUserId()?.trim()?.lowercase() ?: return
        val readableVisibleIds = visibleMessageLookup.find(messages, visibleMessageIds).asSequence()
            .filter { msg ->
                msg.sender_id.trim().lowercase() != currentUserId &&
                    UnreadPolicy.isEligibleStatusForReadTransition(msg.status)
            }
            .map { it.id.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (readableVisibleIds.isEmpty()) return
        viewModelScope.launch {
            groupRepository.markAsRead(groupId, readableVisibleIds)
        }
    }

    private fun loadGroup() {
        viewModelScope.launch {
            // Observe group status for deletion
            launch {
                groupRepository.getGroupFlow(groupId).collect { group ->
                    if (group?.is_deleted == true) {
                        _isDeleted.value = true
                    }
                }
            }

            // Observe membership status
            val userId = authService.getCurrentUserId() ?: ""
            launch {
                groupRepository.isUserMemberFlow(groupId, userId).collect { isMember ->
                    _isMember.value = isMember
                }
            }

            // Observe role status
            launch {
                groupRepository.getUserRoleFlow(groupId, userId).collect { role ->
                    _myRole.value = role
                }
            }

            // 1. Initial Load from Room
            val group = kotlinx.coroutines.withTimeoutOrNull(10_000L) { groupRepository.getGroupById(groupId) } ?: run {
                _uiState.value = GroupChatUiState.Error("Este grupo todavía no está guardado en el equipo. Conéctate para cargarlo.")
                return@launch
            }

            // 2. Background Sync
            launch {
                 try {
                     groupRepository.syncGroups()
                 } catch (e: Exception) {
                     android.util.Log.e("GroupChatVM", "Background sync failed", e)
                 }
            }
            
            val members = groupRepository.getCachedGroupMembers(groupId)
            
            // Fetch User profiles for members
            val userIds = members.map { it.user_id }
            val memberIds = userIds
            val fetchedProfiles = userIds.mapNotNull { userRepository.getCachedUserById(it) }
                .associateBy { it.id.trim().lowercase() }
            val memberProfiles = members.associate { member ->
                val cleanMemberId = member.user_id.trim().lowercase()
                val fallbackName = member.userName?.takeIf { it.isNotBlank() }
                val fallbackImage = member.userImage?.takeIf { it.isNotBlank() }
                cleanMemberId to (fetchedProfiles[cleanMemberId] ?: User(
                    id = cleanMemberId,
                    username = fallbackName,
                    full_name = fallbackName,
                    display_name = fallbackName,
                    avatar_url = fallbackImage
                ))
            }
            
            _myRole.value = members.find { it.user_id == userId }?.role
            
            // The local snapshot is ready before any member/profile HTTP request.
            _uiState.value = GroupChatUiState.Success(
                group, groupRepository.getCachedMessages(groupId), memberProfiles, memberIds
            )
            launch {
                val refreshedMembers = groupRepository.getGroupMembers(groupId).getOrDefault(members)
                val refreshedProfiles = userRepository.getUsersByIds(refreshedMembers.map { it.user_id })
                    .getOrDefault(emptyList()).associateBy { it.id.trim().lowercase() }
                ensureActive()
                if (ownerAccountId != authService.getCurrentUserId()) return@launch
                _uiState.update { state ->
                    if (state is GroupChatUiState.Success) state.copy(
                        memberProfiles = state.memberProfiles + refreshedProfiles,
                        memberIds = refreshedMembers.map { it.user_id }
                    ) else state
                }
            }

            // 3. Observe Group and Messages from Repo
            launch {
                groupRepository.getGroupFlow(groupId).collect { grp ->
                    if (grp != null) {
                        _uiState.update { state ->
                            if (state is GroupChatUiState.Success) {
                                state.copy(group = grp)
                            } else state
                        }
                    }
                }
            }

            groupRepository.getMessagesFlow(groupId).collect { messages ->
                android.util.Log.d("GroupChatViewModel", "Messages updated in UI: ${messages.size}")
                messageRepository.prefetchRecentGroupMedia(messages)
                val currentGroup = groupRepository.getGroupById(groupId) ?: group
                refreshPollStates(messages)
                _uiState.update { state ->
                    if (state is GroupChatUiState.Success) state.copy(group = currentGroup, messages = messages)
                    else GroupChatUiState.Success(currentGroup, messages, memberProfiles, memberIds)
                }
                maybeMarkAsRead(groupId.trim().lowercase(), messages, _visibleMessageIds.value)
            }
        }
    }

    private var mentionMembersJob: kotlinx.coroutines.Job? = null
    private fun observeMentionMembers() {
        mentionMembersJob?.cancel()
        _mentionMembers.value = emptyList()
        val roomId = groupId
        mentionMembersJob = viewModelScope.launch {
            groupRepository.getMembersFlow(roomId).collectLatest { members ->
                if (ownerAccountId == null || ownerAccountId != authService.getCurrentUserId()) {
                    _mentionMembers.value = emptyList(); return@collectLatest
                }
                val ids = members.map { it.user_id.trim().lowercase() }.toSet()
                val cached = _mentionMembers.value.associateBy { it.id.trim().lowercase() }
                _mentionMembers.value = conectate.com.data.model.resolvedRoomMentionMembers(ids, cached.values)
                val fetched = if (ids.isEmpty()) emptyMap() else userRepository.getUsersByIds(ids.toList()).getOrDefault(emptyList())
                    .associateBy { it.id.trim().lowercase() }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (roomId == groupId && ownerAccountId == authService.getCurrentUserId())
                    _mentionMembers.value = conectate.com.data.model.resolvedRoomMentionMembers(ids, fetched.values + cached.values)
            }
        }
    }

    fun refreshMentionMembers() {
        if (ownerAccountId == null || ownerAccountId != authService.getCurrentUserId()) return
        val roomId = groupId
        viewModelScope.launch { groupRepository.getGroupMembers(roomId) }
    }

    fun updateMessageText(text: String) {
        textComposer.updateText(text)
    }

    fun sendMessage() {
        if (ownerAccountId == null || ownerAccountId != authService.getCurrentUserId()) return
        val destinationId = groupId.takeIf { it.isNotBlank() } ?: return

        val state = _uiState.value as? GroupChatUiState.Success
        val onlyAdmins = state?.group?.only_admins_can_talk ?: false
        val isAdmin = _myRole.value == "admin" || _myRole.value == "owner" || (state?.group?.creator_id == getCurrentUserId())
        
        if (onlyAdmins && !isAdmin) {
             _errorMessage.value = "Solo los administradores pueden escribir en este grupo."
             return
        }

        val submission = textComposer.beginSend() ?: return
        fun isCurrentConversation() = groupId == destinationId && ownerAccountId == authService.getCurrentUserId()
        _errorMessage.value = null
        viewModelScope.launch {
            try {
                if (!isCurrentConversation()) return@launch
                val result = groupRepository.sendGroupMessage(
                    destinationId,
                    submission.text,
                    replyToId = submission.replyTo?.id,
                    replyTo = submission.replyTo,
                    onQueued = {
                        withContext(Dispatchers.Main.immediate) {
                            if (isCurrentConversation()) textComposer.queued(submission)
                            else textComposer.finished(submission)
                        }
                    }
                )
                if (result.isFailure && isCurrentConversation()) {
                    _errorMessage.value = result.exceptionOrNull()
                        .toUserFacingMessage("No se pudo enviar el mensaje.")
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isCurrentConversation()) {
                    _errorMessage.value = error.toUserFacingMessage("No se pudo enviar el mensaje.")
                }
            } finally {
                textComposer.finished(submission)
            }
        }
    }

    fun retryMessage(message: conectate.com.data.model.Message) {
        if (!authService.getCurrentUserId().equals(ownerAccountId, true)) return
        if (_isSending.value || message.sender_id != ownerAccountId || message.deleted_for_all ||
            message.status !in setOf("failed", "sending", "pending")) return
        val destination = groupId
        viewModelScope.launch {
            _isSending.value = true
            try {
                groupRepository.retryMessage(message.id, destination).onFailure {
                    _errorMessage.value = it.toUserFacingMessage("No se pudo reenviar el mensaje.")
                }
            } finally { _isSending.value = false }
        }
    }

    fun sendMediaMessage(
        uri: android.net.Uri,
        context: android.content.Context,
        forcedMessageType: String? = null,
        customContent: String? = null
    ) {
        val state = _uiState.value as? GroupChatUiState.Success
        val onlyAdmins = state?.group?.only_admins_can_talk ?: false
        val isAdmin = _myRole.value == "admin" || _myRole.value == "owner" || (state?.group?.creator_id == getCurrentUserId())
        
        if (onlyAdmins && !isAdmin) {
             _errorMessage.value = "Solo los administradores pueden enviar archivos."
             return
        }

        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val resolvedType = forcedMessageType?.trim()?.lowercase()?.ifBlank { null } ?: when {
            mimeType.equals("image/gif", ignoreCase = true) -> "gif"
            mimeType.startsWith("video", ignoreCase = true) -> "video"
            mimeType.startsWith("audio", ignoreCase = true) -> "audio"
            mimeType.startsWith("image", ignoreCase = true) -> "image"
            else -> "document"
        }
        val normalizedCustomContent = customContent?.trim()
        val replySnapshot = textComposer.captureReply()
        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            try {
                val content = normalizedCustomContent ?: when (resolvedType) {
                    "video" -> "🎥 Video"
                    "audio" -> "🎵 Audio"
                    "view_once_image" -> "👁️ Foto ver una vez"
                    "view_once_video" -> "👁️ Video ver una vez"
                    "gif" -> "🌀 GIF"
                    "sticker" -> "🧩 Sticker"
                    "document", "file" -> "📎 Archivo"
                    else -> "🖼️ Imagen"
                }
                val sendResult = groupRepository.sendGroupMediaMessage(
                    groupId = groupId,
                    sourceUri = uri,
                    contentType = resolvedType,
                    content = content,
                    replyToId = replySnapshot.replyTo?.id,
                    replyTo = replySnapshot.replyTo,
                    onQueued = { withContext(Dispatchers.Main.immediate) { textComposer.queuedReply(replySnapshot) } }
                )
                if (sendResult.isFailure) {
                    _errorMessage.value = sendResult.exceptionOrNull()
                        .toUserFacingMessage("No se pudo enviar el archivo.")
                }
            } catch (e: Exception) {
                _errorMessage.value = e.toUserFacingMessage("No se pudo enviar el archivo.")
            }
            _isSending.value = false
        }
    }

    fun sendRecentGif(mediaUrl: String) {
        if (mediaUrl.isBlank() || groupId.isBlank()) return
        val replySnapshot = textComposer.captureReply()
        viewModelScope.launch {
            _isSending.value = true
            val result = groupRepository.sendGroupMessage(
                groupId = groupId,
                content = "🌀 GIF",
                contentType = "gif",
                mediaUrl = mediaUrl.trim(),
                replyToId = replySnapshot.replyTo?.id,
                replyTo = replySnapshot.replyTo,
                onQueued = { withContext(Dispatchers.Main.immediate) { textComposer.queuedReply(replySnapshot) } }
            )
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()
                    .toUserFacingMessage("No se pudo enviar el GIF.")
            }
            _isSending.value = false
        }
    }

    fun sendStickerEmoji(sticker: String) {
        sendStructuredMessage(
            content = sticker.trim().ifBlank { "🧩" },
            contentType = "sticker",
            validationError = "Selecciona un sticker"
        )
    }

    fun sendStickerFromUrl(mediaUrl: String, caption: String = "") {
        val cleanUrl = mediaUrl.trim()
        if (cleanUrl.isBlank()) {
            _errorMessage.value = "Sticker inválido"
            return
        }
        val replySnapshot = textComposer.captureReply()
        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            val result = groupRepository.sendGroupMessage(
                groupId = groupId,
                content = caption,
                contentType = "sticker",
                mediaUrl = cleanUrl,
                replyToId = replySnapshot.replyTo?.id,
                replyTo = replySnapshot.replyTo,
                onQueued = { withContext(Dispatchers.Main.immediate) { textComposer.queuedReply(replySnapshot) } }
            )
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()
                    .toUserFacingMessage("No se pudo enviar el sticker.")
            }
            _isSending.value = false
        }
    }

    fun sendContactMessage(
        name: String,
        phone: String,
        email: String? = null,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList()
    ) {
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        val cleanEmail = email?.trim()?.lowercase()?.ifBlank { null }
        val normalizedPhones = (phones + cleanPhone)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val normalizedEmails = (emails + listOfNotNull(cleanEmail))
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        if (cleanName.isBlank() || (normalizedPhones.isEmpty() && normalizedEmails.isEmpty())) {
            _errorMessage.value = "Completa nombre y al menos teléfono o correo"
            return
        }
        val payload = ChatContactPayload(
            name = cleanName,
            phone = normalizedPhones.firstOrNull(),
            email = normalizedEmails.firstOrNull(),
            phones = normalizedPhones,
            emails = normalizedEmails
        )
        sendStructuredMessage(
            content = encodeContactPayload(payload),
            contentType = "contact",
            validationError = "Completa nombre y al menos teléfono o correo"
        )
    }

    suspend fun resolveMention(handle: String): String? = userRepository.resolvePublicMention(handle)?.id

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
        if (lat.toDoubleOrNull()?.let { it.isFinite() && it in -90.0..90.0 } != true ||
            lon.toDoubleOrNull()?.let { it.isFinite() && it in -180.0..180.0 } != true) {
            _errorMessage.value = "Revisa las coordenadas de la ubicación"
            return
        }
        val mapUrl = "https://maps.google.com/?q=$lat,$lon"
        val marker = label?.trim().orEmpty()
        val content = if (marker.isNotBlank()) {
            "📍 $marker\n$mapUrl"
        } else {
            "📍 Ubicación compartida\n$mapUrl"
        }
        sendStructuredMessage(
            content = content,
            contentType = "location",
            validationError = "Revisa las coordenadas de la ubicación",
            mediaUrl = mapUrl
        )
    }

    private fun sendStructuredMessage(
        content: String,
        contentType: String,
        validationError: String,
        mediaUrl: String? = null
    ) {
        val state = _uiState.value as? GroupChatUiState.Success
        val onlyAdmins = state?.group?.only_admins_can_talk ?: false
        val isAdmin = _myRole.value == "admin" || _myRole.value == "owner" || (state?.group?.creator_id == getCurrentUserId())
        if (onlyAdmins && !isAdmin) {
            _errorMessage.value = "Solo los administradores pueden escribir en este grupo."
            return
        }
        if (content.trim().isBlank()) {
            _errorMessage.value = validationError
            return
        }

        val replySnapshot = textComposer.captureReply()
        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            val result = groupRepository.sendGroupMessage(
                groupId = groupId,
                content = content.trim(),
                contentType = contentType,
                mediaUrl = mediaUrl,
                replyToId = replySnapshot.replyTo?.id,
                replyTo = replySnapshot.replyTo,
                onQueued = { withContext(Dispatchers.Main.immediate) { textComposer.queuedReply(replySnapshot) } }
            )
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()
                    .toUserFacingMessage("No se pudo enviar el mensaje.")
            }
            _isSending.value = false
        }
    }

    private fun refreshPollStates(messages: List<GroupMessage>) {
        if (knownPollSnapshot !== messages) {
            knownPollSnapshot = messages
            knownPollIds = messages.mapNotNull { it.poll_id?.trim()?.takeIf(String::isNotBlank) }.toSet()
        }
        val visibleMessages = visibleMessageLookup.find(messages, _visibleMessageIds.value)
        val visiblePollMessages = visibleMessages.map { it.id to it.poll_id }
        _pollStates.update { it.filterKeys { id -> id in knownPollIds } }
        pollRefresh.update(
            active = _isScreenActive.value,
            room = groupId,
            visiblePollIds = visiblePollIds(visiblePollMessages, _visibleMessageIds.value),
        )
    }

    private fun refreshVisiblePolls() {
        val messages = (_uiState.value as? GroupChatUiState.Success)?.messages.orEmpty()
        refreshPollStates(messages)
    }

    fun createPoll(question: String, options: List<String>, allowsMultiple: Boolean) {
        if (_isCreatingPoll.value) return
        val cleanQuestion = question.trim()
        val cleanOptions = options.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleanQuestion.isBlank()) {
            _errorMessage.value = "La pregunta es obligatoria"
            return
        }
        if (cleanOptions.size < 2) {
            _errorMessage.value = "La encuesta requiere al menos 2 opciones"
            return
        }

        viewModelScope.launch {
            _isCreatingPoll.value = true
            val result = groupRepository.sendGroupPollMessage(
                groupId = groupId,
                question = cleanQuestion,
                options = cleanOptions,
                allowsMultiple = allowsMultiple
            )
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()
                    .toUserFacingMessage("No se pudo crear la encuesta.")
            }
            _isCreatingPoll.value = false
        }
    }

    fun votePollOption(pollId: String, optionId: String, selected: Boolean = true) {
        val voteRoom = groupId
        viewModelScope.launch {
            pollVoteMutex.lock()
            try {
                if (ownerAccountId == null || !authService.getCurrentUserId().equals(ownerAccountId, true) ||
                    voteRoom != groupId
                ) return@launch
                val previous = _pollStates.value[pollId]
                if (previous.isNullOrEmpty() || previous.first().isClosed || previous.none { it.optionId == optionId }) return@launch
                pollRefresh.voteStarted(pollId)
                _pollStates.update { it + (pollId to applyOptimisticPollVote(previous, optionId, selected)) }
                val result = groupRepository.votePollOption(pollId, optionId, selected)
                if (!authService.getCurrentUserId().equals(ownerAccountId, true) || voteRoom != groupId) return@launch
                if (result.isSuccess && !result.getOrThrow().isEmpty()) {
                    _pollStates.update { it + (pollId to result.getOrThrow()) }
                } else {
                    _pollStates.update { it + (pollId to previous) }
                    _errorMessage.value = result.exceptionOrNull()
                        .toUserFacingMessage("No se pudo registrar tu voto.")
                }
            } finally {
                pollRefresh.voteFinished(pollId)
                pollVoteMutex.unlock()
            }
        }
    }

    fun openPollOptionVoters(pollId: String, option: MessagePollOptionState) {
        _pollOptionVoters.value = GroupPollOptionVotersUiState(
            pollId = pollId,
            optionId = option.optionId,
            optionText = option.optionText,
            voteCount = option.voteCount,
            voters = emptyList(),
            isLoading = true
        )

        viewModelScope.launch {
            val voters = groupRepository.getPollOptionVoters(pollId, option.optionId).getOrElse {
                _errorMessage.value = it.message ?: "No se pudo cargar quién votó."
                emptyList()
            }
            val profiles = userRepository.getUsersByIds(voters.map { it.userId }).getOrDefault(emptyList())
            val resolvedVoters = resolvePollVoterIdentities(voters, profiles)
            _pollOptionVoters.update { current ->
                if (current == null || current.pollId != pollId || current.optionId != option.optionId) {
                    current
                } else {
                    current.copy(voters = resolvedVoters, isLoading = false)
                }
            }
        }
    }

    fun dismissPollOptionVoters() {
        _pollOptionVoters.value = null
    }

    private fun applyOptimisticPollVote(
        state: List<MessagePollOptionState>,
        optionId: String,
        selected: Boolean
    ): List<MessagePollOptionState> {
        if (state.isEmpty()) return state
        val allowsMultiple = state.firstOrNull()?.allowsMultiple ?: false
        val updated = state.map { option ->
            val newVoted = when {
                option.optionId == optionId -> selected
                !allowsMultiple && selected -> false
                else -> option.votedByMe
            }
            val newCount = when {
                option.votedByMe && !newVoted -> maxOf(0L, option.voteCount - 1L)
                !option.votedByMe && newVoted -> option.voteCount + 1L
                else -> option.voteCount
            }
            option.copy(votedByMe = newVoted, voteCount = newCount)
        }
        val totalVotes = updated.sumOf { it.voteCount }
        return updated.map { it.copy(totalVotes = totalVotes) }
    }

    fun openMessageInfo(message: conectate.com.data.model.Message) {
        _messageInfoTarget.value = message
        _isLoadingMessageInfo.value = true
        _messageInfoReceipts.value = emptyList()

        viewModelScope.launch {
            val currentUserId = getCurrentUserId()
            val receipts = groupRepository.getGroupMessageReadReceipts(message.id).getOrElse { emptyList() }
            _messageInfoReceipts.value = receipts.filter { it.userId != currentUserId }
            _isLoadingMessageInfo.value = false
        }
    }

    fun closeMessageInfo() {
        _messageInfoTarget.value = null
        _messageInfoReceipts.value = emptyList()
        _isLoadingMessageInfo.value = false
    }

    // Message Actions
    fun onSwipeToReply(message: conectate.com.data.model.Message) {
        if (message.deleted_for_all) return
        textComposer.updateReply(message)
    }

    fun onMessageLongClick(message: conectate.com.data.model.Message) {
        if (message.deleted_for_all && message.sender_id != getCurrentUserId()) return
        _selectedMessage.value = message
    }

    fun dismissMessageActions() {
        _selectedMessage.value = null
    }

    fun openReactionPicker(message: conectate.com.data.model.Message) {
        if (message.deleted_for_all) return
        _reactionPickerMessage.value = message
        _selectedMessage.value = null
    }

    fun dismissReactionPicker() {
        _reactionPickerMessage.value = null
    }

    fun reactToMessage(message: conectate.com.data.model.Message, emoji: String) {
        viewModelScope.launch {
            val result = groupRepository.toggleGroupMessageReaction(message.id, emoji)
            if (result.isFailure) {
                _errorMessage.value = "No se pudo reaccionar"
            }
            _reactionPickerMessage.value = null
        }
    }

    fun openForwardPicker(message: conectate.com.data.model.Message) {
        val forwardingError = message.toForwardMessagePayload().forwardingError
        if (forwardingError != null) {
            _errorMessage.value = forwardingError
            return
        }
        _forwardPickerMessage.value = message
        _selectedMessage.value = null
    }

    val favoriteStickerMediaKeys = stickerRepository.favoriteMediaKeys

    fun isStickerFavorite(mediaUrl: String?, mediaKeys: Set<String>): Boolean =
        conectate.com.data.repository.StickerIdentity.mediaKey(mediaUrl) in mediaKeys

    fun toggleStickerFavorite(message: conectate.com.data.model.Message) {
        val url = message.media_url?.trim().orEmpty()
        if (url.isBlank()) {
            _errorMessage.value = "Sticker sin archivo para guardar."
            _selectedMessage.value = null
            return
        }
        viewModelScope.launch {
            val change = if (stickerRepository.isFavoriteMediaUrl(url)) {
                stickerRepository.removeFavoriteByMedia(url)
            } else {
                stickerRepository.addFavoriteByMedia(url, message.content.ifBlank { "Sticker" })
            }
            _errorMessage.value = when (change) {
                StickerRepository.FavoriteChange.ADDED -> "Sticker añadido a favoritos"
                StickerRepository.FavoriteChange.ALREADY_PRESENT -> "Ya tienes este sticker en favoritos"
                StickerRepository.FavoriteChange.REMOVED -> "Sticker quitado de favoritos"
                else -> "No se pudo comprobar el sticker. Inténtalo de nuevo"
            }
        }
    }

    fun dismissForwardPicker() {
        _forwardPickerMessage.value = null
    }

    fun forwardMessageToSelectedGroups(targetGroupIds: Set<String>) {
        val sourceMessage = _forwardPickerMessage.value ?: return
        if (targetGroupIds.isEmpty()) {
            _errorMessage.value = "Selecciona al menos un grupo"
            return
        }
        if (_isForwarding.value) return

        viewModelScope.launch {
            _isForwarding.value = true
            val groupMessage = GroupMessage(
                id = sourceMessage.id,
                group_id = sourceMessage.chat_id,
                sender_id = sourceMessage.sender_id,
                content = sourceMessage.content,
                content_type = sourceMessage.content_type,
                media_url = sourceMessage.media_url,
                poll_id = sourceMessage.poll_id,
                created_at = sourceMessage.created_at ?: "",
                status = sourceMessage.status,
                sender_name = sourceMessage.sender_name,
                reply_to_id = sourceMessage.reply_to_id,
                is_edited = sourceMessage.is_edited,
                deleted_for_all = sourceMessage.deleted_for_all,
                deleted_by = sourceMessage.deleted_by,
                reactions = sourceMessage.reactions,
                is_forwarded = sourceMessage.is_forwarded,
                forwarded_from_message_id = sourceMessage.forwarded_from_message_id,
                forwarded_from_user_id = sourceMessage.forwarded_from_user_id
            )
            val result = groupRepository.forwardGroupMessageToGroups(groupMessage, targetGroupIds.toList())
            if (result.isSuccess) {
                _forwardPickerMessage.value = null
                _errorMessage.value = null
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "No se pudo reenviar"
            }
            _isForwarding.value = false
        }
    }

    fun cancelReply() {
        textComposer.updateReply(null)
    }

    fun onEditMessage(message: conectate.com.data.model.Message) {
        if (message.content_type != "text" || message.deleted_for_all) return
        _editingMessage.value = message
        textComposer.beginEditing(message.content)
        _selectedMessage.value = null
    }

    fun cancelEdit() {
        _editingMessage.value = null
        textComposer.finishEditing()
    }

    fun confirmEditMessage() {
        if (_isSending.value) return
        val msg = _editingMessage.value ?: return
        val snapshot = textComposer.captureEdit()
        if (snapshot.text.isBlank()) {
            _errorMessage.value = "Escribe un mensaje para guardar la edición."
            return
        }
        if (snapshot.text == msg.content) { cancelEdit(); return }
        val owner = authService.getCurrentUserId() ?: return
        val destination = groupId
        fun current() = owner == authService.getCurrentUserId() && destination == groupId
        _isSending.value = true
        viewModelScope.launch {
            try {
                val result = groupRepository.editGroupMessage(msg.id, snapshot.text)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!current()) return@launch
                if (result.isSuccess) {
                    if (_editingMessage.value?.id == msg.id && textComposer.finishEditingIfUnchanged(snapshot)) {
                        _editingMessage.value = null
                    }
                } else { _errorMessage.value = "No se pudo guardar la edición. Reintenta." }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (current()) { _errorMessage.value = "No se pudo guardar la edición. Reintenta." } }
            finally { if (current()) _isSending.value = false }
        }
    }

    fun deleteMessageForEveryone(message: conectate.com.data.model.Message) {
        if (!canDeleteSelectionForEveryone(setOf(message.id))) {
            _errorMessage.value = "Solo puedes eliminar para todos tus propios mensajes disponibles"
            return
        }
        viewModelScope.launch {
            if (groupRepository.deleteGroupMessage(message.id, true).isFailure) {
                _errorMessage.value = "No se pudo eliminar para todos"
            }
        }
    }

    private fun canDeleteSelectionForEveryone(ids: Set<String>): Boolean {
        val messages = (_uiState.value as? GroupChatUiState.Success)?.messages.orEmpty()
        return MessageDeletionPolicy.canDeleteForEveryone(getCurrentUserId(), ids, messages.map {
            MessageDeletionPolicy.Candidate(it.id, it.sender_id, it.deleted_for_all)
        })
    }

    fun deleteMessagesForMe(ids: Set<String>, onComplete: (Set<String>) -> Unit) =
        deleteMessages(ids, forEveryone = false, onComplete = onComplete)

    fun deleteMessagesForEveryone(ids: Set<String>, onComplete: (Set<String>) -> Unit) =
        deleteMessages(ids, forEveryone = true, onComplete = onComplete)

    private fun deleteMessages(ids: Set<String>, forEveryone: Boolean, onComplete: (Set<String>) -> Unit) {
        if (forEveryone && !canDeleteSelectionForEveryone(ids)) {
            _errorMessage.value = "La selección incluye mensajes que no puedes eliminar para todos"
            onComplete(emptySet())
            return
        }
        viewModelScope.launch {
            val deleted = mutableSetOf<String>()
            try {
                for (id in ids) if (groupRepository.deleteGroupMessage(id, forEveryone).isSuccess) deleted += id
                if (deleted.size < ids.size) _errorMessage.value = "Algunos mensajes no se pudieron eliminar. Siguen seleccionados."
            } finally { onComplete(deleted) }
        }
    }

    fun deleteMessageForMe(message: conectate.com.data.model.Message) {
        viewModelScope.launch {
            groupRepository.deleteGroupMessage(message.id, false)
        }
    }

    fun copyToClipboard(context: android.content.Context, text: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Mensaje", text)
        clipboard.setPrimaryClip(clip)
    }

    // --- Voice Logic (Delegated) ---
    
    fun startRecording() {
         stopAudio()
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
            voiceMessageManager.stopRecordingForReview()
        } else {
            viewModelScope.launch {
                val (file, duration) = voiceMessageManager.stopAndGetFile()
                if (file != null) {
                    sendVoiceMessage(file, duration)
                }
            }
        }
    }
    
    fun sendCurrentRecording() {
        val file = voiceMessageManager.getCurrentFile()
        val duration = recordingDuration.value
        if (file != null) {
            sendVoiceMessage(file, duration)
            voiceMessageManager.reset()
        }
    }
    
    private fun sendVoiceMessage(file: File, durationMs: Long) {
         if (durationMs > 1000) {
            viewModelScope.launch {
                _isSending.value = true
                val bytes = try {
                    withContext(Dispatchers.IO) {
                        val coroutine = kotlinx.coroutines.currentCoroutineContext()
                        file.inputStream().use { input ->
                            input.readMediaBytesWithLimit(60L * 1024 * 1024) { coroutine.ensureActive() }
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    _isSending.value = false
                    throw cancelled
                } catch (error: Exception) {
                    _errorMessage.value = "No se pudo preparar el audio. Verifica el archivo y vuelve a intentarlo."
                    _isSending.value = false
                    return@launch
                }
                val uploadResult = groupRepository.uploadGroupAudio(groupId, bytes)
                if (uploadResult.isSuccess) {
                    val url = uploadResult.getOrThrow()
                    val durationLabel = formatDuration(durationMs)
                    val sendResult = groupRepository.sendGroupMessage(
                        groupId = groupId,
                        content = durationLabel,
                        contentType = "audio",
                        mediaUrl = url
                    )
                    if (sendResult.isFailure) {
                        val reason = sendResult.exceptionOrNull()?.message ?: "Error desconocido"
                        _errorMessage.value = "Error al enviar audio: $reason"
                    }
                } else {
                    val reason = uploadResult.exceptionOrNull()?.message
                    _errorMessage.value = if (reason.isNullOrBlank()) {
                        "Error al subir audio"
                    } else {
                        "Error al subir audio: $reason"
                    }
                }
                _isSending.value = false
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        return voiceMessageManager.formatDuration(ms)
    }

    private var audioPrepareJob: kotlinx.coroutines.Job? = null
    private var audioMetadataJob: kotlinx.coroutines.Job? = null
    private var audioDelivery: conectate.com.util.NativeAudioDelivery? = null
    private var audioAttempt = 0L

    fun playAudio(message: conectate.com.data.model.Message) {
        val id = message.id
        val url = message.media_url ?: return
        voiceMessageManager.pausePreview()
        if (_currentPlayingAudioId.value == id) {
            val current = mediaPlayer
            if (current == null) { stopAudio(); return }
            try {
                audioDelivery?.requireCurrent()
                if (current.isPlaying) { current.pause(); _isAudioPlaying.value = false }
                else if (current.start(_audioPlaybackSpeed.value)) { _isAudioPlaying.value = true; startProgressTracker() }
            } catch (_: Exception) { stopAudio() }
            return
        }
        stopAudio()
        val attempt = audioAttempt
        _currentPlayingAudioId.value = id
        audioPrepareJob = viewModelScope.launch {
            var prepared: conectate.com.util.NativeAudioDelivery? = null
            try {
                val delivery = messageRepository.prepareNativeAudio(url)
                prepared = delivery
                if (attempt != audioAttempt || !_isScreenActive.value) { delivery.close(); return@launch }
                delivery.requireCurrent()
                if (attempt != audioAttempt || !_isScreenActive.value) { delivery.close(); return@launch }
                delivery.requireCurrent()
                audioDelivery = delivery
                val player = conectate.com.util.MessageAudioPlayer()
                mediaPlayer = player
                player.prepare(delivery.source, onPrepared = preparedCallback@ { ready ->
                    if (mediaPlayer !== ready || attempt != audioAttempt) return@preparedCallback
                    try {
                        delivery.requireCurrent()
                        if (!_isScreenActive.value) { stopAudio(); return@preparedCallback }
                        if (ready.duration > 0) _audioDurations.value = (_audioDurations.value + (id to ready.duration.toLong())).entries.toList().takeLast(256).associate { it.toPair() }
                        if (ready.start(_audioPlaybackSpeed.value)) {
                            _isAudioPlaying.value = true; startProgressTracker()
                        }
                    } catch (_: Exception) { stopAudio() }
                }, onCompletion = { if (mediaPlayer === player) stopAudio() },
                    onError = {
                        if (mediaPlayer === player) {
                            stopAudio()
                            conectate.com.presentation.common.AppFeedbackBus.showError("No se pudo reproducir el audio. Inténtalo de nuevo.")
                        }
                    })
                if (attempt != audioAttempt) return@launch
                audioMetadataJob = viewModelScope.launch {
                    try {
                        messageRepository.localAudioDurationMs(delivery.source)?.let { total ->
                            if (attempt == audioAttempt) _audioDurations.value = (_audioDurations.value + (id to total)).entries.toList().takeLast(256).associate { it.toPair() }
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Optional duration must not stop playback. */ }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                prepared?.close(); throw cancelled
            } catch (_: Exception) {
                prepared?.close()
                if (attempt == audioAttempt) stopAudio()
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                try { audioDelivery?.requireCurrent() }
                catch (_: Exception) { stopAudio(); break }
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        _audioProgress.value = conectate.com.util.audioPlaybackProgress(it.currentPosition.toLong(), it.duration.toLong())
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
        val safeFraction = conectate.com.util.finiteAudioProgress(fraction)
        player.seekTo((player.duration * safeFraction).toInt())
        _audioProgress.value = safeFraction
        _audioDurationLabel.value = formatDuration(player.currentPosition.toLong())
    }

    fun cycleAudioPlaybackSpeed() {
        _audioPlaybackSpeed.value = when (_audioPlaybackSpeed.value) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        mediaPlayer?.let(::applyAudioPlaybackSpeed)
    }

    private fun applyAudioPlaybackSpeed(player: conectate.com.util.MessageAudioPlayer) = player.setSpeed(_audioPlaybackSpeed.value)

    private fun stopAudio() {
        progressJob?.cancel()
        _currentPlayingAudioId.value = null
        _isAudioPlaying.value = false
        _audioProgress.value = 0f
        _audioDurationLabel.value = "0:00"
        audioAttempt++
        audioPrepareJob?.cancel()
        audioPrepareJob = null
        audioMetadataJob?.cancel()
        audioMetadataJob = null
        val oldPlayer = mediaPlayer
        mediaPlayer = null
        runCatching { oldPlayer?.release() }
        audioDelivery?.close()
        audioDelivery = null
    }

    override fun onCleared() {
        super.onCleared()
        pollRefresh.stop()
        stopAudio()
        voiceMessageManager.reset()
        groupRepository.setActiveGroup(null) // Called synchronously to ensure execution
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun leaveGroup(onSuccess: () -> Unit) {
        val cleanId = groupId.trim().lowercase()
        if (cleanId.isBlank()) return
        viewModelScope.launch {
            val result = groupRepository.leaveGroup(cleanId)
            if (result.isSuccess) {
                groupRepository.syncGroups()
                onSuccess()
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "No se pudo salir del grupo."
            }
        }
    }

    fun deleteGroupLocally(onSuccess: () -> Unit) {
        viewModelScope.launch {
            runCatching { groupRepository.deleteGroupLocally(groupId) }
                .onSuccess { onSuccess() }
                .onFailure { _errorMessage.value = "No se pudo borrar el chat. Inténtalo de nuevo." }
        }
    }

    fun getCurrentUserId(): String = authService.getCurrentUserId() ?: ""
    fun getCurrentUserEmail(): String? = authService.getCurrentUserEmail()

    fun voiceMessageManagerIsPreviewPlaying(): Boolean {
        return voiceMessageManager.isPreviewPlaying()
    }

    suspend fun getPlayableUrl(
        path: String,
        allowOfflineMediaCache: Boolean = true
    ): String? = messageRepository.getPlayableUrl(path, allowOfflineMediaCache).getOrNull()

    suspend fun consumeViewOnceMessage(message: conectate.com.data.model.Message): String? {
        val mediaPath = message.media_url ?: return null
        return messageRepository.consumeViewOnceMedia(
            scopeType = "group",
            messageId = message.id,
            mediaPath = mediaPath
        ).getOrNull()
    }
}

data class GroupForwardTarget(
    val groupId: String,
    val title: String
)
