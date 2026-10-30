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
import conectate.com.data.repository.message.InboxSyncPolicy
import conectate.com.data.repository.message.inboxNeedsInitialLoading
import conectate.com.data.repository.message.InboxSyncState
import conectate.com.data.repository.LocalInboxRepository
import conectate.com.data.repository.forAccount
import conectate.com.core.concurrency.deferredRepositoryFlow
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ConversationsUiState {
    object Loading : ConversationsUiState()
    data class Success(
        val conversations: List<ChatWithLastMessage>,
        val isRefreshing: Boolean = false,
        val isInitialSyncPending: Boolean = false,
    ) : ConversationsUiState()
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
    val selectedListFilter: ChatListFilter,
    val hasLocalSnapshot: Boolean,
)

@HiltViewModel
class MessagingViewModel @Inject constructor(
    private val messageRepositoryProvider: Lazy<MessageRepository>,
    private val advancedMessagingRepositoryProvider: Lazy<AdvancedMessagingRepository>,
    private val groupRepositoryProvider: Lazy<GroupRepository>,
    private val communityRepositoryProvider: Lazy<CommunityRepository>,
    private val userRepositoryProvider: Lazy<UserRepository>,
    private val localInboxRepository: LocalInboxRepository,
    private val authService: AuthService
) : ViewModel() {

    // RAW SOURCE
    private val messageRepository get() = messageRepositoryProvider.get()
    private val advancedMessagingRepository get() = advancedMessagingRepositoryProvider.get()
    private val groupRepository get() = groupRepositoryProvider.get()
    private val communityRepository get() = communityRepositoryProvider.get()
    private val userRepository get() = userRepositoryProvider.get()
    private val _snapshots = combine(localInboxRepository.snapshots, authService.currentSession) { snapshot, session ->
        snapshot.forAccount(session?.user?.id)
    }
    private val _conversations = _snapshots.map { it.conversations }
    
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

    val chatFolders: StateFlow<List<ChatFolder>> = deferredRepositoryFlow(advancedMessagingRepositoryProvider) { it.folders }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), emptyList())
    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    init {
        viewModelScope.launch {
            authService.currentSession.map { it?.user?.id?.trim()?.lowercase() }.distinctUntilChanged().collect {
                _selectedFolderId.value = null
                _searchQuery.value = ""
                _selectedListFilter.value = ChatListFilter.ALL
                _hiddenChatIds.value = emptySet()
                _uiMessage.value = null
            }
        }
    }

    fun onTabSelected(index: Int) {
        _selectedTabIndex.value = index
        if (isScreenActive) startRefresh(forceInbox = false, includeCollections = false)
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
        _snapshots,
        _searchQuery,
        _selectedFolderId,
        _selectedListFilter
    ) { snapshot, query, selectedFolderId, selectedListFilter ->
        ConversationFilterInput(
            list = snapshot.conversations.takeIf { snapshot.accountId == authService.getCurrentUserId()?.trim()?.lowercase() }.orEmpty(),
            query = query,
            selectedFolderId = selectedFolderId,
            selectedListFilter = selectedListFilter,
            hasLocalSnapshot = snapshot.accountId != null && snapshot.accountId == authService.getCurrentUserId()?.trim()?.lowercase(),
        )
    }

    val conversationsState: StateFlow<ConversationsUiState> = combine(
        conversationFilterInput,
        chatFolders,
        _hiddenChatIds,
        deferredRepositoryFlow(messageRepositoryProvider) { it.inboxSyncState }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), InboxSyncState()),
    ) { input, folders, hiddenChatIds, syncState ->
        val list = input.list
        val query = input.query
        val selectedFolderId = input.selectedFolderId
        val selectedListFilter = input.selectedListFilter
        if (list.isEmpty()) {
            when {
                !input.hasLocalSnapshot && inboxNeedsInitialLoading(syncState) ->
                    ConversationsUiState.Loading
                !syncState.isRefreshing && !syncState.lastError.isNullOrBlank() ->
                    ConversationsUiState.Error(syncState.lastError)
                else -> ConversationsUiState.Success(emptyList(), syncState.isRefreshing, !syncState.hasCompletedInitialSync)
            }
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
            ConversationsUiState.Success(filtered, syncState.isRefreshing, !syncState.hasCompletedInitialSync)
        }
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        // Preserve the rendered inbox for this ViewModel's lifetime, including a
        // return from a long conversation. _snapshots remains bound to the session.
        started = SharingStarted.Lazily,
        initialValue = ConversationsUiState.Loading
    )

    // --- UNREAD COUNTS (FOR BADGES) ---
    
    val unreadDmsCount: StateFlow<Int> = combine(_conversations, _hiddenChatIds) { chats, hiddenChatIds ->
        normalizedVisibleConversations(chats, hiddenChatIds)
            .count { it.unreadCount > 0 && !it.chat.is_group && !it.chat.is_archived }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val unreadGroupsCount: StateFlow<Int> = deferredRepositoryFlow(groupRepositoryProvider) { it.getGroupsFlow() }
        .map { groups -> groups.count { it.unreadCount > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val unreadCommunitiesCount: StateFlow<Int> = deferredRepositoryFlow(communityRepositoryProvider) { it.getCommunitiesFlow() }
        .map { communities -> communities.count { it.unreadCount > 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val currentUserId: String? get() = authService.getCurrentUserId()
    private var refreshJob: Job? = null
    private var periodicRefreshJob: Job? = null
    private var isScreenActive = false

    fun onScreenVisibilityChanged(visible: Boolean) {
        if (isScreenActive == visible) return
        isScreenActive = visible
        if (!visible) {
            periodicRefreshJob?.cancel()
            refreshJob?.cancel()
            refreshJob = null
            return
        }
        startRefresh(forceInbox = false, includeCollections = true)
        periodicRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(InboxSyncPolicy.PeriodicRefreshMillis)
                startRefresh(forceInbox = false, includeCollections = false)
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
        startRefresh(forceInbox = true, includeCollections = false)
    }

    private fun startRefresh(forceInbox: Boolean, includeCollections: Boolean) {
        if (!isScreenActive) return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                messageRepository.getUserChats(force = forceInbox)
                advancedMessagingRepository.refreshFolders()
                if (includeCollections || _selectedTabIndex.value == 1) {
                    groupRepository.syncGroups()
                }
                if (includeCollections || _selectedTabIndex.value == 2) {
                    communityRepository.syncCommunities()
                }
            }
        }
    }

    fun muteChat(chatId: String, isMuted: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { messageRepository.muteChat(chatId, isMuted) }
            _uiMessage.value = if (result.isSuccess) {
                if (isMuted) "Chat silenciado." else "Notificaciones activadas."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar silencio."
            }
        }
    }

    fun pinChat(chatId: String, isPinned: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { messageRepository.pinChat(chatId, isPinned) }
            _uiMessage.value = if (result.isSuccess) {
                if (isPinned) "Chat fijado arriba." else "Chat desfijado."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar fijado."
            }
        }
    }

    fun favoriteChat(chatId: String, isFavorite: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { messageRepository.favoriteChat(chatId, isFavorite) }
            _uiMessage.value = if (result.isSuccess) {
                if (isFavorite) "Chat añadido a favoritos." else "Chat quitado de favoritos."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar favorito."
            }
        }
    }

    fun archiveChat(chatId: String, isArchived: Boolean) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { messageRepository.archiveChat(chatId, isArchived) }
            _uiMessage.value = if (result.isSuccess) {
                if (isArchived) "Chat archivado." else "Chat desarchivado."
            } else {
                result.exceptionOrNull()?.message ?: "No se pudo actualizar archivado."
            }
        }
    }

    fun blockUser(userId: String) {
        viewModelScope.launch { withContext(Dispatchers.IO) { userRepository.blockUser(userId) } }
    }
    
    fun deleteChat(chatId: String) {
        val cleanId = chatId.trim().lowercase()
        if (cleanId.isBlank()) return
        _hiddenChatIds.update { it + cleanId }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { messageRepository.deleteChat(cleanId) }
            if (result.isFailure) {
                _hiddenChatIds.update { it - cleanId }
                _uiMessage.value = result.exceptionOrNull()?.message ?: "No se pudo eliminar el chat."
            }
        }
    }

    fun createFolder(name: String, colorHex: String? = null) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { advancedMessagingRepository.createFolder(name, colorHex) }
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
            val result = withContext(Dispatchers.IO) { advancedMessagingRepository.addChatToFolder(folderId, chatId) }
            if (result.isFailure) {
                _uiMessage.value = result.exceptionOrNull()?.message ?: "No se pudo guardar en carpeta."
            }
        }
    }

    fun removeChatFromFolder(folderId: String, chatId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { advancedMessagingRepository.removeChatFromFolder(folderId, chatId) }
            if (result.isFailure) {
                _uiMessage.value = result.exceptionOrNull()?.message ?: "No se pudo quitar de carpeta."
            }
        }
    }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch {
            val cleanFolder = folderId.trim()
            val result = withContext(Dispatchers.IO) { advancedMessagingRepository.deleteFolder(cleanFolder) }
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
            withContext(Dispatchers.IO) { messageRepository.submitReport(uid, name ?: "", reason, desc ?: "", uri) }
        }
    }

    fun consumeUiMessage() {
        _uiMessage.value = null
    }
}
