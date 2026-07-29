package conectate.com.di

import android.content.Context
import androidx.room.Room
import conectate.com.data.local.AppDatabase
import conectate.com.data.local.DatabaseMigrations
import conectate.com.data.local.dao.ChatDao
import conectate.com.data.local.dao.ChatSettingsDao
import conectate.com.data.local.dao.DeviceTokenDao
import conectate.com.data.local.dao.FollowDao
import conectate.com.data.local.dao.HashtagDao
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

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "conectate_db"
        )
            .addMigrations(*DatabaseMigrations.ALL)
            .fallbackToDestructiveMigrationOnDowngrade()
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

    @Provides
    fun provideHashtagDao(database: AppDatabase): HashtagDao {
        return database.hashtagDao()
    }

    @Provides
    fun provideFollowDao(database: AppDatabase): FollowDao {
        return database.followDao()
    }

    @Provides
    fun provideDeviceTokenDao(database: AppDatabase): DeviceTokenDao {
        return database.deviceTokenDao()
    }
}
