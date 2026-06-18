package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import conectate.com.data.local.entity.VideoStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoStatsDao {
    @Query("SELECT * FROM video_stats WHERE lower(videoId) = lower(:videoId) ORDER BY lastUpdated DESC LIMIT 1")
    fun getStatsFlow(videoId: String): Flow<VideoStatsEntity?>

    @Query("SELECT * FROM video_stats WHERE lower(videoId) = lower(:videoId) ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getStats(videoId: String): VideoStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStats(stats: VideoStatsEntity)

    @Query("DELETE FROM video_stats WHERE lower(videoId) = lower(:canonicalVideoId) AND videoId != :canonicalVideoId")
    suspend fun deleteStatsAliases(canonicalVideoId: String)

    @Query("UPDATE video_stats SET likeCount = :count, lastUpdated = :updatedAt WHERE lower(videoId) = lower(:videoId)")
    suspend fun updateLikeCount(videoId: String, count: Int, updatedAt: Long)

    @Query("UPDATE video_stats SET commentCount = :count, lastUpdated = :updatedAt WHERE lower(videoId) = lower(:videoId)")
    suspend fun updateCommentCount(videoId: String, count: Int, updatedAt: Long)
}
