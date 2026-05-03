package conectate.com.data.repository

import androidx.compose.runtime.mutableStateListOf
import conectate.com.data.model.Conversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor() {
    private val _conversations = mutableStateListOf<Conversation>()

    fun getConversations(): Flow<List<Conversation>> = flow {
        emit(_conversations.toList())
    }

    fun createConversation() {}

    fun archiveConversation(conversationId: String) {}

    fun unarchiveConversation(conversationId: String) {}

    fun pinConversation(conversationId: String) {}

    fun unpinConversation(conversationId: String) {}

    fun muteConversation(conversationId: String) {}

    fun unmuteConversation(conversationId: String) {}

    fun markAsRead(conversationId: String) {}

    fun deleteConversation(conversationId: String) {}
}
