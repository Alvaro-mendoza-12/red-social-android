package conectate.com.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import conectate.com.test.UnitTestApplication
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnitTestApplication::class)
class MessageDeletionMigrationTest {
    @get:Rule val storage = TemporaryFolder()

    @Test fun `real v54 file migrates additively preserving history and quarantining unowned all delete`() = runBlocking {
        val path = File(storage.root, "v54.db").absolutePath
        val schema = listOf(File("schemas/conectate.com.data.local.AppDatabase/54.json"),
            File("app/schemas/conectate.com.data.local.AppDatabase/54.json")).first { it.isFile }
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database")
            .jsonObject.getValue("entities").jsonArray
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            entities.forEach { raw ->
                val entity = raw.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                    old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            old.execSQL("INSERT INTO chats (id, is_group, created_by, participantsJson, unread_count, is_deleted) VALUES ('chat', 0, 'a', '[\"a\",\"b\"]', 0, 0)")
            fun insert(id: String, all: Int, deletedBy: String, action: String) {
                old.execSQL("INSERT INTO messages (id, chat_id, sender_id, content, content_type, media_url, created_at, status, is_edited, deleted_for_all, deleted_by_json, reactions_json, is_forwarded, is_synced, pending_action) VALUES (?, 'chat', 'a', 'history', 'image', 'file:///saved/picture.webp', '2026-09-09', 'delivered', 0, ?, ?, '{}', 0, 0, ?)",
                    arrayOf<Any?>(id, all, deletedBy, action))
            }
            insert("for-me", 0, "[\"b\"]", "delete")
            insert("for-all", 1, "[]", "delete")
            insert("send", 0, "[]", "send")
            insert("malformed", 0, "invalid-json", "delete")
            old.version = 54
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, path)
            .addMigrations(*DatabaseMigrations.ALL).allowMainThreadQueries().build()
        try {
            assertEquals(4, db.messageDao().getMessagesSync("chat").size)
            assertEquals("history", db.messageDao().getMessageById("send")!!.content)
            assertEquals("file:///saved/picture.webp", db.messageDao().getMessageById("send")!!.media_url)
            assertEquals("send", db.messageDao().getMessageById("send")!!.pending_action)
            val queued = db.messageDeletionDao().pending("b", 10).single()
            assertEquals("for-me", queued.message_id)
            assertFalse(queued.for_everyone)
            assertTrue(db.messageDeletionDao().pending("a", 10).isEmpty())
            assertEquals("failed", db.messageDeletionDao().get("for-all", "a", true)!!.state)
            assertFalse(db.messageDao().getMessageById("for-all")!!.deleted_for_all)
            // Never discard an undecodable legacy intent or invent its actor.
            assertEquals("delete", db.messageDao().getMessageById("malformed")!!.pending_action)
            assertEquals(APP_DATABASE_VERSION, db.openHelper.writableDatabase.version)
        } finally { db.close() }
    }
}
