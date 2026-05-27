package conectate.com.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.dao.ChatSettingsDao
import conectate.com.data.local.dao.DeviceTokenDao
import conectate.com.data.local.dao.FollowDao
import conectate.com.data.local.dao.HashtagDao
import conectate.com.data.local.dao.MessageDao
import conectate.com.data.local.dao.UserDao
import conectate.com.data.local.dao.VideoCommentDao
import conectate.com.data.local.dao.VideoStatsDao
import conectate.com.data.local.entity.HashtagEntity
import conectate.com.data.local.entity.PostHashtagEntity
import conectate.com.data.local.entity.VideoHashtagEntity
import conectate.com.data.local.entity.VideoCommentEntity
import conectate.com.data.local.entity.VideoStatsEntity
import conectate.com.data.local.entity.UserEntity
import conectate.com.data.local.entity.ChatEntity
import conectate.com.data.local.entity.ChatSettingsEntity
import conectate.com.data.local.entity.MessageEntity
import conectate.com.data.local.entity.GroupEntity
import conectate.com.data.local.entity.GroupMemberEntity
import conectate.com.data.local.entity.GroupMessageEntity
import conectate.com.data.local.entity.CommunityEntity
import conectate.com.data.local.entity.CommunityMemberEntity
import conectate.com.data.local.entity.CommunityMessageEntity
import conectate.com.data.local.entity.DeviceTokenEntity
import conectate.com.data.local.entity.FollowEntity
import conectate.com.data.local.dao.GroupDao
import conectate.com.data.local.dao.CommunityDao

const val APP_DATABASE_VERSION = 38

@Database(
    entities = [
        UserEntity::class, 
        ChatEntity::class, 
        ChatSettingsEntity::class,
        MessageEntity::class,
        VideoStatsEntity::class,
        VideoCommentEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        GroupMessageEntity::class,
        CommunityEntity::class,
        CommunityMemberEntity::class,
        CommunityMessageEntity::class,
        HashtagEntity::class,
        PostHashtagEntity::class,
        VideoHashtagEntity::class,
        FollowEntity::class,
        DeviceTokenEntity::class
    ],
    version = APP_DATABASE_VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun chatDao(): ChatDao
    abstract fun chatSettingsDao(): ChatSettingsDao
    abstract fun messageDao(): MessageDao
    abstract fun videoStatsDao(): VideoStatsDao
    abstract fun videoCommentDao(): VideoCommentDao
    abstract fun groupDao(): GroupDao
    abstract fun communityDao(): CommunityDao
    abstract fun hashtagDao(): HashtagDao
    abstract fun followDao(): FollowDao
    abstract fun deviceTokenDao(): DeviceTokenDao
}


