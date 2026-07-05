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
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ConversationsUiState {
    object Loading : ConversationsUiState()
    data class Success(val conversations: List<ChatWithLastMessage>) : ConversationsUiState()
    data class Error(val message: String) : ConversationsUiState()
}

enum class ChatListFilter {
    ALL,
    FAVORITES,
    ARCHIVED
}

private data class ConversationFilterInput(
    val list: List<ChatWithLastMessage>,
    val query: String,
    val selectedFolderId: String?,
    val selectedListFilter: ChatListFilter
)

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

    private val _selectedListFilter = MutableStateFlow(ChatListFilter.ALL)
    val selectedListFilter: StateFlow<ChatListFilter> = _selectedListFilter.asStateFlow()

    // Oculta inmediatamente un chat deslizado mientras Room y la sincronizacion
    // remota terminan la eliminacion. Evita dejar una franja roja en la lista.
    private val _hiddenChatIds = MutableStateFlow<Set<String>>(emptySet())

    val chatFolders: StateFlow<List<ChatFolder>> = advancedMessagingRepository.folders
    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    fun onTabSelected(index: Int) {
        _selectedTabIndex.value = index
    }

    fun selectFolder(folderId: String?) {
        _selectedFolderId.value = folderId?.trim()?.ifBlank { null }
        if (!folderId.isNullOrBlank()) {
            _selectedListFilter.value = ChatListFilter.ALL
        }
    }

    fun selectListFilter(filter: ChatListFilter) {
        _selectedListFilter.value = filter
        // Los chips "Todas/Favoritos/Archivados" son filtros globales.
        // Si una carpeta queda seleccionada, el usuario toca "Todas" y parece
        // que no pasa nada porque la carpeta sigue filtrando por debajo.
        _selectedFolderId.value = null
    }

    // FILTERED UI STATE
    private val conversationFilterInput = combine(
        _conversations,
        _searchQuery,
        _selectedFolderId,
        _selectedListFilter
    ) { list: List<ChatWithLastMessage>, query: String, selectedFolderId: String?, selectedListFilter: ChatListFilter ->
        ConversationFilterInput(
            list = list,
            query = query,
            selectedFolderId = selectedFolderId,
            selectedListFilter = selectedListFilter
        )
    }

    val conversationsState: StateFlow<ConversationsUiState> = combine(
        conversationFilterInput,
        chatFolders,
        _hiddenChatIds
    ) { input, folders, hiddenChatIds ->
        val list = input.list
        val query = input.query
        val selectedFolderId = input.selectedFolderId
        val selectedListFilter = input.selectedListFilter
        if (list.isEmpty()) {
            // We can't distinguish between "Loading from DB" and "Empty DB" easily here,
            // but since Room emits [] immediately if empty, this is correct for "Empty".
            ConversationsUiState.Success(emptyList())
        } else {
            val distinctList = normalizedVisibleConversations(list, hiddenChatIds)

            val folderChatIds = folders.firstOrNull { it.id == selectedFolderId }?.chat_ids?.toSet().orEmpty()
            val byFilter = when (selectedListFilter) {
                ChatListFilter.ALL -> distinctList.filter { !it.chat.is_archived }
                ChatListFilter.FAVORITES -> distinctList.filter { it.chat.is_favorite && !it.chat.is_archived }
                ChatListFilter.ARCHIVED -> distinctList.filter { it.chat.is_archived }
            }
            val byFolder = if (selectedFolderId.isNullOrBlank()) {
                byFilter
            } else {
                byFilter.filter { folderChatIds.contains(it.chat.id.trim().lowercase()) }
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
    
    val unreadDmsCount: StateFlow<Int> = combine(messageRepository.getUserChatsFlow(), _hiddenChatIds) { chats, hiddenChatIds ->
        normalizedVisibleConversations(chats, hiddenChatIds)
            .count { it.unreadCount > 0 && !it.chat.is_group && !it.chat.is_archived }
    }
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
        viewModelScope.launch {
            while (true) {
                delay(12_000)
                refreshNow()
            }
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

    fun favoriteChat(chatId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            val result = messageRepository.favoriteChat(chatId, isFavorite)
            _uiMessage.value = if (result.isSuccess) {
                if (isFavorite) "Chat añadido a favoritos." else "Chat quitado de favoritos."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar favorito."
            }
        }
    }

    fun archiveChat(chatId: String, isArchived: Boolean) {
        viewModelScope.launch {
            val result = messageRepository.archiveChat(chatId, isArchived)
            _uiMessage.value = if (result.isSuccess) {
                if (isArchived) "Chat archivado." else "Chat desarchivado."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar archivado."
            }
        }
    }

    fun blockUser(userId: String) {
        viewModelScope.launch { userRepository.blockUser(userId) }
    }
    
    fun deleteChat(chatId: String) {
        val cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return
        _hiddenChatIds.update { it + cleanId }
        viewModelScope.launch {
            val result = messageRepository.deleteChat(cleanId)
            if (result.isFailure) {
                _hiddenChatIds.update { it - cleanId }
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
            messageRepository.submitReport(uid, name ?: "", reason, desc ?: "", uri)
        }
    }

    fun consumeUiMessage() {
        _uiMessage.value = null
    }
}
