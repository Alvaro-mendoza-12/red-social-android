package conectate.com.data.local

import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {

    val ALL: Array<Migration> = (1 until APP_DATABASE_VERSION)
        .map { fromVersion ->
            object : Migration(fromVersion, APP_DATABASE_VERSION) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    Log.i("DatabaseMigrations", "Normalizing schema $fromVersion->$APP_DATABASE_VERSION")
                    LatestSchemaNormalizer.migrate(db)
                }
            }
        }
        .toTypedArray()
}

private object LatestSchemaNormalizer {
    private const val TAG = "LatestSchemaNormalizer"

    private data class ColumnSpec(
        val name: String,
        val fallbackSql: String? = null,
        val sourceCandidates: List<String> = listOf(name),
        val coalesceNulls: Boolean = fallbackSql != null
    )

    private data class TablePlan(
        val name: String,
        val createSql: String,
        val columns: List<ColumnSpec>,
        val indexSql: List<String> = emptyList()
    )

    private val usersPlan = TablePlan(
        name = "users",
        createSql = "CREATE TABLE IF NOT EXISTS `users` (`id` TEXT NOT NULL, `username` TEXT NOT NULL, `full_name` TEXT NOT NULL, `avatar_url` TEXT, `avatar_media_type` TEXT, `avatar_video_muted` INTEGER NOT NULL DEFAULT 1, `avatar_duration_ms` INTEGER, `bio` TEXT, `follower_count` INTEGER NOT NULL DEFAULT 0, `following_count` INTEGER NOT NULL DEFAULT 0, `is_verified` INTEGER NOT NULL DEFAULT 0, `is_admin` INTEGER NOT NULL DEFAULT 0, `voice_access_granted` INTEGER NOT NULL DEFAULT 0, `is_online` INTEGER NOT NULL DEFAULT 0, `last_seen` TEXT, `updated_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("username", "''"),
            ColumnSpec("full_name", "''"),
            ColumnSpec("avatar_url", "NULL", coalesceNulls = false),
            ColumnSpec("avatar_media_type", "NULL", coalesceNulls = false),
            ColumnSpec("avatar_video_muted", "1"),
            ColumnSpec("avatar_duration_ms", "NULL", coalesceNulls = false),
            ColumnSpec("bio", "NULL", coalesceNulls = false),
            ColumnSpec("follower_count", "0"),
            ColumnSpec("following_count", "0"),
            ColumnSpec("is_verified", "0"),
            ColumnSpec("is_admin", "0"),
            ColumnSpec("voice_access_granted", "0"),
            ColumnSpec("is_online", "0"),
            ColumnSpec("last_seen", "NULL", coalesceNulls = false),
            ColumnSpec("updated_at", "CAST(strftime('%s','now') AS INTEGER) * 1000")
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_users_username` ON `users` (`username`)"
        )
    )

    private val chatsPlan = TablePlan(
        name = "chats",
        createSql = "CREATE TABLE IF NOT EXISTS `chats` (`id` TEXT NOT NULL, `name` TEXT, `is_group` INTEGER NOT NULL, `created_by` TEXT NOT NULL, `participantsJson` TEXT NOT NULL, `last_message_at` TEXT, `display_name` TEXT, `display_image` TEXT, `unread_count` INTEGER NOT NULL, `other_user_id` TEXT, `last_message_preview` TEXT, `last_message_timestamp` TEXT, `last_message_id` TEXT, `last_message_sender_id` TEXT, `is_deleted` INTEGER NOT NULL, `nickname` TEXT, `cleared_at` TEXT, `is_muted` INTEGER NOT NULL DEFAULT 0, `is_pinned` INTEGER NOT NULL DEFAULT 0, `is_archived` INTEGER NOT NULL DEFAULT 0, `is_favorite` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("name", "NULL", coalesceNulls = false),
            ColumnSpec("is_group", "0"),
            ColumnSpec("created_by", "''"),
            ColumnSpec("participantsJson", "'[]'"),
            ColumnSpec("last_message_at", "NULL", coalesceNulls = false),
            ColumnSpec("display_name", "NULL", coalesceNulls = false),
            ColumnSpec("display_image", "NULL", coalesceNulls = false),
            ColumnSpec("unread_count", "0"),
            ColumnSpec("other_user_id", "NULL", coalesceNulls = false),
            ColumnSpec("last_message_preview", "NULL", coalesceNulls = false),
            ColumnSpec("last_message_timestamp", "NULL", coalesceNulls = false),
            ColumnSpec("last_message_id", "NULL", coalesceNulls = false),
            ColumnSpec("last_message_sender_id", "NULL", coalesceNulls = false),
            ColumnSpec("is_deleted", "0"),
            ColumnSpec("nickname", "NULL", coalesceNulls = false),
            ColumnSpec("cleared_at", "NULL", coalesceNulls = false),
            ColumnSpec("is_muted", "0"),
            ColumnSpec("is_pinned", "0"),
            ColumnSpec("is_archived", "0"),
            ColumnSpec("is_favorite", "0")
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_chats_last_message_at` ON `chats` (`last_message_at`)",
            "CREATE INDEX IF NOT EXISTS `index_chats_is_pinned_last_message_at` ON `chats` (`is_pinned` ASC, `last_message_at` DESC)",
            "CREATE INDEX IF NOT EXISTS `index_chats_is_archived_is_pinned_is_favorite_last_message_at` ON `chats` (`is_archived` ASC, `is_pinned` ASC, `is_favorite` ASC, `last_message_at` DESC)",
            "CREATE INDEX IF NOT EXISTS `index_chats_is_group` ON `chats` (`is_group`)",
            "CREATE INDEX IF NOT EXISTS `index_chats_unread_count` ON `chats` (`unread_count`)"
        )
    )

    private val chatSettingsPlan = TablePlan(
        name = "chat_settings",
        createSql = "CREATE TABLE IF NOT EXISTS `chat_settings` (`chat_id` TEXT NOT NULL, `background_type` TEXT NOT NULL DEFAULT 'default', `background_uri` TEXT, `updated_at` TEXT NOT NULL, PRIMARY KEY(`chat_id`))",
        columns = listOf(
            ColumnSpec("chat_id"),
            ColumnSpec("background_type", "'default'"),
            ColumnSpec("background_uri", "NULL", coalesceNulls = false),
            ColumnSpec("updated_at", "CURRENT_TIMESTAMP")
        )
    )

    private var messagesPlan = TablePlan(
        name = "messages",
        createSql = "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `chat_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `media_size` INTEGER, `created_at` TEXT NOT NULL, `reply_to_id` TEXT, `reply_to_json` TEXT, `status` TEXT NOT NULL, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, `reactions_json` TEXT NOT NULL, `is_forwarded` INTEGER NOT NULL, `forwarded_from_message_id` TEXT, `forwarded_from_user_id` TEXT, `poll_id` TEXT, `delivered_at` TEXT, `read_at` TEXT, `is_synced` INTEGER NOT NULL, `pending_action` TEXT, PRIMARY KEY(`id`))",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("chat_id"),
            ColumnSpec("sender_id", "'unknown'"),
            ColumnSpec("content", "''"),
            ColumnSpec("content_type", "'text'"),
            ColumnSpec("media_url", "NULL", coalesceNulls = false),
            ColumnSpec("media_size", "NULL", coalesceNulls = false),
            ColumnSpec("created_at", "CURRENT_TIMESTAMP"),
            ColumnSpec("reply_to_id", "NULL", coalesceNulls = false),
            ColumnSpec("reply_to_json", "NULL", coalesceNulls = false),
            ColumnSpec("status", "'sent'"),
            ColumnSpec("is_edited", "0"),
            ColumnSpec("deleted_for_all", "0"),
            ColumnSpec("deleted_by_json", "'[]'"),
            ColumnSpec("reactions_json", "'{}'"),
            ColumnSpec("is_forwarded", "0"),
            ColumnSpec("forwarded_from_message_id", "NULL", coalesceNulls = false),
            ColumnSpec("forwarded_from_user_id", "NULL", coalesceNulls = false),
            ColumnSpec("poll_id", "NULL", coalesceNulls = false),
            ColumnSpec("delivered_at", "NULL", coalesceNulls = false),
            ColumnSpec("read_at", "NULL", coalesceNulls = false),
            ColumnSpec("is_synced", "1"),
            ColumnSpec("pending_action", "NULL", coalesceNulls = false)
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_messages_chat_id_created_at` ON `messages` (`chat_id`, `created_at`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_sender_id` ON `messages` (`sender_id`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_status_is_synced` ON `messages` (`status`, `is_synced`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_pending_action` ON `messages` (`pending_action`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_is_forwarded` ON `messages` (`is_forwarded`)"
        )
    )

    private val videoStatsPlan = TablePlan(
        name = "video_stats",
        createSql = "CREATE TABLE IF NOT EXISTS `video_stats` (`videoId` TEXT NOT NULL, `likeCount` INTEGER NOT NULL, `commentCount` INTEGER NOT NULL, `shareCount` INTEGER NOT NULL, `viewCount` INTEGER NOT NULL, `lastUpdated` INTEGER NOT NULL, PRIMARY KEY(`videoId`))",
        columns = listOf(
            ColumnSpec("videoId", sourceCandidates = listOf("videoId", "video_id")),
            ColumnSpec("likeCount", "0", listOf("likeCount", "like_count")),
            ColumnSpec("commentCount", "0", listOf("commentCount", "comment_count")),
            ColumnSpec("shareCount", "0", listOf("shareCount", "share_count")),
            ColumnSpec("viewCount", "0", listOf("viewCount", "view_count")),
            ColumnSpec("lastUpdated", "CAST(strftime('%s','now') AS INTEGER) * 1000", listOf("lastUpdated", "last_updated"))
        )
    )

    private val videoCommentsPlan = TablePlan(
        name = "video_comments",
        createSql = "CREATE TABLE IF NOT EXISTS `video_comments` (`id` TEXT NOT NULL, `videoId` TEXT NOT NULL, `userId` TEXT NOT NULL, `userName` TEXT NOT NULL, `userAvatarUrl` TEXT, `content` TEXT NOT NULL, `parentId` TEXT, `likeCount` INTEGER NOT NULL, `createdAt` TEXT NOT NULL, `isPosted` INTEGER NOT NULL, `contentType` TEXT NOT NULL, `mediaUrl` TEXT, PRIMARY KEY(`id`))",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("videoId", sourceCandidates = listOf("videoId", "video_id")),
            ColumnSpec("userId", sourceCandidates = listOf("userId", "user_id")),
            ColumnSpec("userName", "''", listOf("userName", "user_name")),
            ColumnSpec("userAvatarUrl", "NULL", listOf("userAvatarUrl", "user_avatar_url"), coalesceNulls = false),
            ColumnSpec("content", "''"),
            ColumnSpec("parentId", "NULL", listOf("parentId", "parent_id"), coalesceNulls = false),
            ColumnSpec("likeCount", "0", listOf("likeCount", "like_count")),
            ColumnSpec("createdAt", "CURRENT_TIMESTAMP", listOf("createdAt", "created_at")),
            ColumnSpec("isPosted", "1", listOf("isPosted", "is_posted")),
            ColumnSpec("contentType", "'text'", listOf("contentType", "content_type")),
            ColumnSpec("mediaUrl", "NULL", listOf("mediaUrl", "media_url"), coalesceNulls = false)
        )
    )

    private val groupsPlan = TablePlan(
        name = "groups",
        createSql = "CREATE TABLE IF NOT EXISTS `groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `image` TEXT, `description` TEXT, `created_at` TEXT NOT NULL, `creator_id` TEXT NOT NULL, `last_message` TEXT, `last_message_at` TEXT, `unread_count` INTEGER NOT NULL, `is_deleted` INTEGER NOT NULL, `is_member` INTEGER NOT NULL, `only_admins_can_talk` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("name", "''"),
            ColumnSpec("image", "NULL", coalesceNulls = false),
            ColumnSpec("description", "NULL", coalesceNulls = false),
            ColumnSpec("created_at", "CURRENT_TIMESTAMP"),
            ColumnSpec("creator_id", "''"),
            ColumnSpec("last_message", "NULL", coalesceNulls = false),
            ColumnSpec("last_message_at", "NULL", coalesceNulls = false),
            ColumnSpec("unread_count", "0"),
            ColumnSpec("is_deleted", "0"),
            ColumnSpec("is_member", "1"),
            ColumnSpec("only_admins_can_talk", "0")
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_groups_last_message_at` ON `groups` (`last_message_at`)"
        )
    )

    private val groupMembersPlan = TablePlan(
        name = "group_members",
        createSql = "CREATE TABLE IF NOT EXISTS `group_members` (`group_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `role` TEXT NOT NULL, `joined_at` TEXT NOT NULL, PRIMARY KEY(`group_id`, `user_id`), FOREIGN KEY(`group_id`) REFERENCES `groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        columns = listOf(
            ColumnSpec("group_id"),
            ColumnSpec("user_id"),
            ColumnSpec("role", "'member'"),
            ColumnSpec("joined_at", "CURRENT_TIMESTAMP")
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_group_members_user_id` ON `group_members` (`user_id`)"
        )
    )

    private val groupMessagesPlan = TablePlan(
        name = "group_messages",
        createSql = "CREATE TABLE IF NOT EXISTS `group_messages` (`id` TEXT NOT NULL, `group_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `poll_id` TEXT, `created_at` TEXT NOT NULL, `status` TEXT NOT NULL, `delivered_at` TEXT, `read_at` TEXT, `sender_name` TEXT, `sender_image` TEXT, `reply_to_id` TEXT, `reply_to_json` TEXT, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, `reactions_json` TEXT NOT NULL DEFAULT '{}', `is_forwarded` INTEGER NOT NULL DEFAULT 0, `forwarded_from_message_id` TEXT, `forwarded_from_user_id` TEXT, `is_synced` INTEGER NOT NULL DEFAULT 1, `pending_action` TEXT, `retry_count` INTEGER NOT NULL DEFAULT 0, `last_retry_at` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`group_id`) REFERENCES `groups`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("group_id"),
            ColumnSpec("sender_id", "'unknown'"),
            ColumnSpec("content", "''"),
            ColumnSpec("content_type", "'text'"),
            ColumnSpec("media_url", "NULL", coalesceNulls = false),
            ColumnSpec("poll_id", "NULL", coalesceNulls = false),
            ColumnSpec("created_at", "CURRENT_TIMESTAMP"),
            ColumnSpec("status", "'sent'"),
            ColumnSpec("delivered_at", "NULL", coalesceNulls = false),
            ColumnSpec("read_at", "NULL", coalesceNulls = false),
            ColumnSpec("sender_name", "NULL", coalesceNulls = false),
            ColumnSpec("sender_image", "NULL", coalesceNulls = false),
            ColumnSpec("reply_to_id", "NULL", coalesceNulls = false),
            ColumnSpec("reply_to_json", "NULL", coalesceNulls = false),
            ColumnSpec("is_edited", "0"),
            ColumnSpec("deleted_for_all", "0"),
            ColumnSpec("deleted_by_json", "'[]'"),
            ColumnSpec("reactions_json", "'{}'"),
            ColumnSpec("is_forwarded", "0"),
            ColumnSpec("forwarded_from_message_id", "NULL", coalesceNulls = false),
            ColumnSpec("forwarded_from_user_id", "NULL", coalesceNulls = false),
            ColumnSpec("is_synced", "1"),
            ColumnSpec("pending_action", "NULL", coalesceNulls = false),
            ColumnSpec("retry_count", "0"),
            ColumnSpec("last_retry_at", "NULL", coalesceNulls = false)
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_group_messages_group_id` ON `group_messages` (`group_id`)",
            "CREATE INDEX IF NOT EXISTS `index_group_messages_created_at` ON `group_messages` (`created_at`)",
            "CREATE INDEX IF NOT EXISTS `index_group_messages_is_synced_pending_action` ON `group_messages` (`is_synced`, `pending_action`)",
            "CREATE INDEX IF NOT EXISTS `index_group_messages_poll_id` ON `group_messages` (`poll_id`)",
            "CREATE INDEX IF NOT EXISTS `index_group_messages_is_forwarded` ON `group_messages` (`is_forwarded`)"
        )
    )

    private val communitiesPlan = TablePlan(
        name = "communities",
        createSql = "CREATE TABLE IF NOT EXISTS `communities` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `image` TEXT, `description` TEXT, `category` TEXT, `is_public` INTEGER NOT NULL, `created_at` TEXT NOT NULL, `creator_id` TEXT NOT NULL, `last_message` TEXT, `last_message_at` TEXT, `unread_count` INTEGER NOT NULL, `is_deleted` INTEGER NOT NULL, `only_admins_can_talk` INTEGER NOT NULL, `is_member` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("name", "''"),
            ColumnSpec("image", "NULL", coalesceNulls = false),
            ColumnSpec("description", "NULL", coalesceNulls = false),
            ColumnSpec("category", "NULL", coalesceNulls = false),
            ColumnSpec("is_public", "1"),
            ColumnSpec("created_at", "CURRENT_TIMESTAMP"),
            ColumnSpec("creator_id", "''"),
            ColumnSpec("last_message", "NULL", coalesceNulls = false),
            ColumnSpec("last_message_at", "NULL", coalesceNulls = false),
            ColumnSpec("unread_count", "0"),
            ColumnSpec("is_deleted", "0"),
            ColumnSpec("only_admins_can_talk", "0"),
            ColumnSpec("is_member", "1")
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_communities_last_message_at` ON `communities` (`last_message_at`)",
            "CREATE INDEX IF NOT EXISTS `index_communities_category` ON `communities` (`category`)"
        )
    )

    private val communityMembersPlan = TablePlan(
        name = "community_members",
        createSql = "CREATE TABLE IF NOT EXISTS `community_members` (`community_id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `role` TEXT NOT NULL, `joined_at` TEXT NOT NULL, PRIMARY KEY(`community_id`, `user_id`), FOREIGN KEY(`community_id`) REFERENCES `communities`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        columns = listOf(
            ColumnSpec("community_id"),
            ColumnSpec("user_id"),
            ColumnSpec("role", "'member'"),
            ColumnSpec("joined_at", "CURRENT_TIMESTAMP")
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_community_members_user_id` ON `community_members` (`user_id`)"
        )
    )

    private val communityMessagesPlan = TablePlan(
        name = "community_messages",
        createSql = "CREATE TABLE IF NOT EXISTS `community_messages` (`id` TEXT NOT NULL, `community_id` TEXT NOT NULL, `sender_id` TEXT NOT NULL, `content` TEXT NOT NULL, `content_type` TEXT NOT NULL, `media_url` TEXT, `poll_id` TEXT, `created_at` TEXT NOT NULL, `status` TEXT NOT NULL, `delivered_at` TEXT, `read_at` TEXT, `sender_name` TEXT, `sender_image` TEXT, `reply_to_id` TEXT, `reply_to_json` TEXT, `is_edited` INTEGER NOT NULL, `deleted_for_all` INTEGER NOT NULL, `deleted_by_json` TEXT NOT NULL, `reactions_json` TEXT NOT NULL DEFAULT '{}', `is_forwarded` INTEGER NOT NULL DEFAULT 0, `forwarded_from_message_id` TEXT, `forwarded_from_user_id` TEXT, `is_synced` INTEGER NOT NULL DEFAULT 1, `pending_action` TEXT, `retry_count` INTEGER NOT NULL DEFAULT 0, `last_retry_at` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`community_id`) REFERENCES `communities`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("community_id"),
            ColumnSpec("sender_id", "'unknown'"),
            ColumnSpec("content", "''"),
            ColumnSpec("content_type", "'text'"),
            ColumnSpec("media_url", "NULL", coalesceNulls = false),
            ColumnSpec("poll_id", "NULL", coalesceNulls = false),
            ColumnSpec("created_at", "CURRENT_TIMESTAMP"),
            ColumnSpec("status", "'sent'"),
            ColumnSpec("delivered_at", "NULL", coalesceNulls = false),
            ColumnSpec("read_at", "NULL", coalesceNulls = false),
            ColumnSpec("sender_name", "NULL", coalesceNulls = false),
            ColumnSpec("sender_image", "NULL", coalesceNulls = false),
            ColumnSpec("reply_to_id", "NULL", coalesceNulls = false),
            ColumnSpec("reply_to_json", "NULL", coalesceNulls = false),
            ColumnSpec("is_edited", "0"),
            ColumnSpec("deleted_for_all", "0"),
            ColumnSpec("deleted_by_json", "'[]'"),
            ColumnSpec("reactions_json", "'{}'"),
            ColumnSpec("is_forwarded", "0"),
            ColumnSpec("forwarded_from_message_id", "NULL", coalesceNulls = false),
            ColumnSpec("forwarded_from_user_id", "NULL", coalesceNulls = false),
            ColumnSpec("is_synced", "1"),
            ColumnSpec("pending_action", "NULL", coalesceNulls = false),
            ColumnSpec("retry_count", "0"),
            ColumnSpec("last_retry_at", "NULL", coalesceNulls = false)
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_community_messages_community_id` ON `community_messages` (`community_id`)",
            "CREATE INDEX IF NOT EXISTS `index_community_messages_created_at` ON `community_messages` (`created_at`)",
            "CREATE INDEX IF NOT EXISTS `index_community_messages_is_synced_pending_action` ON `community_messages` (`is_synced`, `pending_action`)",
            "CREATE INDEX IF NOT EXISTS `index_community_messages_poll_id` ON `community_messages` (`poll_id`)",
            "CREATE INDEX IF NOT EXISTS `index_community_messages_is_forwarded` ON `community_messages` (`is_forwarded`)"
        )
    )

    private var hashtagsPlan = TablePlan(
        name = "hashtags",
        createSql = "CREATE TABLE IF NOT EXISTS `hashtags` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `post_count` INTEGER NOT NULL, `video_count` INTEGER NOT NULL, `trending_score` REAL NOT NULL, `created_at` TEXT, `updated_at` TEXT, PRIMARY KEY(`id`))",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("name", "''"),
            ColumnSpec("post_count", "0"),
            ColumnSpec("video_count", "0"),
            ColumnSpec("trending_score", "0.0"),
            ColumnSpec("created_at", "NULL", coalesceNulls = false),
            ColumnSpec("updated_at", "NULL", coalesceNulls = false)
        )
    )

    private val postHashtagsPlan = TablePlan(
        name = "post_hashtags",
        createSql = "CREATE TABLE IF NOT EXISTS `post_hashtags` (`post_id` TEXT NOT NULL, `hashtag_id` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`post_id`, `hashtag_id`), FOREIGN KEY(`hashtag_id`) REFERENCES `hashtags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        columns = listOf(
            ColumnSpec("post_id"),
            ColumnSpec("hashtag_id"),
            ColumnSpec("position", "0")
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_post_hashtags_hashtag_id` ON `post_hashtags` (`hashtag_id`)"
        )
    )

    private val videoHashtagsPlan = TablePlan(
        name = "video_hashtags",
        createSql = "CREATE TABLE IF NOT EXISTS `video_hashtags` (`video_id` TEXT NOT NULL, `hashtag_id` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`video_id`, `hashtag_id`), FOREIGN KEY(`hashtag_id`) REFERENCES `hashtags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        columns = listOf(
            ColumnSpec("video_id"),
            ColumnSpec("hashtag_id"),
            ColumnSpec("position", "0")
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_video_hashtags_hashtag_id` ON `video_hashtags` (`hashtag_id`)"
        )
    )

    private val followsPlan = TablePlan(
        name = "follows",
        createSql = "CREATE TABLE IF NOT EXISTS `follows` (`followerId` TEXT NOT NULL, `followedId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`followerId`, `followedId`), FOREIGN KEY(`followerId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`followedId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        columns = listOf(
            ColumnSpec("followerId"),
            ColumnSpec("followedId"),
            ColumnSpec("createdAt", "CAST(strftime('%s','now') AS INTEGER) * 1000")
        ),
        indexSql = listOf(
            "CREATE INDEX IF NOT EXISTS `index_follows_followerId` ON `follows` (`followerId`)",
            "CREATE INDEX IF NOT EXISTS `index_follows_followedId` ON `follows` (`followedId`)"
        )
    )

    private val deviceTokensPlan = TablePlan(
        name = "device_tokens",
        createSql = "CREATE TABLE IF NOT EXISTS `device_tokens` (`id` TEXT NOT NULL, `token` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        columns = listOf(
            ColumnSpec("id"),
            ColumnSpec("token", "''"),
            ColumnSpec("updatedAt", "CAST(strftime('%s','now') AS INTEGER) * 1000")
        )
    )

    private val latestPlans = listOf(
        usersPlan,
        chatsPlan,
        chatSettingsPlan,
        messagesPlan,
        videoStatsPlan,
        videoCommentsPlan,
        groupsPlan,
        groupMembersPlan,
        groupMessagesPlan,
        communitiesPlan,
        communityMembersPlan,
        communityMessagesPlan,
        hashtagsPlan,
        postHashtagsPlan,
        videoHashtagsPlan,
        followsPlan,
        deviceTokensPlan
    )

    fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys=OFF")
        db.beginTransaction()
        try {
            latestPlans.forEach { rebuildTable(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.execSQL("PRAGMA foreign_keys=ON")
        }

        db.query("PRAGMA foreign_key_check").use { cursor ->
            if (cursor.moveToFirst()) {
                var table = cursor.getString(0)
                throw IllegalStateException("Foreign key check failed after Room migration normalization on table=$table")
            }
        }

        Log.i(TAG, "Schema normalization completed for Room database v$APP_DATABASE_VERSION")
    }

    private fun rebuildTable(db: SupportSQLiteDatabase, plan: TablePlan) {
        val tempName = "${plan.name}__new"
        db.execSQL("DROP TABLE IF EXISTS `$tempName`")
        db.execSQL(plan.createSql.replace("`${plan.name}`", "`$tempName`"))

        if (tableExists(db, plan.name)) {
            val existingColumns = readColumns(db, plan.name)
            val targetColumns = plan.columns.joinToString(", ") { "`${it.name}`" }
            val sourceColumns = plan.columns.joinToString(", ") { column ->
                "${resolveExpression(column, existingColumns)} AS `${column.name}`"
            }
            db.execSQL(
                "INSERT INTO `$tempName` ($targetColumns) " +
                    "SELECT $sourceColumns FROM `${plan.name}`"
            )
            db.execSQL("DROP TABLE `${plan.name}`")
        }

        db.execSQL("ALTER TABLE `$tempName` RENAME TO `${plan.name}`")
        plan.indexSql.forEach(db::execSQL)
    }

    private fun resolveExpression(column: ColumnSpec, existingColumns: Set<String>): String {
        val sourceColumn = column.sourceCandidates.firstOrNull(existingColumns::contains)
        return when {
            sourceColumn != null && column.fallbackSql != null && column.coalesceNulls ->
                "COALESCE(`$sourceColumn`, ${column.fallbackSql})"
            sourceColumn != null -> "`$sourceColumn`"
            column.fallbackSql != null -> column.fallbackSql
            else -> "NULL"
        }
    }

    private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean {
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    private fun readColumns(db: SupportSQLiteDatabase, tableName: String): Set<String> {
        db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val columns = linkedSetOf<String>()
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                columns += cursor.getString(nameIndex)
            }
            return columns
        }
    }
}



