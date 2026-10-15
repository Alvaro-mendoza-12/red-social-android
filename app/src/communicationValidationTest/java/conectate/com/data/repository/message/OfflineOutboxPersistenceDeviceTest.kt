package conectate.com.data.repository.message

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class OfflineOutboxPersistenceDeviceTest {
    @Test fun textAndEveryMediaTypeSurviveDatabaseReopenAndRetryWithTheSameIds() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="audit-outbox-"+UUID.randomUUID()+".db"
        val folder=File(context.noBackupFilesDir,"audit-outbox-"+UUID.randomUUID()).apply { mkdirs() }
        var db:AppDatabase?=null
        try {
            db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
            val originals=listOf("text","audio","image","video","file").mapIndexed { index,type ->
                val media=if(type=="text")null else File(folder,type+".fixture").apply { writeBytes(byteArrayOf(1,2,3,4,index.toByte())) }
                MessageEntity(id="message-$index",chat_id="owned-chat",sender_id="audit-owner",content="Fixture $type",content_type=type,
                    media_url=media?.toURI()?.toString(),media_size=media?.length(),created_at="2026-10-11T22:00:0${index}Z",
                    status="sending",is_synced=false,pending_action="send")
            }
            originals.forEach { db!!.messageDao().insertMessage(it) }
            assertEquals(5,db!!.messageDao().getPendingSendMessages().size)
            db!!.close();db=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
            val reopened=db!!.messageDao().getPendingSendMessages();assertEquals(originals.toSet(),reopened.toSet())
            reopened.filter { it.media_url!=null }.forEach { assertTrue(File(java.net.URI(it.media_url)).isFile) }
            val sent=mutableListOf<String>();var online=false
            val runner=PendingMessageBatchRunner({db!!.messageDao().getPendingSendMessages()},
                {if(online)Result.success("synthetic-token")else Result.failure(java.io.IOException("offline"))},
                {row,_->sent.add(row.id);db!!.messageDao().insertMessage(row.copy(status="sent",is_synced=true,pending_action=null));PendingSendOutcome.SENT},
                {fail("Temporary network loss must not mark messages as failed")})
            assertTrue(runner.run("audit-owner",50,{true}).isFailure)
            assertEquals(5,db!!.messageDao().getPendingSendMessages().size);assertTrue(sent.isEmpty())
            online=true;assertEquals(5,runner.run("audit-owner",50,{true}).getOrThrow())
            assertEquals(originals.map { it.id }.toSet(),sent.toSet());assertEquals(5,sent.size)
            assertEquals(0,runner.run("audit-owner",50,{true}).getOrThrow());assertEquals(5,sent.size)
            assertTrue(db!!.messageDao().getPendingSendMessages().isEmpty())
        } finally {
            db?.close();check(name.startsWith("audit-outbox-"));context.deleteDatabase(name)
            folder.listFiles()?.forEach { check(it.parentFile==folder);it.delete() };folder.delete()
        }
    }
    @Test fun networkRetryDoesNotDispatchAnotherAccountsPendingMessages() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            for(owner in listOf("active","other"))db.messageDao().insertMessage(MessageEntity(id=owner,chat_id="owned",sender_id=owner,
                content="Fixture",content_type="text",created_at="2026-10-11T22:00:00Z",status="sending",is_synced=false,pending_action="send"))
            val sent=mutableListOf<String>()
            val runner=PendingMessageBatchRunner({db.messageDao().getPendingSendMessages()},{Result.success("synthetic")},
                {row,_->sent.add(row.id);db.messageDao().insertMessage(row.copy(status="sent",is_synced=true,pending_action=null));PendingSendOutcome.SENT},{fail("No terminal errors expected")})
            assertEquals(1,runner.run("active",50,{true}).getOrThrow());assertEquals(listOf("active"),sent)
            assertEquals(listOf("other"),db.messageDao().getPendingSendMessages().map { it.id })
        } finally { db.close() }
    }
}
