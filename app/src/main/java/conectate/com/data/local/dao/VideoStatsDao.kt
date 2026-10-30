package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import conectate.com.data.local.entity.VideoStatsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoStatsDao {
    @Query("SELECT * FROM video_stats WHERE lower(videoId) IN (:ids) ORDER BY lastUpdated DESC")
    suspend fun getStatsBatch(ids: List<String>): List<VideoStatsEntity>

    @Query("DELETE FROM video_stats WHERE lower(videoId) IN (:ids) AND videoId != lower(videoId)")
    suspend fun deleteStatsBatchAliases(ids: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatsBatch(stats: List<VideoStatsEntity>)

    /** One read/transaction for a feed page, rather than four round trips per video. */
    @Transaction
    suspend fun mergeServerPage(incoming: List<VideoStatsEntity>): List<VideoStatsEntity> {
        val canonical = incoming.filter { it.videoId.isNotBlank() }
            .map { it.copy(videoId = it.videoId.trim().lowercase()) }.distinctBy { it.videoId }
        if (canonical.isEmpty()) return emptyList()
        val ids = canonical.map { it.videoId }
        val previous = getStatsBatch(ids).groupBy { it.videoId.lowercase() }
        val merged = canonical.map { server ->
            server.copy(
                viewCount = maxOf(server.viewCount, previous[server.videoId].orEmpty().maxOfOrNull { it.viewCount } ?: 0),
                likeCount = server.likeCount.coerceAtLeast(0),
                commentCount = server.commentCount.coerceAtLeast(0),
                shareCount = server.shareCount.coerceAtLeast(0),
            )
        }
        deleteStatsBatchAliases(ids)
        insertStatsBatch(merged)
        return merged
    }
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
