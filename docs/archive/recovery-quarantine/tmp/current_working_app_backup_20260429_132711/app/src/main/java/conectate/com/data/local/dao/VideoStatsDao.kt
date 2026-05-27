package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import conectate.com.data.local.entity.VideoStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoStatsDao {
    @Query("SELECT * FROM video_stats WHERE videoId = :videoId")
    fun getStatsFlow(videoId: String): Flow<VideoStatsEntity?>

    @Query("SELECT * FROM video_stats WHERE videoId = :videoId")
    suspend fun getStats(videoId: String): VideoStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStats(stats: VideoStatsEntity)

    @Query("UPDATE video_stats SET likeCount = :count WHERE videoId = :videoId")
    suspend fun updateLikeCount(videoId: String, count: Int)

    @Query("UPDATE video_stats SET commentCount = :count WHERE videoId = :videoId")
    suspend fun updateCommentCount(videoId: String, count: Int)
}
