package conectate.com.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class DatabaseMigrationsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun migrateFrom19ToLatest_preservesUsersChatsAndMessages() {
        val dbName = "migration_v19_${System.currentTimeMillis()}.db"
        createLegacyDatabase(
            name = dbName,
            version = 19,
            statements = listOf(
                "CREATE TABLE IF NOT EXISTS `users` (`id` TEXT NOT NULL, `username` TEXT NOT NULL, `full_name` TEXT NOT NULL, `avatar_url` TEXT, `bio` TEXT, `follower_count` INTEGER NOT NULL, `following_count` INTEGER NOT NULL, `is_verified` INTEGER NOT NULL, `is_admin` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `chats` (`id` TEXT NOT NULL, `name` TEXT, `is_group` INTEGER NOT NULL, `created_by` TEXT NOT NULL, `participantsJson` TEXT NOT NULL, `last_message_at` TEXT, `display_name` TEXT, `display_image` TEXT, `unread_count` INTEGER NOT NULL, `other_user_id` TEXT, `last_message_preview` TEXT, `last_message_timestamp` TEXT, `last_message_id` TEXT, `last_message_sender_id` TEXT, `is_deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `media_size` INTEGER, `created_at` TEXT NOT NULL, `reply_to_id` TEXT, `status` TEXT NOT NULL, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, `is_synced` INTEGER NOT NULL, `pending_action` TEXT, PRIMARY KEY(`id`))",
                "INSERT INTO `users` (`id`,`username`,`full_name`,`avatar_url`,`bio`,`follower_count`,`following_count`,`is_verified`,`is_admin`) VALUES ('u1','alice','Alice','https://cdn/avatar.png','bio',4,7,1,0)",
                "INSERT INTO `chats` (`id`,`name`,`is_group`,`created_by`,`participantsJson`,`last_message_at`,`display_name`,`display_image`,`unread_count`,`other_user_id`,`last_message_preview`,`last_message_timestamp`,`last_message_id`,`last_message_sender_id`,`is_deleted`) VALUES ('c1',NULL,0,'u1','[\"u1\",\"u2\"]','2026-01-01T10:00:00Z','Bob',NULL,3,'u2','Hola','2026-01-01T10:00:00Z','m1','u2',0)",
                "INSERT INTO `messages` (`id`,`chat_id`,`sender_id`,`content`,`content_type`,`media_url`,`media_size`,`created_at`,`reply_to_id`,`status`,`is_edited`,`deleted_for_all`,`deleted_by_json`,`is_synced`,`pending_action`) VALUES ('m1','c1','u2','Hola','text',NULL,NULL,'2026-01-01T10:00:00Z',NULL,'sent',0,0,'[]',1,NULL)"
            )
        )

        val db = openMigratedDatabase(dbName)
        try {
            val sqlite = db.openHelper.writableDatabase
            assertHasColumns(sqlite, "users", "voice_access_granted", "is_online", "last_seen", "updated_at")
            assertHasColumns(sqlite, "chats", "nickname", "cleared_at", "is_muted", "is_pinned")
            assertHasColumns(sqlite, "messages", "reply_to_json", "reactions_json", "is_forwarded", "poll_id")
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM users"))
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM messages"))
            assertEquals(0, singleInt(sqlite, "SELECT voice_access_granted FROM users WHERE id='u1'"))
            assertEquals(0, singleInt(sqlite, "SELECT is_pinned FROM chats WHERE id='c1'"))
            assertEquals("{}", singleString(sqlite, "SELECT reactions_json FROM messages WHERE id='m1'"))
        } finally {
            db.close()
        }

        context.deleteDatabase(dbName)
    }

    @Test
    fun migrateFrom25ToLatest_preservesGroupAndCommunityData() {
        val dbName = "migration_v25_${System.currentTimeMillis()}.db"
        createLegacyDatabase(
            name = dbName,
            version = 25,
            statements = listOf(
                "CREATE TABLE IF NOT EXISTS `groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `image` TEXT, `description` TEXT, `created_at` TEXT NOT NULL, `creator_id` TEXT NOT NULL, `last_message` TEXT, `last_message_at` TEXT, `unread_count` INTEGER NOT NULL, `is_deleted` INTEGER NOT NULL, `is_member` INTEGER NOT NULL, `only_admins_can_talk` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `group_members` (`group_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `role` TEXT NOT NULL, `joined_at` TEXT NOT NULL, PRIMARY KEY(`group_id`, `user_id`))",
                "CREATE TABLE IF NOT EXISTS `group_messages` (`id` TEXT NOT NULL, `group_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `created_at` TEXT NOT NULL, `status` TEXT NOT NULL, `delivered_at` TEXT, `read_at` TEXT, `sender_name` TEXT, `sender_image` TEXT, `reply_to_id` TEXT, `reply_to_json` TEXT, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `communities` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `image` TEXT, `description` TEXT, `category` TEXT, `is_public` INTEGER NOT NULL, `created_at` TEXT NOT NULL, `creator_id` TEXT NOT NULL, `last_message` TEXT, `last_message_at` TEXT, `unread_count` INTEGER NOT NULL, `is_deleted` INTEGER NOT NULL, `only_admins_can_talk` INTEGER NOT NULL, `is_member` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `community_members` (`community_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `role` TEXT NOT NULL, `joined_at` TEXT NOT NULL, PRIMARY KEY(`community_id`, `user_id`))",
                "CREATE TABLE IF NOT EXISTS `community_messages` (`id` TEXT NOT NULL, `community_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `created_at` TEXT NOT NULL, `status` TEXT NOT NULL, `delivered_at` TEXT, `read_at` TEXT, `sender_name` TEXT, `sender_image` TEXT, `reply_to_id` TEXT, `reply_to_json` TEXT, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, PRIMARY KEY(`id`))",
                "INSERT INTO `groups` (`id`,`name`,`image`,`description`,`created_at`,`creator_id`,`last_message`,`last_message_at`,`unread_count`,`is_deleted`,`is_member`,`only_admins_can_talk`) VALUES ('g1','Grupo',NULL,'desc','2026-01-02T00:00:00Z','u1','hola','2026-01-02T00:00:00Z',2,0,1,0)",
                "INSERT INTO `group_members` (`group_id`,`user_id`,`role`,`joined_at`) VALUES ('g1','u1','owner','2026-01-02T00:00:00Z')",
                "INSERT INTO `group_messages` (`id`,`group_id`,`sender_id`,`content`,`content_type`,`media_url`,`created_at`,`status`,`delivered_at`,`read_at`,`sender_name`,`sender_image`,`reply_to_id`,`reply_to_json`,`is_edited`,`deleted_for_all`,`deleted_by_json`) VALUES ('gm1','g1','u1','mensaje','text',NULL,'2026-01-02T00:01:00Z','sent',NULL,NULL,'Alice',NULL,NULL,NULL,0,0,'[]')",
                "INSERT INTO `communities` (`id`,`name`,`image`,`description`,`category`,`is_public`,`created_at`,`creator_id`,`last_message`,`last_message_at`,`unread_count`,`is_deleted`,`only_admins_can_talk`,`is_member`) VALUES ('co1','Comunidad',NULL,'desc','tech',1,'2026-01-03T00:00:00Z','u1','hola','2026-01-03T00:00:00Z',1,0,0,1)",
                "INSERT INTO `community_members` (`community_id`,`user_id`,`role`,`joined_at`) VALUES ('co1','u1','owner','2026-01-03T00:00:00Z')",
                "INSERT INTO `community_messages` (`id`,`community_id`,`sender_id`,`content`,`content_type`,`media_url`,`created_at`,`status`,`delivered_at`,`read_at`,`sender_name`,`sender_image`,`reply_to_id`,`reply_to_json`,`is_edited`,`deleted_for_all`,`deleted_by_json`) VALUES ('cm1','co1','u1','mensaje comunidad','text',NULL,'2026-01-03T00:01:00Z','sent',NULL,NULL,'Alice',NULL,NULL,NULL,0,0,'[]')"
            )
        )

        val db = openMigratedDatabase(dbName)
        try {
            val sqlite = db.openHelper.writableDatabase
            assertHasColumns(sqlite, "group_messages", "poll_id", "reactions_json", "is_synced", "retry_count")
            assertHasColumns(sqlite, "community_messages", "poll_id", "reactions_json", "is_synced", "retry_count")
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM group_messages"))
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM community_messages"))
            assertEquals(1, singleInt(sqlite, "SELECT is_synced FROM group_messages WHERE id='gm1'"))
            assertEquals("{}", singleString(sqlite, "SELECT reactions_json FROM community_messages WHERE id='cm1'"))
        } finally {
            db.close()
        }

        context.deleteDatabase(dbName)
    }

    @Test
    fun migrateFrom33ToLatest_createsChatSettingsWithoutLosingRows() {
        val dbName = "migration_v33_${System.currentTimeMillis()}.db"
        createLegacyDatabase(
            name = dbName,
            version = 33,
            statements = listOf(
                "CREATE TABLE IF NOT EXISTS `users` (`id` TEXT NOT NULL, `username` TEXT NOT NULL, `full_name` TEXT NOT NULL, `avatar_url` TEXT, `bio` TEXT, `follower_count` INTEGER NOT NULL, `following_count` INTEGER NOT NULL, `is_verified` INTEGER NOT NULL, `is_admin` INTEGER NOT NULL, `voice_access_granted` INTEGER NOT NULL, `is_online` INTEGER NOT NULL, `last_seen` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `chats` (`id` TEXT NOT NULL, `name` TEXT, `is_group` INTEGER NOT NULL, `created_by` TEXT NOT NULL, `participantsJson` TEXT NOT NULL, `last_message_at` TEXT, `display_name` TEXT, `display_image` TEXT, `unread_count` INTEGER NOT NULL, `other_user_id` TEXT, `last_message_preview` TEXT, `last_message_timestamp` TEXT, `last_message_id` TEXT, `last_message_sender_id` TEXT, `is_deleted` INTEGER NOT NULL, `nickname` TEXT, `cleared_at` TEXT, `is_muted` INTEGER NOT NULL DEFAULT 0, `is_pinned` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `media_size` INTEGER, `created_at` TEXT NOT NULL, `reply_to_id` TEXT, `reply_to_json` TEXT, `status` TEXT NOT NULL, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, `reactions_json` TEXT NOT NULL, `is_forwarded` INTEGER NOT NULL, `forwarded_from_message_id` TEXT, `forwarded_from_user_id` TEXT, `poll_id` TEXT, `delivered_at` TEXT, `read_at` TEXT, `is_synced` INTEGER NOT NULL, `pending_action` TEXT, PRIMARY KEY(`id`))",
                "INSERT INTO `users` (`id`,`username`,`full_name`,`avatar_url`,`bio`,`follower_count`,`following_count`,`is_verified`,`is_admin`,`voice_access_granted`,`is_online`,`last_seen`,`updated_at`) VALUES ('u1','alice','Alice',NULL,NULL,0,0,0,0,1,1,'2026-02-01T00:00:00Z',1000)",
                "INSERT INTO `chats` (`id`,`name`,`is_group`,`created_by`,`participantsJson`,`last_message_at`,`display_name`,`display_image`,`unread_count`,`other_user_id`,`last_message_preview`,`last_message_timestamp`,`last_message_id`,`last_message_sender_id`,`is_deleted`,`nickname`,`cleared_at`,`is_muted`,`is_pinned`) VALUES ('c1',NULL,0,'u1','[\"u1\",\"u2\"]','2026-02-01T00:00:00Z','Bob',NULL,0,'u2','hola','2026-02-01T00:00:00Z','m1','u2',0,NULL,NULL,0,1)",
                "INSERT INTO `messages` (`id`,`chat_id`,`sender_id`,`content`,`content_type`,`media_url`,`media_size`,`created_at`,`reply_to_id`,`reply_to_json`,`status`,`is_edited`,`deleted_for_all`,`deleted_by_json`,`reactions_json`,`is_forwarded`,`forwarded_from_message_id`,`forwarded_from_user_id`,`poll_id`,`delivered_at`,`read_at`,`is_synced`,`pending_action`) VALUES ('m1','c1','u2','hola','text',NULL,NULL,'2026-02-01T00:00:00Z',NULL,NULL,'sent',0,0,'[]','{}',0,NULL,NULL,NULL,NULL,NULL,1,NULL)"
            )
        )

        val db = openMigratedDatabase(dbName)
        try {
            val sqlite = db.openHelper.writableDatabase
            assertHasColumns(sqlite, "chat_settings", "chat_id", "background_type", "background_uri", "updated_at")
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM chats"))
            assertEquals(1, singleInt(sqlite, "SELECT is_pinned FROM chats WHERE id='c1'"))
            assertEquals(0, singleInt(sqlite, "SELECT COUNT(*) FROM chat_settings"))
        } finally {
            db.close()
        }

        context.deleteDatabase(dbName)
    }

    @Test
    fun migrateFrom34LegacyShape_preservesRowsAndNormalizesSchema() {
        val dbName = "migration_v34_${System.currentTimeMillis()}.db"
        createLegacyDatabase(
            name = dbName,
            version = 34,
            statements = listOf(
                "CREATE TABLE IF NOT EXISTS `users` (`id` TEXT NOT NULL, `username` TEXT NOT NULL, `full_name` TEXT NOT NULL, `avatar_url` TEXT, `bio` TEXT, `follower_count` INTEGER NOT NULL, `following_count` INTEGER NOT NULL, `is_verified` INTEGER NOT NULL, `is_admin` INTEGER NOT NULL, `voice_access_granted` INTEGER NOT NULL, `is_online` INTEGER NOT NULL, `last_seen` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `chats` (`id` TEXT NOT NULL, `name` TEXT, `is_group` INTEGER NOT NULL, `created_by` TEXT NOT NULL, `participantsJson` TEXT NOT NULL, `last_message_at` TEXT, `display_name` TEXT, `display_image` TEXT, `unread_count` INTEGER NOT NULL, `other_user_id` TEXT, `last_message_preview` TEXT, `last_message_timestamp` TEXT, `last_message_id` TEXT, `last_message_sender_id` TEXT, `is_deleted` INTEGER NOT NULL, `nickname` TEXT, `cleared_at` TEXT, `is_muted` INTEGER NOT NULL, `is_pinned` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `chat_settings` (`chat_id` TEXT NOT NULL, `background_type` TEXT NOT NULL, `background_uri` TEXT, `updated_at` TEXT NOT NULL, PRIMARY KEY(`chat_id`))",
                "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `media_size` INTEGER, `created_at` TEXT NOT NULL, `reply_to_id` TEXT, `reply_to_json` TEXT, `status` TEXT NOT NULL, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, `reactions_json` TEXT NOT NULL, `is_forwarded` INTEGER NOT NULL, `forwarded_from_message_id` TEXT, `forwarded_from_user_id` TEXT, `poll_id` TEXT, `delivered_at` TEXT, `read_at` TEXT, `is_synced` INTEGER NOT NULL, `pending_action` TEXT, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `image` TEXT, `description` TEXT, `created_at` TEXT NOT NULL, `creator_id` TEXT NOT NULL, `last_message` TEXT, `last_message_at` TEXT, `unread_count` INTEGER NOT NULL, `is_deleted` INTEGER NOT NULL, `is_member` INTEGER NOT NULL, `only_admins_can_talk` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `group_members` (`group_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `role` TEXT NOT NULL, `joined_at` TEXT NOT NULL, PRIMARY KEY(`group_id`, `user_id`))",
                "CREATE TABLE IF NOT EXISTS `group_messages` (`id` TEXT NOT NULL, `group_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `poll_id` TEXT, `created_at` TEXT NOT NULL, `status` TEXT NOT NULL, `delivered_at` TEXT, `read_at` TEXT, `sender_name` TEXT, `sender_image` TEXT, `reply_to_id` TEXT, `reply_to_json` TEXT, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, `reactions_json` TEXT NOT NULL, `is_forwarded` INTEGER NOT NULL, `forwarded_from_message_id` TEXT, `forwarded_from_user_id` TEXT, `is_synced` INTEGER NOT NULL, `pending_action` TEXT, `retry_count` INTEGER NOT NULL, `last_retry_at` TEXT, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `communities` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `image` TEXT, `description` TEXT, `category` TEXT, `is_public` INTEGER NOT NULL, `created_at` TEXT NOT NULL, `creator_id` TEXT NOT NULL, `last_message` TEXT, `last_message_at` TEXT, `unread_count` INTEGER NOT NULL, `is_deleted` INTEGER NOT NULL, `only_admins_can_talk` INTEGER NOT NULL, `is_member` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `community_members` (`community_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `role` TEXT NOT NULL, `joined_at` TEXT NOT NULL, PRIMARY KEY(`community_id`, `user_id`))",
                "CREATE TABLE IF NOT EXISTS `community_messages` (`id` TEXT NOT NULL, `community_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `poll_id` TEXT, `created_at` TEXT NOT NULL, `status` TEXT NOT NULL, `delivered_at` TEXT, `read_at` TEXT, `sender_name` TEXT, `sender_image` TEXT, `reply_to_id` TEXT, `reply_to_json` TEXT, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, `reactions_json` TEXT NOT NULL, `is_forwarded` INTEGER NOT NULL, `forwarded_from_message_id` TEXT, `forwarded_from_user_id` TEXT, `is_synced` INTEGER NOT NULL, `pending_action` TEXT, `retry_count` INTEGER NOT NULL, `last_retry_at` TEXT, PRIMARY KEY(`id`))",
                "INSERT INTO `users` (`id`,`username`,`full_name`,`avatar_url`,`bio`,`follower_count`,`following_count`,`is_verified`,`is_admin`,`voice_access_granted`,`is_online`,`last_seen`,`updated_at`) VALUES ('u1','alice','Alice',NULL,'bio',4,7,1,0,1,1,'2026-02-01T00:00:00Z',1000)",
                "INSERT INTO `chats` (`id`,`name`,`is_group`,`created_by`,`participantsJson`,`last_message_at`,`display_name`,`display_image`,`unread_count`,`other_user_id`,`last_message_preview`,`last_message_timestamp`,`last_message_id`,`last_message_sender_id`,`is_deleted`,`nickname`,`cleared_at`,`is_muted`,`is_pinned`) VALUES ('c1',NULL,0,'u1','[\"u1\",\"u2\"]','2026-02-01T00:00:00Z','Bob',NULL,2,'u2','hola','2026-02-01T00:00:00Z','m1','u2',0,'Bobby',NULL,1,1)",
                "INSERT INTO `chat_settings` (`chat_id`,`background_type`,`background_uri`,`updated_at`) VALUES ('c1','image','content://chat/bg','2026-02-01T00:00:00Z')",
                "INSERT INTO `messages` (`id`,`chat_id`,`sender_id`,`content`,`content_type`,`media_url`,`media_size`,`created_at`,`reply_to_id`,`reply_to_json`,`status`,`is_edited`,`deleted_for_all`,`deleted_by_json`,`reactions_json`,`is_forwarded`,`forwarded_from_message_id`,`forwarded_from_user_id`,`poll_id`,`delivered_at`,`read_at`,`is_synced`,`pending_action`) VALUES ('m1','c1','u2','hola','text',NULL,NULL,'2026-02-01T00:00:00Z',NULL,NULL,'sent',0,0,'[]','{\"like\":[\"u1\"]}',0,NULL,NULL,NULL,NULL,NULL,1,NULL)",
                "INSERT INTO `groups` (`id`,`name`,`image`,`description`,`created_at`,`creator_id`,`last_message`,`last_message_at`,`unread_count`,`is_deleted`,`is_member`,`only_admins_can_talk`) VALUES ('g1','Grupo',NULL,'desc','2026-01-02T00:00:00Z','u1','hola','2026-01-02T00:00:00Z',2,0,1,0)",
                "INSERT INTO `group_members` (`group_id`,`user_id`,`role`,`joined_at`) VALUES ('g1','u1','owner','2026-01-02T00:00:00Z')",
                "INSERT INTO `group_messages` (`id`,`group_id`,`sender_id`,`content`,`content_type`,`media_url`,`poll_id`,`created_at`,`status`,`delivered_at`,`read_at`,`sender_name`,`sender_image`,`reply_to_id`,`reply_to_json`,`is_edited`,`deleted_for_all`,`deleted_by_json`,`reactions_json`,`is_forwarded`,`forwarded_from_message_id`,`forwarded_from_user_id`,`is_synced`,`pending_action`,`retry_count`,`last_retry_at`) VALUES ('gm1','g1','u1','mensaje','text',NULL,NULL,'2026-01-02T00:01:00Z','sent',NULL,NULL,'Alice',NULL,NULL,NULL,0,0,'[]','{}',0,NULL,NULL,1,NULL,0,NULL)",
                "INSERT INTO `communities` (`id`,`name`,`image`,`description`,`category`,`is_public`,`created_at`,`creator_id`,`last_message`,`last_message_at`,`unread_count`,`is_deleted`,`only_admins_can_talk`,`is_member`) VALUES ('co1','Comunidad',NULL,'desc','tech',1,'2026-01-03T00:00:00Z','u1','hola','2026-01-03T00:00:00Z',1,0,0,1)",
                "INSERT INTO `community_members` (`community_id`,`user_id`,`role`,`joined_at`) VALUES ('co1','u1','owner','2026-01-03T00:00:00Z')",
                "INSERT INTO `community_messages` (`id`,`community_id`,`sender_id`,`content`,`content_type`,`media_url`,`poll_id`,`created_at`,`status`,`delivered_at`,`read_at`,`sender_name`,`sender_image`,`reply_to_id`,`reply_to_json`,`is_edited`,`deleted_for_all`,`deleted_by_json`,`reactions_json`,`is_forwarded`,`forwarded_from_message_id`,`forwarded_from_user_id`,`is_synced`,`pending_action`,`retry_count`,`last_retry_at`) VALUES ('cm1','co1','u1','mensaje comunidad','text',NULL,NULL,'2026-01-03T00:01:00Z','sent',NULL,NULL,'Alice',NULL,NULL,NULL,0,0,'[]','{}',0,NULL,NULL,1,NULL,0,NULL)"
            )
        )

        val db = openMigratedDatabase(dbName)
        try {
            val sqlite = db.openHelper.writableDatabase
            assertHasColumns(sqlite, "chat_settings", "chat_id", "background_type", "background_uri", "updated_at")
            assertHasColumns(sqlite, "group_messages", "poll_id", "reactions_json", "is_synced", "retry_count", "last_retry_at")
            assertHasColumns(sqlite, "community_messages", "poll_id", "reactions_json", "is_synced", "retry_count", "last_retry_at")
            assertHasColumns(sqlite, "hashtags", "id", "name", "post_count", "video_count", "trending_score", "created_at", "updated_at")
            assertHasColumns(sqlite, "post_hashtags", "post_id", "hashtag_id", "position")
            assertHasColumns(sqlite, "video_hashtags", "video_id", "hashtag_id", "position")
            assertHasColumns(sqlite, "follows", "followerId", "followedId", "createdAt")
            assertHasColumns(sqlite, "device_tokens", "id", "token", "updatedAt")
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM users"))
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM chats"))
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM chat_settings"))
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM group_messages"))
            assertEquals(1, singleInt(sqlite, "SELECT COUNT(*) FROM community_messages"))
            assertEquals(0, singleInt(sqlite, "SELECT COUNT(*) FROM hashtags"))
            assertEquals(0, singleInt(sqlite, "SELECT COUNT(*) FROM post_hashtags"))
            assertEquals(0, singleInt(sqlite, "SELECT COUNT(*) FROM video_hashtags"))
            assertEquals(0, singleInt(sqlite, "SELECT COUNT(*) FROM follows"))
            assertEquals(0, singleInt(sqlite, "SELECT COUNT(*) FROM device_tokens"))
            assertEquals("content://chat/bg", singleString(sqlite, "SELECT background_uri FROM chat_settings WHERE chat_id='c1'"))
            assertEquals(1, singleInt(sqlite, "SELECT is_pinned FROM chats WHERE id='c1'"))
            assertEquals(1, singleInt(sqlite, "SELECT is_synced FROM group_messages WHERE id='gm1'"))
            assertEquals("{}", singleString(sqlite, "SELECT reactions_json FROM community_messages WHERE id='cm1'"))
        } finally {
            db.close()
        }

        context.deleteDatabase(dbName)
    }

    private fun createLegacyDatabase(
        name: String,
        version: Int,
        statements: List<String>
    ) {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            statements.forEach(db::execSQL)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) = Unit
                    }
                )
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun openMigratedDatabase(name: String): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()
    }

    private fun assertHasColumns(
        db: SupportSQLiteDatabase,
        table: String,
        vararg expectedColumns: String
    ) {
        val actualColumns = linkedSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                actualColumns += cursor.getString(nameIndex)
            }
        }
        expectedColumns.forEach { column ->
            assertTrue("Expected column $column in table $table, found $actualColumns", actualColumns.contains(column))
        }
    }

    private fun singleInt(db: SupportSQLiteDatabase, sql: String): Int {
        db.query(sql).use { cursor ->
            assertTrue("Query returned no rows: $sql", cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private fun singleString(db: SupportSQLiteDatabase, sql: String): String {
        db.query(sql).use { cursor ->
            assertTrue("Query returned no rows: $sql", cursor.moveToFirst())
            return cursor.getString(0)
        }
    }
}
