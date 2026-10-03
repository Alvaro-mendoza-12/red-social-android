package conectate.com.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.local.entity.UserEntity
import conectate.com.data.model.User
import conectate.com.data.model.profilePublicHandle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileCacheMigrationTest {
    @Test fun migrationFrom55PreservesRowsAndPendingDeletionThenPersistsTheCompleteProfile() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "qa_profile_migration_${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(
                object : SupportSQLiteOpenHelper.Callback(55) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        LatestSchemaNormalizer.schemaPlansForVerification().forEach { table ->
                            db.execSQL(table.createSql.replace(", `profile_json` TEXT", ""))
                            table.indexSql.forEach(db::execSQL)
                        }
                        db.execSQL("INSERT INTO users (id, username, full_name) VALUES ('qa-old', 'old.email', 'Nombre QA')")
                        db.execSQL("INSERT INTO message_deletions VALUES ('qa-message','qa-old',0,'qa-chat','qa-revision',1,'pending',NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Only creates v55")
                },
            ).build(),
        )
        helper.writableDatabase
        helper.close()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.ALL).build()
        try {
            val old = requireNotNull(database.userDao().getUser("qa-old"))
            assertNull(old.profile_json)
            assertEquals("nombre_qa", profilePublicHandle(old.toDomain()))
            database.openHelper.readableDatabase.query("SELECT state FROM message_deletions WHERE message_id='qa-message'").use {
                assertTrue(it.moveToFirst()); assertEquals("pending", it.getString(0))
            }
            val profile = User(id = "qa-chosen", username = "qa_permanente", username_origin = "chosen",
                display_name = "Nombre Visible QA", favorite_music_url = "https://example.invalid/qa.mp3",
                profile_view_history_enabled = false)
            database.userDao().insertUser(UserEntity.fromDomain(profile))
            val cached = requireNotNull(database.userDao().getUser(profile.id)).toDomain()
            assertEquals("qa_permanente", profilePublicHandle(cached))
            assertEquals(profile.displayName, cached.displayName)
            assertEquals(profile.favorite_music_url, cached.favorite_music_url)
            assertFalse(cached.profile_view_history_enabled)
        } finally {
            database.close(); context.deleteDatabase(name)
        }
    }
}
