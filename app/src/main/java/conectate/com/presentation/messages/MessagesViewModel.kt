package conectate.com.presentation.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import conectate.com.data.model.Conversation
import conectate.com.data.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _archivedChats = MutableStateFlow<List<Conversation>>(emptyList())
    val archivedChats: StateFlow<List<Conversation>> = _archivedChats.asStateFlow()

    private val _favoriteChats = MutableStateFlow<List<Conversation>>(emptyList())
    val favoriteChats: StateFlow<List<Conversation>> = _favoriteChats.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadConversations()
    }

    private fun loadConversations() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                conversationRepository.getConversations()
                    .collect { conversations ->
                        val filtered = conversations.filter { !it.isArchived }
                        val archived = conversations.filter { it.isArchived }
                        val favorites = conversations.filter { !it.isArchived && it.isFavorite }
                        
                        _conversations.value = filtered
                        _archivedChats.value = archived
                        _favoriteChats.value = favorites
                    }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        // Filter conversations based on query
        viewModelScope.launch {
            if (query.isBlank()) {
                loadConversations()
            } else {
                val filtered = _conversations.value.filter {
                    it.name?.contains(query, ignoreCase = true) == true ||
                    (it.lastMessage?.contains(query, ignoreCase = true) ?: false)
                }
                _conversations.value = filtered
            }
        }
    }

    fun archiveConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.archiveConversation(conversationId)
        }
    }

    fun unarchiveConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.unarchiveConversation(conversationId)
        }
    }

    fun favoriteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.favoriteConversation(conversationId)
        }
    }

    fun unfavoriteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.unfavoriteConversation(conversationId)
        }
    }

    fun pinConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.pinConversation(conversationId)
        }
    }

    fun unpinConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.unpinConversation(conversationId)
        }
    }

    fun muteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.muteConversation(conversationId)
        }
    }

    fun unmuteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.unmuteConversation(conversationId)
        }
    }

    fun markAsRead(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.markAsRead(conversationId)
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(conversationId)
        }
    }

    fun refreshConversations() {
        loadConversations()
    }
}
