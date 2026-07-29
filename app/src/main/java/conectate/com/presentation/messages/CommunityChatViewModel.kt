package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import conectate.com.core.messaging.UnreadPolicy
import conectate.com.data.model.Community
import conectate.com.data.model.CommunityChannel
import conectate.com.data.model.CommunityMember
import conectate.com.data.model.CommunityMessage
import conectate.com.data.model.MessagePollOptionState
import conectate.com.data.model.MessageReadReceipt
import conectate.com.data.model.PollVoteUser
import conectate.com.data.repository.CommunityRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.model.User
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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

sealed class CommunityChatUiState {
    object Loading : CommunityChatUiState()
    data class Success(
        val community: Community,
        val messages: List<CommunityMessage>,
        val isMember: Boolean,
        val memberProfiles: Map<String, User> = emptyMap(),
        val memberIds: List<String> = emptyList()
    ) : CommunityChatUiState()
    data class Error(val message: String) : CommunityChatUiState()
}

data class CommunityPollOptionVotersUiState(
    val pollId: String,
    val optionId: String,
    val optionText: String,
    val voteCount: Long,
    val voters: List<PollVoteUser> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class CommunityChatViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
    private val userRepository: UserRepository,
    private val authService: AuthService,
    private val voiceMessageManager: VoiceMessageManager,
    private val messageRepository: MessageRepository,
    private val stickerRepository: StickerRepository,
    private val chatSettingsRepository: ChatSettingsRepository,
    private val messageSessionStateStore: MessageSessionStateStore
) : ViewModel() {

    private val _uiState = MutableStateFlow<CommunityChatUiState>(CommunityChatUiState.Loading)
    val uiState: StateFlow<CommunityChatUiState> = _uiState.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

    // Voice Manager State Expose
    val voiceState = voiceMessageManager.state
    val recordingDuration = voiceMessageManager.duration
    val voicePlaybackProgress = voiceMessageManager.playbackProgress
    val voicePlaybackDurationLabel = voiceMessageManager.playbackDurationLabel

    private val _currentPlayingAudioId = MutableStateFlow<String?>(null)
    val currentPlayingAudioId: StateFlow<String?> = _currentPlayingAudioId.asStateFlow()

    private val _audioProgress = MutableStateFlow(0f)
    val audioProgress: StateFlow<Float> = _audioProgress.asStateFlow()

    private val _audioDurationLabel = MutableStateFlow("0:00")
    val audioDurationLabel: StateFlow<String> = _audioDurationLabel.asStateFlow()

    private val _replyToMessage = MutableStateFlow<conectate.com.data.model.Message?>(null)
    val replyToMessage: StateFlow<conectate.com.data.model.Message?> = _replyToMessage.asStateFlow()

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
    private val _activeCommunityId = MutableStateFlow<String?>(null)
    private val _chatBackgroundSettings = MutableStateFlow(ChatBackgroundSettings())
    val chatBackgroundSettings = _chatBackgroundSettings.asStateFlow()
    private var chatBackgroundJob: kotlinx.coroutines.Job? = null

    private var mediaPlayer: android.media.MediaPlayer? = null
    private var progressJob: kotlinx.coroutines.Job? = null

    private var communityId: String = ""
    private val _visibleMessageIds = MutableStateFlow<Set<String>>(emptySet())
    private val _isScreenActive = MutableStateFlow(false)

    val forwardTargets: StateFlow<List<CommunityForwardTarget>> = combine(
        communityRepository.getCommunitiesFlow(),
        _activeCommunityId
    ) { communities, currentCommunityId ->
        val current = currentCommunityId?.trim()?.lowercase()
        communities
            .mapNotNull { item ->
                val id = item.community.id.trim().lowercase()
                if (id.isBlank() || id == current) return@mapNotNull null
                CommunityForwardTarget(
                    communityId = id,
                    title = item.community.name.ifBlank { "Comunidad" }
                )
            }
            .distinctBy { it.communityId }
            .sortedBy { it.title.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _pollStates = MutableStateFlow<Map<String, List<MessagePollOptionState>>>(emptyMap())
    val pollStates: StateFlow<Map<String, List<MessagePollOptionState>>> = _pollStates.asStateFlow()
    private val pollRetryJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private val _isCreatingPoll = MutableStateFlow(false)
    val isCreatingPoll: StateFlow<Boolean> = _isCreatingPoll.asStateFlow()

    private val _messageInfoTarget = MutableStateFlow<conectate.com.data.model.Message?>(null)
    val messageInfoTarget: StateFlow<conectate.com.data.model.Message?> = _messageInfoTarget.asStateFlow()

    private val _messageInfoReceipts = MutableStateFlow<List<MessageReadReceipt>>(emptyList())
    val messageInfoReceipts: StateFlow<List<MessageReadReceipt>> = _messageInfoReceipts.asStateFlow()

    private val _isLoadingMessageInfo = MutableStateFlow(false)
    val isLoadingMessageInfo: StateFlow<Boolean> = _isLoadingMessageInfo.asStateFlow()
    private val _pollOptionVoters = MutableStateFlow<CommunityPollOptionVotersUiState?>(null)
    val pollOptionVoters: StateFlow<CommunityPollOptionVotersUiState?> = _pollOptionVoters.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    private val _myRole = MutableStateFlow<String?>(null)
    val myRole: StateFlow<String?> = _myRole.asStateFlow()

    private val _channels = MutableStateFlow<List<CommunityChannel>>(emptyList())
    val channels: StateFlow<List<CommunityChannel>> = _channels.asStateFlow()

    private val _selectedChannel = MutableStateFlow<CommunityChannel?>(null)
    val selectedChannel: StateFlow<CommunityChannel?> = _selectedChannel.asStateFlow()

    fun init(communityId: String) {
        val cleanId = communityId.trim().lowercase()
        if (cleanId.isBlank()) return
        this.communityId = cleanId
        _activeCommunityId.value = cleanId
        observeChatBackground(cleanId)
        // Voice Manager
        voiceMessageManager.setScope(viewModelScope)
        voiceMessageManager.reset()
        
        loadCommunity()
        loadChannels()
        viewModelScope.launch {
            communityRepository.setActiveCommunity(cleanId)
            communityRepository.markMessagesAsDelivered(cleanId)
            communityRepository.markAsRead(cleanId)
        }
    }

    fun loadChannels() {
        val id = communityId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            communityRepository.getCommunityChannels(id)
                .onSuccess { catalog ->
                    _channels.value = catalog.channels
                    val selectedId = _selectedChannel.value?.id
                    _selectedChannel.value = catalog.channels.firstOrNull { it.id == selectedId }
                        ?: catalog.channels.firstOrNull { it.isDefault }
                        ?: catalog.channels.firstOrNull()
                }
                .onFailure {
                    _errorMessage.value = it.toUserFacingMessage("No se pudieron cargar los canales.")
                }
        }
    }

    fun selectChannel(channel: CommunityChannel) {
        if (_channels.value.none { it.id == channel.id }) return
        _selectedChannel.value = channel
        _replyToMessage.value = null
        _selectedMessage.value = null
    }

    fun createChannel(name: String, description: String?, type: String) {
        if (name.isBlank()) {
            _errorMessage.value = "Escribe un nombre para el canal."
            return
        }
        viewModelScope.launch {
            _isSending.value = true
            communityRepository.createCommunityChannel(communityId, name, description, type)
                .onSuccess {
                    loadChannels()
                    _selectedChannel.value = it
                }
                .onFailure {
                    _errorMessage.value = it.toUserFacingMessage("No se pudo crear el canal.")
                }
            _isSending.value = false
        }
    }

    fun setChannelPaused(channel: CommunityChannel, paused: Boolean, reason: String? = null, minutes: Int = 60) {
        viewModelScope.launch {
            _isSending.value = true
            communityRepository.pauseCommunityChannel(
                communityId = communityId,
                channelId = channel.id,
                paused = paused,
                minutes = minutes,
                reason = reason
            ).onSuccess { updated ->
                _channels.update { items -> items.map { if (it.id == updated.id) updated else it } }
                if (_selectedChannel.value?.id == updated.id) _selectedChannel.value = updated
            }.onFailure {
                _errorMessage.value = it.toUserFacingMessage("No se pudo actualizar la pausa del canal.")
            }
            _isSending.value = false
        }
    }

    fun deleteChannel(channel: CommunityChannel) {
        viewModelScope.launch {
            _isSending.value = true
            communityRepository.deleteCommunityChannel(communityId, channel.id)
                .onSuccess { loadChannels() }
                .onFailure {
                    _errorMessage.value = it.toUserFacingMessage("No se pudo eliminar el canal.")
                }
            _isSending.value = false
        }
    }

    private fun writableChannelOrNull(): CommunityChannel? {
        val channel = _selectedChannel.value
        if (channel?.isPaused == true) {
            _errorMessage.value = channel.pauseReason
                ?.takeIf { it.isNotBlank() }
                ?.let { "Este canal está pausado: $it" }
                ?: "Este canal está pausado temporalmente."
            return null
        }
        if (channel?.type == "voice") {
            _errorMessage.value = "Este es un canal de voz. Ábrelo para conversar."
            return null
        }
        return channel
    }

    private fun backgroundKey(id: String) = "community:${id.trim().lowercase()}"

    private fun observeChatBackground(id: String) {
        chatBackgroundJob?.cancel()
        chatBackgroundJob = viewModelScope.launch {
            chatSettingsRepository.observeChatBackground(backgroundKey(id)).collectLatest {
                _chatBackgroundSettings.value = it
            }
        }
    }

    fun setChatBackground(type: String, uri: String? = null) {
        val id = communityId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch { chatSettingsRepository.setChatBackground(backgroundKey(id), type, uri) }
    }

    fun clearChatBackground() {
        val id = communityId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch { chatSettingsRepository.clearChatBackground(backgroundKey(id)) }
    }

    fun onScreenVisibilityChanged(isVisible: Boolean) {
        _isScreenActive.value = isVisible
        val cleanId = communityId.trim().lowercase()
        if (cleanId.isBlank()) return
        if (isVisible) {
            messageSessionStateStore.setActiveConversation("community", cleanId, true)
            viewModelScope.launch {
                communityRepository.setActiveCommunity(cleanId)
                communityRepository.markMessagesAsDelivered(cleanId)
                communityRepository.markAsRead(cleanId)
            }
        } else {
            messageSessionStateStore.setActiveConversation("community", cleanId, false)
            communityRepository.setActiveCommunity(null)
        }
    }

    fun onVisibleMessageIdsChanged(visibleMessageIds: Set<String>) {
        _visibleMessageIds.value = visibleMessageIds
        if (!_isScreenActive.value) return
        val cleanId = communityId.trim().lowercase()
        if (cleanId.isBlank()) return
        val state = _uiState.value as? CommunityChatUiState.Success ?: return
        maybeMarkAsRead(cleanId, state.messages, visibleMessageIds)
    }

    private fun maybeMarkAsRead(communityId: String, messages: List<CommunityMessage>, visibleMessageIds: Set<String>) {
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
            communityRepository.markAsRead(communityId, readableVisibleIds)
        }
    }

    private fun loadCommunity() {
        viewModelScope.launch {
            // Observe community status for deletion
            launch {
                communityRepository.getCommunityFlow(communityId).collect { comm: Community? ->
                    if (comm == null || comm.is_deleted) {
                        _isDeleted.value = true
                    }
                }
            }

            // 1. Initial Load from Local Room
            val community = communityRepository.getCommunityById(communityId) ?: run {
                _isDeleted.value = true
                return@launch
            }
            
            // 2. Background Sync (Don't let it block or kill the VM)
            launch {
                try {
                    communityRepository.syncCommunities()
                } catch (e: Exception) {
                    android.util.Log.e("CommunityChatVM", "Background sync failed", e)
                }
            }

            val userId = authService.getCurrentUserId() ?: ""
            
            // 1. Force sync members
            val members = communityRepository.getCommunityMembers(communityId).getOrNull() ?: emptyList()
            _myRole.value = members.find { it.user_id == userId }?.role
            
            // 2. Fetch User profiles for members to get names/emails
            val userIds = members.map { it.user_id }
            val memberIds = userIds
            val fetchedProfiles = userRepository.getUsersByIds(userIds).getOrNull()
                ?.associateBy { it.id.trim().lowercase() }
                ?: emptyMap()
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
            
            // 3. Observe Messages (Directly, more stable)
            val messagesFlow = communityRepository.getCommunityMessagesFlow(communityId)
            
            // 4. Observe Membership separately to avoid UI resets
            val membershipFlow = communityRepository.isUserMemberFlow(communityId, userId)
            
            viewModelScope.launch {
                membershipFlow.collect { isMember ->
                    _uiState.update { state ->
                        if (state is CommunityChatUiState.Success) {
                            state.copy(isMember = isMember, memberProfiles = memberProfiles)
                        } else {
                            CommunityChatUiState.Success(
                                community = community,
                                messages = emptyList(),
                                isMember = isMember,
                                memberProfiles = memberProfiles,
                                memberIds = memberIds
                            )
                        }
                    }
                }
            }

            // Observe Role Reactively
            viewModelScope.launch {
                communityRepository.getUserRoleFlow(communityId, userId).collect { role ->
                    _myRole.value = role
                }
            }

            launch {
                communityRepository.getCommunityFlow(communityId).collect { comm ->
                    if (comm != null) {
                        _uiState.update { state ->
                            if (state is CommunityChatUiState.Success) {
                                state.copy(community = comm)
                            } else state
                        }
                    }
                }
            }

            messagesFlow.collect { messages ->
                android.util.Log.d("CommunityChatVM", "Messages updated in UI: ${messages.size}")
                refreshPollStates(messages)
                val currentState = _uiState.value
                val isMember = if (currentState is CommunityChatUiState.Success) currentState.isMember else false
                val currentComm = communityRepository.getCommunityById(communityId) ?: community
                _uiState.value = CommunityChatUiState.Success(
                    community = currentComm,
                    messages = messages,
                    isMember = isMember,
                    memberProfiles = memberProfiles,
                    memberIds = memberIds
                )
                maybeMarkAsRead(communityId.trim().lowercase(), messages, _visibleMessageIds.value)
            }
        }
    }

    fun updateMessageText(text: String) {
        _messageText.value = text
    }

    fun sendMessage() {
        val content = _messageText.value
        if (content.isBlank() || _isSending.value) return

        val channel = writableChannelOrNull()
        if (_selectedChannel.value != null && channel == null) return
        val state = _uiState.value as? CommunityChatUiState.Success
        val onlyAdmins = state?.community?.only_admins_can_talk ?: false
        val isAdmin = _myRole.value == "admin" || _myRole.value == "owner" || (state?.community?.creator_id == getCurrentUserId())
        
        if (onlyAdmins && !isAdmin) {
             _errorMessage.value = "Solo los administradores pueden escribir en esta comunidad."
             return
        }

        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            
            val replyTo = _replyToMessage.value
            _replyToMessage.value = null
            _messageText.value = "" // Optimistic UI clear
            
            val result = communityRepository.sendCommunityMessage(
                communityId,
                content,
                replyToId = replyTo?.id,
                replyTo = replyTo,
                channelId = channel?.id
            )
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()
                    .toUserFacingMessage("No se pudo enviar el mensaje.")
                _messageText.value = content
                _replyToMessage.value = replyTo
            }
            _isSending.value = false
        }
    }

    fun sendMediaMessage(
        uri: android.net.Uri,
        context: android.content.Context,
        forcedMessageType: String? = null,
        customContent: String? = null
    ) {
        val channel = writableChannelOrNull()
        if (_selectedChannel.value != null && channel == null) return
        val state = _uiState.value as? CommunityChatUiState.Success
        val onlyAdmins = state?.community?.only_admins_can_talk ?: false
        val isAdmin = _myRole.value == "admin" || _myRole.value == "owner" || (state?.community?.creator_id == getCurrentUserId())
        
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
                val sendResult = communityRepository.sendCommunityMediaMessage(
                    communityId = communityId,
                    sourceUri = uri,
                    contentType = resolvedType,
                    content = content,
                    channelId = channel?.id
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
        if (mediaUrl.isBlank() || communityId.isBlank()) return
        val channel = writableChannelOrNull()
        if (_selectedChannel.value != null && channel == null) return
        viewModelScope.launch {
            val result = communityRepository.sendCommunityMessage(
                communityId = communityId,
                content = "🌀 GIF",
                contentType = "gif",
                mediaUrl = mediaUrl.trim(),
                channelId = channel?.id
            )
            if (result.isFailure) {
                android.util.Log.e("CommunityChatVM", "Recent GIF send failed", result.exceptionOrNull())
            }
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
        val channel = writableChannelOrNull()
        if (_selectedChannel.value != null && channel == null) return
        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            val result = communityRepository.sendCommunityMessage(
                communityId = communityId,
                content = caption,
                contentType = "sticker",
                mediaUrl = cleanUrl,
                channelId = channel?.id
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
            _errorMessage.value = "Completa latitud y longitud"
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
            validationError = "Completa latitud y longitud",
            mediaUrl = mapUrl
        )
    }

    private fun sendStructuredMessage(
        content: String,
        contentType: String,
        validationError: String,
        mediaUrl: String? = null
    ) {
        val channel = writableChannelOrNull()
        if (_selectedChannel.value != null && channel == null) return
        val state = _uiState.value as? CommunityChatUiState.Success
        val onlyAdmins = state?.community?.only_admins_can_talk ?: false
        val isAdmin = _myRole.value == "admin" || _myRole.value == "owner" || (state?.community?.creator_id == getCurrentUserId())
        if (onlyAdmins && !isAdmin) {
            _errorMessage.value = "Solo los administradores pueden escribir en esta comunidad."
            return
        }
        if (content.trim().isBlank()) {
            _errorMessage.value = validationError
            return
        }

        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            val result = communityRepository.sendCommunityMessage(
                communityId = communityId,
                content = content.trim(),
                contentType = contentType,
                mediaUrl = mediaUrl,
                channelId = channel?.id
            )
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()
                    .toUserFacingMessage("No se pudo enviar el mensaje.")
            }
            _isSending.value = false
        }
    }

    private fun refreshPollStates(messages: List<CommunityMessage>) {
        val pollIds = messages
            .mapNotNull { it.poll_id?.trim()?.ifBlank { null } }
            .distinct()
        if (pollIds.isEmpty()) {
            _pollStates.value = emptyMap()
            pollRetryJobs.values.forEach { it.cancel() }
            pollRetryJobs.clear()
            return
        }

        pollRetryJobs.keys
            .filter { existingId -> existingId !in pollIds }
            .forEach { staleId ->
                pollRetryJobs.remove(staleId)?.cancel()
            }

        pollIds.forEach { pollId ->
            val current = _pollStates.value[pollId]
            if (!current.isNullOrEmpty()) return@forEach
            if (pollRetryJobs[pollId]?.isActive == true) return@forEach

            pollRetryJobs[pollId] = viewModelScope.launch {
                repeat(6) { attempt ->
                    val fetched = communityRepository.getPollState(pollId).getOrNull().orEmpty()
                    if (fetched.isNotEmpty()) {
                        _pollStates.update { it + (pollId to fetched) }
                        return@launch
                    }
                    if (attempt < 5) delay(1000)
                }
                _pollStates.update { existing ->
                    if (existing[pollId].isNullOrEmpty()) existing + (pollId to emptyList()) else existing
                }
            }
        }
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
            val result = communityRepository.sendCommunityPollMessage(
                communityId = communityId,
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
        viewModelScope.launch {
            val previous = _pollStates.value[pollId]
            if (!previous.isNullOrEmpty()) {
                _pollStates.update { it + (pollId to applyOptimisticPollVote(previous, optionId, selected)) }
            }
            val result = communityRepository.votePollOption(pollId, optionId, selected)
            if (result.isSuccess) {
                _pollStates.update { it + (pollId to result.getOrThrow()) }
            } else {
                if (previous != null) {
                    _pollStates.update { it + (pollId to previous) }
                }
                _errorMessage.value = result.exceptionOrNull()
                    .toUserFacingMessage("No se pudo registrar tu voto.")
            }
        }
    }

    fun openPollOptionVoters(pollId: String, option: MessagePollOptionState) {
        _pollOptionVoters.value = CommunityPollOptionVotersUiState(
            pollId = pollId,
            optionId = option.optionId,
            optionText = option.optionText,
            voteCount = option.voteCount,
            voters = emptyList(),
            isLoading = true
        )

        viewModelScope.launch {
            val voters = communityRepository.getPollOptionVoters(pollId, option.optionId).getOrElse {
                _errorMessage.value = it.message ?: "No se pudo cargar quién votó."
                emptyList()
            }
            _pollOptionVoters.update { current ->
                if (current == null || current.pollId != pollId || current.optionId != option.optionId) {
                    current
                } else {
                    current.copy(voters = voters, isLoading = false)
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
            val receipts = communityRepository.getCommunityMessageReadReceipts(message.id).getOrElse { emptyList() }
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
        _replyToMessage.value = message
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
            val result = communityRepository.toggleCommunityMessageReaction(message.id, emoji)
            if (result.isFailure) {
                _errorMessage.value = "No se pudo reaccionar"
            }
            _reactionPickerMessage.value = null
        }
    }

    fun openForwardPicker(message: conectate.com.data.model.Message) {
        if (message.deleted_for_all) return
        _forwardPickerMessage.value = message
        _selectedMessage.value = null
    }

    fun isStickerFavorite(mediaUrl: String?): Boolean = stickerRepository.isFavoriteMediaUrl(mediaUrl)

    fun toggleStickerFavorite(message: conectate.com.data.model.Message) {
        val url = message.media_url?.trim().orEmpty()
        if (url.isBlank()) {
            _errorMessage.value = "Sticker sin archivo para guardar."
            _selectedMessage.value = null
            return
        }
        viewModelScope.launch {
            val isFavorite = stickerRepository.toggleFavoriteByMedia(
                mediaUrl = url,
                title = message.content.ifBlank { "Sticker" }
            )
            _errorMessage.value = if (isFavorite) {
                "Sticker añadido a favoritos"
            } else {
                "Sticker eliminado de favoritos"
            }
            _selectedMessage.value = null
        }
    }

    fun dismissForwardPicker() {
        _forwardPickerMessage.value = null
    }

    fun forwardMessageToSelectedCommunities(targetCommunityIds: Set<String>) {
        val sourceMessage = _forwardPickerMessage.value ?: return
        if (targetCommunityIds.isEmpty()) {
            _errorMessage.value = "Selecciona al menos una comunidad"
            return
        }
        if (_isForwarding.value) return

        viewModelScope.launch {
            _isForwarding.value = true
            val communityMessage = CommunityMessage(
                id = sourceMessage.id,
                community_id = sourceMessage.chat_id,
                sender_id = sourceMessage.sender_id,
                content = sourceMessage.content,
                content_type = sourceMessage.content_type,
                media_url = sourceMessage.media_url,
                poll_id = sourceMessage.poll_id,
                created_at = sourceMessage.created_at ?: "",
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
            val result = communityRepository.forwardCommunityMessageToCommunities(communityMessage, targetCommunityIds.toList())
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
        _replyToMessage.value = null
    }

    fun onEditMessage(message: conectate.com.data.model.Message) {
        if (message.content_type != "text" || message.deleted_for_all) return
        _editingMessage.value = message
        _messageText.value = message.content
        _selectedMessage.value = null
    }

    fun cancelEdit() {
        _editingMessage.value = null
        _messageText.value = ""
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
            val result = communityRepository.editCommunityMessage(msg.id, newContent)
            if (result.isSuccess) {
                cancelEdit()
            } else {
                _errorMessage.value = "Error al editar"
            }
            _isSending.value = false
        }
    }

    fun deleteMessageForEveryone(message: conectate.com.data.model.Message) {
        viewModelScope.launch {
            communityRepository.deleteCommunityMessage(message.id, true)
        }
    }

    fun deleteMessageForMe(message: conectate.com.data.model.Message) {
        viewModelScope.launch {
            communityRepository.deleteCommunityMessage(message.id, false)
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
        if (_selectedChannel.value?.isDefault == false) {
            _errorMessage.value = "Por ahora las encuestas se crean en el canal General."
            return
        }
        if (writableChannelOrNull() == null && _selectedChannel.value != null) return
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
            val channel = writableChannelOrNull()
            if (_selectedChannel.value != null && channel == null) return
            viewModelScope.launch {
                _isSending.value = true
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val uploadResult = communityRepository.uploadCommunityAudio(communityId, bytes)
                if (uploadResult.isSuccess) {
                    val url = uploadResult.getOrThrow()
                    val durationLabel = formatDuration(durationMs)
                    val sendResult = communityRepository.sendCommunityMessage(
                        communityId = communityId,
                        content = durationLabel,
                        contentType = "audio",
                        mediaUrl = url,
                        channelId = channel?.id
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

    fun playAudio(message: conectate.com.data.model.Message) {
        val id = message.id
        val url = message.media_url ?: return

        voiceMessageManager.pausePreview()

        if (_currentPlayingAudioId.value == id) {
            mediaPlayer?.let {
                if (it.isPlaying) it.pause() else it.start()
                return
            }
        }

        stopAudio()

        viewModelScope.launch {
            try {
                mediaPlayer = android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    UbuntuMedia.setDataSource(this, url)
                    prepareAsync()
                    setOnPreparedListener {
                        _currentPlayingAudioId.value = id
                        start()
                        startProgressTracker()
                    }
                    setOnCompletionListener { stopAudio() }
                    setOnErrorListener { _, _, _ ->
                        stopAudio()
                        false
                    }
                }
            } catch (e: Exception) {
                stopAudio()
            }
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        _audioProgress.value = it.currentPosition.toFloat() / it.duration.toFloat()
                        _audioDurationLabel.value = formatDuration(it.currentPosition.toLong())
                    }
                }
                delay(100)
            }
        }
    }

    private fun stopAudio() {
        progressJob?.cancel()
        _currentPlayingAudioId.value = null
        _audioProgress.value = 0f
        _audioDurationLabel.value = "0:00"
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        pollRetryJobs.values.forEach { it.cancel() }
        pollRetryJobs.clear()
        stopAudio()
        voiceMessageManager.reset()
        communityRepository.setActiveCommunity(null) // Called synchronously to avoid scope cancellation
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun joinCommunity() {
        viewModelScope.launch {
            communityRepository.joinCommunity(communityId)
            loadCommunity()
        }
    }

    fun leaveCommunity(onSuccess: () -> Unit) {
        val cleanId = communityId.trim().lowercase()
        if (cleanId.isBlank()) return
        viewModelScope.launch {
            val result = communityRepository.leaveCommunity(cleanId)
            if (result.isSuccess) {
                communityRepository.syncCommunities()
                onSuccess()
            } else {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "No se pudo salir de la comunidad."
            }
        }
    }

    fun deleteCommunityLocally(onSuccess: () -> Unit) {
        viewModelScope.launch {
            communityRepository.deleteCommunity(communityId) // Already has creator check for global vs local
            onSuccess()
        }
    }

    fun getCurrentUserId(): String = authService.getCurrentUserId() ?: ""
    fun getCurrentUserEmail(): String? = authService.getCurrentUserEmail()
    
    fun voiceMessageManagerIsPreviewPlaying(): Boolean {
        return voiceMessageManager.isPreviewPlaying()
    }

    suspend fun getPlayableUrl(path: String): String? = messageRepository.getPlayableUrl(path).getOrNull()

    suspend fun consumeViewOnceMessage(message: conectate.com.data.model.Message): String? {
        val mediaPath = message.media_url ?: return null
        return messageRepository.consumeViewOnceMedia(
            scopeType = "community",
            messageId = message.id,
            mediaPath = mediaPath
        ).getOrNull()
    }
}

data class CommunityForwardTarget(
    val communityId: String,
    val title: String
)
