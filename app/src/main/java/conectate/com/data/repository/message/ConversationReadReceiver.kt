package conectate.com.data.repository.message

import android.content.Context
import androidx.room.withTransaction
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.dao.MessageDao
import conectate.com.data.local.dao.GroupDao
import conectate.com.data.local.dao.CommunityDao
import conectate.com.data.supabase.AuthService
import conectate.com.util.ConversationReadEvent
import conectate.com.util.ConversationReadStore
import conectate.com.util.MessageNotificationFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ConversationReadReceiver @Inject constructor(@param:ApplicationContext private val context: Context, private val auth: AuthService,
    private val database: AppDatabase, private val messages: MessageDao, private val chats: ChatDao,
    private val groups: GroupDao, private val communities: CommunityDao) {
    private val mutex=Mutex()
    suspend fun apply(event: ConversationReadEvent) = mutex.withLock {
        ConversationReadStore(context).remember(event)
        MessageNotificationFactory.applyRemoteRead(context,event)
        if(auth.getCurrentUserId()?.lowercase()!=event.accountId)return@withLock
        database.withTransaction {
            check(auth.getCurrentUserId()?.lowercase()==event.accountId)
            val now=Instant.now().toString()
            when(event.kind){
                "chat" -> {
                    messages.markMessagesAsReadByIds(event.conversationId,event.accountId,now,event.messageIds.toList())
                    event.through?.let { messages.markReadThrough(event.conversationId,event.accountId,it) }
                    chats.reconcileUnreadCount(event.conversationId,event.accountId)
                }
                "group" -> {
                    groups.markMessagesAsReadByIds(event.conversationId,now,event.accountId,event.messageIds.toList())
                    event.through?.let { groups.markReadThrough(event.conversationId,event.accountId,it) }
                    groups.reconcileUnreadCount(event.conversationId,event.accountId)
                }
                "community" -> {
                    communities.markMessagesAsReadByIds(event.conversationId,now,event.accountId,event.messageIds.toList())
                    event.through?.let { communities.markReadThrough(event.conversationId,event.accountId,it) }
                    communities.reconcileUnreadCount(event.conversationId,event.accountId)
                }
            }
            check(auth.getCurrentUserId()?.lowercase()==event.accountId)
        }
    }
}
