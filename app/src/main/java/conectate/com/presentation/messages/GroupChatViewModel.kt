package conectate.com.presentation.messages

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
import kotlinx.coroutines.launch
import javax.inject.Inject
import conectate.com.data.repository.MessageRepository
import java.io.File
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import conectate.com.util.ChatContactPayload
import conectate.com.util.UbuntuMedia
import conectate.com.util.encodeContactPayload

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
    private val groupRepository: GroupRepository,
    private val userRepository: UserRepository,
    private val groupDao: GroupDao,
    private val authService: AuthService,
    private val voiceMessageManager: VoiceMessageManager,
    private val messageRepository: MessageRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<GroupChatUiState>(GroupChatUiState.Loading)
    val uiState: StateFlow<GroupChatUiState> = _uiState.asStateFlow()

    private val _messageText = MutableStateFlow("")
    val messageText: StateFlow<String> = _messageText.asStateFlow()

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
    private val _pollOptionVoters = MutableStateFlow<GroupPollOptionVotersUiState?>(null)
    val pollOptionVoters: StateFlow<GroupPollOptionVotersUiState?> = _pollOptionVoters.asStateFlow()

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

    private val _myRole = MutableStateFlow<String?>(null)
    val myRole: StateFlow<String?> = _myRole.asStateFlow()

    private val _isDeleted = MutableStateFlow(false)
    val isDeleted: StateFlow<Boolean> = _isDeleted.asStateFlow()

    private val _isMember = MutableStateFlow(true)
    val isMember: StateFlow<Boolean> = _isMember.asStateFlow()

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
    private val _activeGroupId = MutableStateFlow<String?>(null)

    private var mediaPlayer: android.media.MediaPlayer? = null
    private var progressJob: kotlinx.coroutines.Job? = null

    private var groupId: String = ""
    private val _visibleMessageIds = MutableStateFlow<Set<String>>(emptySet())
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

    fun init(groupId: String) {
        val cleanId = groupId.trim().lowercase()
        if (cleanId.isBlank()) return
        this.groupId = cleanId
        _activeGroupId.value = cleanId
        // Voice Manager
        voiceMessageManager.setScope(viewModelScope)
        voiceMessageManager.reset()
        
        loadGroup()
        viewModelScope.launch {
            groupRepository.setActiveGroup(cleanId)
            groupRepository.markMessagesAsDelivered(cleanId)
            groupRepository.markAsRead(cleanId)
        }
    }

    fun onScreenVisibilityChanged(isVisible: Boolean) {
        _isScreenActive.value = isVisible
        val cleanId = groupId.trim().lowercase()
        if (cleanId.isBlank()) return
        if (isVisible) {
            viewModelScope.launch {
                groupRepository.setActiveGroup(cleanId)
                groupRepository.markMessagesAsDelivered(cleanId)
                groupRepository.markAsRead(cleanId)
            }
        } else {
            groupRepository.setActiveGroup(null)
        }
    }

    fun onVisibleMessageIdsChanged(visibleMessageIds: Set<String>) {
        _visibleMessageIds.value = visibleMessageIds
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
            groupRepository.markAsRead(groupId, readableVisibleIds)
        }
    }

    private fun loadGroup() {
        viewModelScope.launch {
            // Observe group status for deletion
            launch {
                groupRepository.getGroupFlow(groupId).collect { group ->
                    if (group == null || group.is_deleted) {
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
            val group = groupRepository.getGroupById(groupId) ?: run {
                _isDeleted.value = true
                return@launch
            }

            // 2. Background Sync
            launch {
                 try {
                     groupRepository.syncGroups()
                     groupRepository.getGroupMembers(groupId)
                 } catch (e: Exception) {
                     android.util.Log.e("GroupChatVM", "Background sync failed", e)
                 }
            }
            
            val members = groupRepository.getGroupMembers(groupId).getOrNull() ?: emptyList()
            
            // Fetch User profiles for members
            val userIds = members.map { it.user_id }
            val memberIds = userIds
            val memberProfiles = userRepository.getUsersByIds(userIds).getOrNull()?.associateBy { it.id } ?: emptyMap()
            
            _myRole.value = members.find { it.user_id == userId }?.role
            
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
                val currentGroup = groupRepository.getGroupById(groupId) ?: group
                refreshPollStates(messages)
                _uiState.update {
                    GroupChatUiState.Success(
                        group = currentGroup,
                        messages = messages,
                        memberProfiles = memberProfiles,
                        memberIds = memberIds
                    )
                }
                maybeMarkAsRead(groupId.trim().lowercase(), messages, _visibleMessageIds.value)
            }
        }
    }

    fun updateMessageText(text: String) {
        _messageText.value = text
    }

    fun sendMessage() {
        val content = _messageText.value
        if (content.isBlank() || _isSending.value) return

        val state = _uiState.value as? GroupChatUiState.Success
        val onlyAdmins = state?.group?.only_admins_can_talk ?: false
        val isAdmin = _myRole.value == "admin" || _myRole.value == "owner" || (state?.group?.creator_id == getCurrentUserId())
        
        if (onlyAdmins && !isAdmin) {
             _errorMessage.value = "Solo los administradores pueden escribir en este grupo."
             return
        }

        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            
            val replyTo = _replyToMessage.value
            _replyToMessage.value = null
            
            val result = groupRepository.sendGroupMessage(groupId, content, replyToId = replyTo?.id)
            if (result.isSuccess) {
                _messageText.value = ""
            } else {
                val error = result.exceptionOrNull()?.message ?: "Error desconocido"
                _errorMessage.value = "Error al enviar: $error"
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
        val normalizedCustomContent = customContent?.trim()?.ifBlank { null }
        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes != null) {
                    val uploadResult = groupRepository.uploadGroupMedia(groupId, bytes, mimeType)
                    if (uploadResult.isSuccess) {
                        val url = uploadResult.getOrThrow()
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
                        val sendResult = groupRepository.sendGroupMessage(
                            groupId = groupId,
                            content = content,
                            contentType = resolvedType,
                            mediaUrl = url
                        )
                        if (sendResult.isFailure) {
                            val reason = sendResult.exceptionOrNull()?.message ?: "Error desconocido"
                            _errorMessage.value = "Error al enviar: $reason"
                        }
                    } else {
                        val reason = uploadResult.exceptionOrNull()?.message
                        _errorMessage.value = if (reason.isNullOrBlank()) {
                            "Error al subir archivo"
                        } else {
                            "Error al subir archivo: $reason"
                        }
                    }
                } else {
                    _errorMessage.value = "No se pudo leer el archivo"
                }
            } catch (e: Exception) {
                _errorMessage.value = "Error: ${e.message}"
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

    fun sendStickerFromUrl(mediaUrl: String, caption: String = "🧩 Sticker") {
        val cleanUrl = mediaUrl.trim()
        if (cleanUrl.isBlank()) {
            _errorMessage.value = "Sticker inválido"
            return
        }
        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            val result = groupRepository.sendGroupMessage(
                groupId = groupId,
                content = caption,
                contentType = "sticker",
                mediaUrl = cleanUrl
            )
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message ?: "Error desconocido"
                _errorMessage.value = "Error al enviar: $reason"
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

        viewModelScope.launch {
            _isSending.value = true
            _errorMessage.value = null
            val result = groupRepository.sendGroupMessage(
                groupId = groupId,
                content = content.trim(),
                contentType = contentType,
                mediaUrl = mediaUrl
            )
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message ?: "Error desconocido"
                _errorMessage.value = "Error al enviar: $reason"
            }
            _isSending.value = false
        }
    }

    private fun refreshPollStates(messages: List<GroupMessage>) {
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
                    val fetched = groupRepository.getPollState(pollId).getOrNull().orEmpty()
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
            val result = groupRepository.sendGroupPollMessage(
                groupId = groupId,
                question = cleanQuestion,
                options = cleanOptions,
                allowsMultiple = allowsMultiple
            )
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.message ?: "No se pudo crear la encuesta"
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
            val result = groupRepository.votePollOption(pollId, optionId, selected)
            if (result.isSuccess) {
                _pollStates.update { it + (pollId to result.getOrThrow()) }
            } else {
                if (previous != null) {
                    _pollStates.update { it + (pollId to previous) }
                }
                _errorMessage.value = result.exceptionOrNull()?.message ?: "No se pudo registrar tu voto"
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
            val result = groupRepository.toggleGroupMessageReaction(message.id, emoji)
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
            val result = groupRepository.editGroupMessage(msg.id, newContent)
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
            groupRepository.deleteGroupMessage(message.id, true)
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
            val result = voiceMessageManager.stopAndGetFile()
            val file = result.first
            val duration = result.second
            
            if (file != null) {
                sendVoiceMessage(file, duration)
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
            val bytes = file.readBytes()
            viewModelScope.launch {
                _isSending.value = true
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
        groupRepository.setActiveGroup(null) // Called synchronously to ensure execution
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun deleteGroupLocally(onSuccess: () -> Unit) {
        viewModelScope.launch {
            groupRepository.deleteGroup(groupId) // This already deletes locally in Repo
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
