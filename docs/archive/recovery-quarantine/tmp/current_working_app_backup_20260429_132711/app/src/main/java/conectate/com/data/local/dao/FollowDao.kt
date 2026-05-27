package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import conectate.com.data.local.entity.FollowEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun follow(followEntity: FollowEntity): Long

    @Delete
    suspend fun unfollow(followEntity: FollowEntity): Int

    @Query("SELECT COUNT(*) FROM follows WHERE followedId = :userId")
    suspend fun getFollowerCount(userId: String): Int

    @Query("SELECT COUNT(*) FROM follows WHERE followerId = :userId")
    suspend fun getFollowingCount(userId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM follows WHERE followerId = :followerId AND followedId = :followedId)")
    suspend fun isFollowing(followerId: String, followedId: String): Boolean

    @Query("SELECT u.* FROM users u JOIN follows f ON u.id = f.followedId WHERE f.followerId = :userId")
    fun getFollowing(userId: String): Flow<List<conectate.com.data.local.entity.UserEntity>>

    @Query("SELECT u.* FROM users u JOIN follows f ON u.id = f.followerId WHERE f.followedId = :userId")
    fun getFollowers(userId: String): Flow<List<conectate.com.data.local.entity.UserEntity>>
}
