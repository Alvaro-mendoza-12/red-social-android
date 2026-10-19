package conectate.com.util

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID
import androidx.room.Room
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.local.entity.MessageEntity
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=UnitTestApplication::class)
class ConversationReadSyncTest {
    @Test fun readCutoffReconcilesRoomBadgeAndKeepsNewerIncomingUnread() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val account=UUID.randomUUID().toString();val room=UUID.randomUUID().toString()
            db.chatDao().insertChat(ChatEntity(room,null,false,account,"[]",null,null,null,unread_count=9))
            val old=MessageEntity(UUID.randomUUID().toString(),room,"other","Old","text",created_at="2026-10-18T11:59:00Z",status="delivered")
            val newer=old.copy(id=UUID.randomUUID().toString(),created_at="2026-10-18T12:00:01Z")
            db.messageDao().insertMessages(listOf(old,newer,old.copy(id=UUID.randomUUID().toString(),sender_id=account)))
            db.messageDao().markReadThrough(room,account,"2026-10-18T12:00:00Z")
            db.chatDao().reconcileUnreadCount(room,account)
            assertEquals("read",db.messageDao().getMessageById(old.id)?.status)
            assertEquals("delivered",db.messageDao().getMessageById(newer.id)?.status)
            assertEquals(1,db.chatDao().getChatById(room)?.unread_count)
        } finally {db.close()}
    }
    @Test fun serverCreationTimeSuppressesLatePushAfterFullRead() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val account=UUID.randomUUID().toString();val room=UUID.randomUUID().toString()
        ConversationReadStore(context).remember(ConversationReadEvent(account,"chat",room,emptySet(),"2026-10-18T12:00:00Z"))
        val payload=MessageNotificationPayload("read_sync","Mensajes","Local fixture",room,"chat","Conversación","Ana","Leído",774102,
            messageId=UUID.randomUUID().toString(),notificationAccountTarget=NotificationAccountTarget(userId=account),createdAt="2026-10-18T11:59:00Z")
        assertFalse(MessageNotificationFactory.post(context,payload))
        assertFalse(ConversationReadStore(context).isRead(account,"chat",room,"new","2026-10-18T12:00:01Z"))
    }
    @Test fun controlsValidateIdentityAndRetainNewerUnreadMessages() {
        val account=UUID.randomUUID().toString();val room=UUID.randomUUID().toString();val id=UUID.randomUUID().toString()
        val data=mapOf("type" to "conversation_read","recipient_id" to account,"conversation_id" to room,"target_type" to "chat","message_ids" to "[\"$id\"]","read_through" to "2026-10-18T12:00:00Z")
        val event=conversationReadEvent(data)!!
        assertTrue(event.includes(id,null))
        assertTrue(event.includes("old","2026-10-18T11:00:00Z"))
        assertFalse(event.includes("new","2026-10-18T12:00:01Z"))
        assertNull(conversationReadEvent(data+ ("target_type" to "voice")))
        assertNull(conversationReadEvent(data+ ("message_ids" to "[\"invalid\"]")))
        assertNull(conversationReadEvent(data+ ("recipient_id" to "invalid")))
    }
    @Test fun storedReadIdsAreIsolatedByAccountAndPersistAcrossInstances() {
        val context=ApplicationProvider.getApplicationContext<Context>();val account=UUID.randomUUID().toString();val room=UUID.randomUUID().toString()
        ConversationReadStore(context).remember(ConversationReadEvent(account,"group",room,setOf("read-id"),null))
        assertTrue(ConversationReadStore(context).isRead(account,"group",room,"read-id"))
        assertFalse(ConversationReadStore(context).isRead("other","group",room,"read-id"))
        assertFalse(ConversationReadStore(context).isRead(account,"chat",room,"read-id"))
    }
    @Test fun notificationRemovesOnlyReadMessageAndLateDuplicatesCannotReappear() {
        val context=ApplicationProvider.getApplicationContext<Context>();val manager=context.getSystemService(NotificationManager::class.java)
        val account=UUID.randomUUID().toString();val room=UUID.randomUUID().toString();val first=UUID.randomUUID().toString();val second=UUID.randomUUID().toString()
        val target=NotificationAccountTarget(userId=account)
        val payload=MessageNotificationPayload("read_sync","Mensajes","Local fixture",room,"chat","Conversación","Ana","Leído",774101,messageId=first,notificationAccountTarget=target,allowInlineActions=false)
        try {
            MessageNotificationFactory.post(context,payload)
            MessageNotificationFactory.post(context,payload.copy(messageId=second,messageBody="Todavía no leído"))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val event=ConversationReadEvent(account,"chat",room,setOf(first),null)
            ConversationReadStore(context).remember(event);MessageNotificationFactory.applyRemoteRead(context,event)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val notification=manager.activeNotifications.single { it.id==774101 }.notification
            val history=NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!.messages
            assertEquals(1,history.size);assertEquals("Todavía no leído",history[0].text.toString())
            assertFalse(MessageNotificationFactory.post(context,payload))
            MessageNotificationFactory.applyRemoteRead(context,ConversationReadEvent(account,"chat",room,setOf(second),null))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(manager.activeNotifications.any {it.id==774101})
        }finally{MessageNotificationFactory.cancelConversation(context,target,"chat",room)}
    }
}
