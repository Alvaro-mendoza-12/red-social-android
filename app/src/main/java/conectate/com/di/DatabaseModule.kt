package conectate.com.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.dao.ChatSettingsDao
import conectate.com.data.local.dao.MessageDao
import conectate.com.data.local.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    /**
     * Database Migration from version 1 to 2
     * Adds performance indices to messages and chats tables
     * NO DATA IS MODIFIED - Only adds indices for query optimization
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                android.util.Log.d("DatabaseMigration", "Starting migration 1→2: Creating indices")
                
                // Messages table indices - Critical for chat performance
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chat_id_created_at ON messages(chat_id, created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sender_id ON messages(sender_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_status_is_synced ON messages(status, is_synced)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_pending_action ON messages(pending_action)")
                
                // Chats table indices - Critical for conversation list performance
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_last_message_at ON chats(last_message_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_is_group ON chats(is_group)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_unread_count ON chats(unread_count)")
                
                android.util.Log.d("DatabaseMigration", "✅ Migration 1→2 completed successfully - 7 indices created")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseMigration", "Error during migration 1→2", e)
                // DO NOT re-throw: Indices are optimization, not critical
                // App will still function, just slower queries
            }
        }
    }

    /**
     * Database Migration from version 19 to 20
     * Adds voice_access_granted column to users table
     */
    private val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                android.util.Log.d("DatabaseMigration", "Starting migration 19→20: Adding voice_access_granted")
                
                // Add voice_access_granted column to users table with default value false
                db.execSQL("ALTER TABLE users ADD COLUMN voice_access_granted INTEGER NOT NULL DEFAULT 0")
                
                android.util.Log.d("DatabaseMigration", "✅ Migration 19→20 completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseMigration", "Error during migration 19→20", e)
                throw e // Re-throw to trigger fallback
            }
        }
    }

    /**
     * Database Migration from version 25 to 26
     * Adds durable sync metadata to group_messages for offline retry queue.
     */
    private val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                android.util.Log.d("DatabaseMigration", "Starting migration 25→26: group_messages sync metadata")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN is_synced INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN pending_action TEXT")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN last_retry_at TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_messages_is_synced_pending_action ON group_messages(is_synced, pending_action)")
                android.util.Log.d("DatabaseMigration", "✅ Migration 25→26 completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseMigration", "Error during migration 25→26", e)
                throw e
            }
        }
    }

    /**
     * Database Migration from version 26 to 27
     * Adds durable sync metadata to community_messages for offline retry queue.
     */
    private val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                android.util.Log.d("DatabaseMigration", "Starting migration 26→27: community_messages sync metadata")
                db.execSQL("ALTER TABLE community_messages ADD COLUMN is_synced INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE community_messages ADD COLUMN pending_action TEXT")
                db.execSQL("ALTER TABLE community_messages ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE community_messages ADD COLUMN last_retry_at TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_community_messages_is_synced_pending_action ON community_messages(is_synced, pending_action)")
                android.util.Log.d("DatabaseMigration", "✅ Migration 26→27 completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseMigration", "Error during migration 26→27", e)
                throw e
            }
        }
    }

    /**
     * Database Migration from version 27 to 28
     * Adds mute/pin cache columns for direct chats.
     */
    private val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                android.util.Log.d("DatabaseMigration", "Starting migration 27→28: chat mute/pin cache")
                db.execSQL("ALTER TABLE chats ADD COLUMN is_muted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chats ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_is_pinned_last_message_at ON chats(is_pinned, last_message_at DESC)")
                android.util.Log.d("DatabaseMigration", "✅ Migration 27→28 completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseMigration", "Error during migration 27→28", e)
                throw e
            }
        }
    }

    /**
     * Database Migration from version 28 to 29
     * Adds poll_id cache columns for group/community messages.
     */
    private val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                android.util.Log.d("DatabaseMigration", "Starting migration 28→29: poll_id for group/community messages")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN poll_id TEXT")
                db.execSQL("ALTER TABLE community_messages ADD COLUMN poll_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_messages_poll_id ON group_messages(poll_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_community_messages_poll_id ON community_messages(poll_id)")
                android.util.Log.d("DatabaseMigration", "✅ Migration 28→29 completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseMigration", "Error during migration 28→29", e)
                throw e
            }
        }
    }

    /**
     * Database Migration from version 29 to 30
     * Adds DM reaction cache + forwarding metadata columns.
     */
    private val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                android.util.Log.d("DatabaseMigration", "Starting migration 29→30: DM reactions + forwarding metadata")
                db.execSQL("ALTER TABLE messages ADD COLUMN reactions_json TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE messages ADD COLUMN is_forwarded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN forwarded_from_message_id TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN forwarded_from_user_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_is_forwarded ON messages(is_forwarded)")
                android.util.Log.d("DatabaseMigration", "✅ Migration 29→30 completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseMigration", "Error during migration 29→30", e)
                throw e
            }
        }
    }

    /**
     * Database Migration from version 30 to 31
     * Adds group/community reaction cache + forwarding metadata columns.
     */
    private val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                android.util.Log.d("DatabaseMigration", "Starting migration 30→31: group/community reactions + forwarding metadata")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN reactions_json TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN is_forwarded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN forwarded_from_message_id TEXT")
                db.execSQL("ALTER TABLE group_messages ADD COLUMN forwarded_from_user_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_group_messages_is_forwarded ON group_messages(is_forwarded)")

                db.execSQL("ALTER TABLE community_messages ADD COLUMN reactions_json TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE community_messages ADD COLUMN is_forwarded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE community_messages ADD COLUMN forwarded_from_message_id TEXT")
                db.execSQL("ALTER TABLE community_messages ADD COLUMN forwarded_from_user_id TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_community_messages_is_forwarded ON community_messages(is_forwarded)")

                android.util.Log.d("DatabaseMigration", "✅ Migration 30→31 completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseMigration", "Error during migration 30→31", e)
                throw e
            }
        }
    }

    /**
     * Database Migration from version 33 to 34
     * Adds per-chat background settings cache.
     */
    private val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(db: SupportSQLiteDatabase) {
            try {
                android.util.Log.d("DatabaseMigration", "Starting migration 33→34: chat_settings table")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_settings` (" +
                        "`chat_id` TEXT NOT NULL, " +
                        "`background_type` TEXT NOT NULL DEFAULT 'default', " +
                        "`background_uri` TEXT, " +
                        "`updated_at` TEXT NOT NULL, " +
                        "PRIMARY KEY(`chat_id`))"
                )
                android.util.Log.d("DatabaseMigration", "✅ Migration 33→34 completed successfully")
            } catch (e: Exception) {
                android.util.Log.e("DatabaseMigration", "Error during migration 33→34", e)
                throw e
            }
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "conectate_db"
        )
        .addMigrations(
            MIGRATION_1_2,
            MIGRATION_19_20,
            MIGRATION_25_26,
            MIGRATION_26_27,
            MIGRATION_27_28,
            MIGRATION_28_29,
            MIGRATION_29_30,
            MIGRATION_30_31,
            MIGRATION_33_34
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideUserDao(database: AppDatabase): UserDao {
        return database.userDao()
    }

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao {
        return database.chatDao()
    }

    @Provides
    fun provideChatSettingsDao(database: AppDatabase): ChatSettingsDao {
        return database.chatSettingsDao()
    }

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao {
        return database.messageDao()
    }

    @Provides
    fun provideVideoStatsDao(database: AppDatabase): conectate.com.data.local.dao.VideoStatsDao {
        return database.videoStatsDao()
    }

    @Provides
    fun provideVideoCommentDao(database: AppDatabase): conectate.com.data.local.dao.VideoCommentDao {
        return database.videoCommentDao()
    }

    @Provides
    fun provideGroupDao(database: AppDatabase): conectate.com.data.local.dao.GroupDao {
        return database.groupDao()
    }

    @Provides
    fun provideCommunityDao(database: AppDatabase): conectate.com.data.local.dao.CommunityDao {
        return database.communityDao()
    }
}
