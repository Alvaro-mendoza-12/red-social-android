package conectate.com.data.repository.message

import androidx.room.withTransaction
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.dao.CommunityDao
import conectate.com.data.local.dao.GroupDao
import conectate.com.data.local.dao.MessageDao
import conectate.com.data.supabase.AuthSessionLease
import conectate.com.util.ConversationReadEvent
import java.time.Instant
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The journal and native alerts are account-scoped even when that account is inactive. */
internal class ConversationReadDelivery(
    private val remember: (ConversationReadEvent) -> Unit,
    private val clearAlerts: (ConversationReadEvent) -> Unit,
    private val local: ConversationReadLocalStore,
) {
    private val mutex = Mutex()

    suspend fun apply(event: ConversationReadEvent, owner: AuthSessionLease) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        remember(event)
        clearAlerts(event)
        if (owner.accountId == event.accountId && owner.isCurrent()) local.apply(event, owner)
    }
}

/** A read control must not enter the shared Room cache while another account activates. */
internal class ConversationReadLocalStore(
    private val database: AppDatabase,
    private val messages: MessageDao = database.messageDao(),
    private val chats: ChatDao = database.chatDao(),
    private val groups: GroupDao = database.groupDao(),
    private val communities: CommunityDao = database.communityDao(),
    private val withStorage: suspend (AuthSessionLease, suspend () -> Unit) -> Unit,
) {
    suspend fun apply(event: ConversationReadEvent, owner: AuthSessionLease) {
        if (owner.accountId != event.accountId) return
        withStorage(owner) {
            database.withTransaction {
                owner.requireCurrent()
                val now = Instant.now().toString()
                when (event.kind) {
                    "chat" -> {
                        messages.markMessagesAsReadByIds(event.conversationId, event.accountId, now, event.messageIds.toList())
                        event.through?.let { messages.markReadThrough(event.conversationId, event.accountId, it) }
                        chats.reconcileUnreadCount(event.conversationId, event.accountId)
                    }
                    "group" -> {
                        groups.markMessagesAsReadByIds(event.conversationId, now, event.accountId, event.messageIds.toList())
                        event.through?.let { groups.markReadThrough(event.conversationId, event.accountId, it) }
                        groups.reconcileUnreadCount(event.conversationId, event.accountId)
                    }
                    "community" -> {
                        communities.markMessagesAsReadByIds(event.conversationId, now, event.accountId, event.messageIds.toList())
                        event.through?.let { communities.markReadThrough(event.conversationId, event.accountId, it) }
                        communities.reconcileUnreadCount(event.conversationId, event.accountId)
                    }
                }
                // Keep this check inside the transaction so a forced logout rolls back its SQL.
                owner.requireCurrent()
            }
        }
    }
}
