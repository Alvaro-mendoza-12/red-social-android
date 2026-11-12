package conectate.com.data.repository.message

import android.content.Context
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

@Singleton
class ConversationReadReceiver @Inject constructor(@param:ApplicationContext private val context: Context, private val auth: AuthService,
    private val database: AppDatabase, private val messages: MessageDao, private val chats: ChatDao,
    private val groups: GroupDao, private val communities: CommunityDao) {
    private val delivery = ConversationReadDelivery(
        remember = { ConversationReadStore(context).remember(it) },
        clearAlerts = { MessageNotificationFactory.applyRemoteRead(context, it) },
        local = ConversationReadLocalStore(database, messages, chats, groups, communities) { owner, write ->
            auth.withAccountLocalStorage(owner, write)
        },
    )

    suspend fun apply(event: ConversationReadEvent) {
        // Capture before waiting for another receipt; returning to the same UID is another visit.
        delivery.apply(event, auth.captureAccountLease())
    }

    internal suspend fun apply(event: ConversationReadEvent, owner: conectate.com.data.supabase.AuthSessionLease) {
        owner.requireCurrent()
        delivery.apply(event, owner)
    }
}
