package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.ChatWithLastMessage
import conectate.com.data.model.ChatFolder
import conectate.com.data.repository.AdvancedMessagingRepository
import conectate.com.data.repository.MessageRepository
import conectate.com.data.repository.UserRepository
import conectate.com.data.supabase.AuthService
import conectate.com.data.repository.GroupRepository
import conectate.com.data.repository.CommunityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ConversationsUiState {
    object Loading : ConversationsUiState()
    data class Success(val conversations: List<ChatWithLastMessage>) : ConversationsUiState()
    data class Error(val message: String) : ConversationsUiState()
}

const val ARCHIVED_CHAT_FILTER_ID = "__archived_chats__"
const val FAVORITE_CHAT_FILTER_ID = "__favorite_chats__"

@HiltViewModel
class MessagingViewModel @Inject constructor(
    private val messageRepository: MessageRepository,
    private val advancedMessagingRepository: AdvancedMessagingRepository,
    private val groupRepository: GroupRepository,
    private val communityRepository: CommunityRepository,
    private val userRepository: UserRepository,
    private val authService: AuthService
) : ViewModel() {

    // RAW SOURCE
    private val _conversations = messageRepository.getUserChatsFlow()
    
    // UI SEARCH STATE
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTabIndex = MutableStateFlow(0)
    val selectedTabIndex: StateFlow<Int> = _selectedTabIndex.asStateFlow()

    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    val chatFolders: StateFlow<List<ChatFolder>> = advancedMessagingRepository.folders
    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    fun onTabSelected(index: Int) {
        _selectedTabIndex.value = index
    }

    fun selectFolder(folderId: String?) {
        _selectedFolderId.value = folderId?.trim()?.ifBlank { null }
    }

    // FILTERED UI STATE
    val conversationsState: StateFlow<ConversationsUiState> = combine(_conversations, _searchQuery, _selectedFolderId, chatFolders) { list, query, selectedFolderId, folders ->
        if (list.isEmpty()) {
            // We can't distinguish between "Loading from DB" and "Empty DB" easily here,
            // but since Room emits [] immediately if empty, this is correct for "Empty".
            ConversationsUiState.Success(emptyList())
        } else {
            val showingArchived = selectedFolderId == ARCHIVED_CHAT_FILTER_ID
            val showingFavorites = selectedFolderId == FAVORITE_CHAT_FILTER_ID
            // 2. LOGICAL DEDUPLICATION & GHOST CHAT FILTERING
            val validChats = list.filter {
                val hasContent = !it.chat.last_message.isNullOrBlank() || !it.lastMessage?.content.isNullOrBlank()
                val hasTimestamp = !it.chat.last_message_at.isNullOrBlank() || !it.lastMessage?.created_at.isNullOrBlank()
                hasContent || hasTimestamp
            }

            val (groups, dms) = validChats.partition { it.chat.is_group }
            
            // DMs: Deduplicate by otherUserId (Collapses multiple chats with same person)
            val uniqueDms = dms.groupBy { it.otherUserId ?: it.chat.id }
                .mapNotNull { (_, duplicates) ->
                    duplicates.maxWithOrNull(
                        compareBy<ChatWithLastMessage> { it.chat.is_pinned }
                            .thenBy { it.chat.is_favorite }
                            .thenBy { it.chat.safeLastMessageAt }
                    )
                }

            // Groups: Deduplicate by ID
            val uniqueGroups = groups.groupBy { it.chat.id }
                .mapNotNull { (_, duplicates) ->
                    duplicates.maxWithOrNull(
                        compareBy<ChatWithLastMessage> { it.chat.is_pinned }
                            .thenBy { it.chat.is_favorite }
                            .thenBy { it.chat.safeLastMessageAt }
                    )
                }

            // Combine and Final Sort
            val distinctList = (uniqueDms + uniqueGroups)
                .sortedWith(
                    compareBy<ChatWithLastMessage> { it.chat.is_archived }
                        .thenByDescending { it.chat.is_pinned }
                        .thenByDescending { it.chat.is_favorite }
                        .thenByDescending { it.chat.safeLastMessageAt }
                )

            val byArchive = when {
                showingArchived -> distinctList.filter { it.chat.is_archived }
                showingFavorites -> distinctList.filter { !it.chat.is_archived && it.chat.is_favorite }
                else -> distinctList.filter { !it.chat.is_archived }
            }
            val folderChatIds = folders.firstOrNull { it.id == selectedFolderId }?.chat_ids?.toSet().orEmpty()
            val byFolder = when {
                showingArchived -> byArchive
                showingFavorites -> byArchive
                selectedFolderId.isNullOrBlank() -> byArchive
                else -> byArchive.filter { folderChatIds.contains(it.chat.id.trim().lowercase()) }
            }

            // 3. SEARCH FILTER
            val filtered = if (query.isBlank()) {
                byFolder
            } else {
                byFolder.filter { item ->
                    val name = item.otherUserName ?: ""
                    val lastMsg = item.chat.last_message ?: ""
                    name.contains(query, ignoreCase = true) || lastMsg.contains(query, ignoreCase = true)
                }
            }
            ConversationsUiState.Success(filtered)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConversationsUiState.Loading
    )

    // --- UNREAD COUNTS (FOR BADGES) ---
    
    val unreadDmsCount: StateFlow<Int> = messageRepository.getUserChatsFlow()
        .map { chats -> chats.count { it.unreadCount > 0 && !it.chat.is_group && !it.chat.is_archived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val unreadGroupsCount: StateFlow<Int> = groupRepository.getGroupsFlow()
        .map { groups -> groups.count { it.unreadCount > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val unreadCommunitiesCount: StateFlow<Int> = communityRepository.getCommunitiesFlow()
        .map { communities -> communities.count { it.unreadCount > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val currentUserId: String? get() = authService.getCurrentUserId()

    init {
        // Trigger background sync
        viewModelScope.launch {
            messageRepository.getUserChats()
            advancedMessagingRepository.refreshFolders()
            groupRepository.syncGroups()
            communityRepository.syncCommunities()
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun getFilteredConversations(): List<ChatWithLastMessage> {
         return (conversationsState.value as? ConversationsUiState.Success)?.conversations ?: emptyList()
    }
    
    fun refreshNow() {
        viewModelScope.launch { 
            messageRepository.getUserChats()
            advancedMessagingRepository.refreshFolders()
            groupRepository.syncGroups()
            communityRepository.syncCommunities()
        }
    }

    fun muteChat(chatId: String, isMuted: Boolean) {
        viewModelScope.launch {
            val result = messageRepository.muteChat(chatId, isMuted)
            _uiMessage.value = if (result.isSuccess) {
                if (isMuted) "Chat silenciado." else "Notificaciones activadas."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar silencio."
            }
        }
    }

    fun pinChat(chatId: String, isPinned: Boolean) {
        viewModelScope.launch {
            val result = messageRepository.pinChat(chatId, isPinned)
            _uiMessage.value = if (result.isSuccess) {
                if (isPinned) "Chat fijado arriba." else "Chat desfijado."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar fijado."
            }
        }
    }

    fun archiveChat(chatId: String, isArchived: Boolean) {
        viewModelScope.launch {
            val result = messageRepository.archiveChat(chatId, isArchived)
            _uiMessage.value = if (result.isSuccess) {
                if (isArchived) "Chat archivado." else "Chat restaurado."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar archivo."
            }
            if (!isArchived && _selectedFolderId.value == ARCHIVED_CHAT_FILTER_ID) {
                _selectedFolderId.value = null
            }
        }
    }

    fun favoriteChat(chatId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            val result = messageRepository.favoriteChat(chatId, isFavorite)
            _uiMessage.value = if (result.isSuccess) {
                if (isFavorite) "Chat agregado a favoritos." else "Chat quitado de favoritos."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar favorito."
            }
            if (!isFavorite && _selectedFolderId.value == FAVORITE_CHAT_FILTER_ID) {
                _selectedFolderId.value = null
            }
        }
    }

    fun blockUser(userId: String) {
        viewModelScope.launch { userRepository.blockUser(userId) }
    }
    
    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            val result = messageRepository.deleteChat(chatId)
            if (result.isFailure) {
                _uiMessage.value = result.exceptionOrNull()?.message ?: "No se pudo eliminar el chat."
            }
        }
    }

    fun createFolder(name: String, colorHex: String? = null) {
        viewModelScope.launch {
            val result = advancedMessagingRepository.createFolder(name, colorHex)
            if (result.isSuccess) {
                val folder = result.getOrThrow()
                _selectedFolderId.value = folder.id
                _uiMessage.value = "Carpeta \"${folder.name}\" creada."
            } else {
                _uiMessage.value = result.exceptionOrNull()?.message ?: "No se pudo crear carpeta."
            }
        }
    }

    fun addChatToFolder(folderId: String, chatId: String) {
        viewModelScope.launch {
            val result = advancedMessagingRepository.addChatToFolder(folderId, chatId)
            if (result.isFailure) {
                _uiMessage.value = result.exceptionOrNull()?.message ?: "No se pudo guardar en carpeta."
            }
        }
    }

    fun removeChatFromFolder(folderId: String, chatId: String) {
        viewModelScope.launch {
            val result = advancedMessagingRepository.removeChatFromFolder(folderId, chatId)
            if (result.isFailure) {
                _uiMessage.value = result.exceptionOrNull()?.message ?: "No se pudo quitar de carpeta."
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            val cleanFolder = folderId.trim()
            val result = advancedMessagingRepository.deleteFolder(cleanFolder)
            if (result.isSuccess) {
                if (_selectedFolderId.value == cleanFolder) {
                    _selectedFolderId.value = null
                }
                _uiMessage.value = "Carpeta eliminada."
            } else {
                _uiMessage.value = result.exceptionOrNull()?.message ?: "No se pudo eliminar carpeta."
            }
        }
    }
    
    fun loadConversations() {
        refreshNow()
    }

    fun submitReport(uid: String, name: String?, reason: String, desc: String?, uri: android.net.Uri?) {
        viewModelScope.launch {
            if (uid.isBlank()) {
                _uiMessage.value = "No se pudo identificar el usuario a reportar."
                return@launch
            }
            val result = messageRepository.submitReport(uid, name ?: "", reason, desc ?: "", uri)
            _uiMessage.value = if (result.isSuccess) {
                "Reporte enviado."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo enviar reporte."
            }
        }
    }

    fun consumeUiMessage() {
        _uiMessage.value = null
    }
}
