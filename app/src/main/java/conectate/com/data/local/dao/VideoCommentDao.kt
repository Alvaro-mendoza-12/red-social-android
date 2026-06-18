package conectate.com.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import conectate.com.data.local.entity.VideoCommentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoCommentDao {
    @Query("SELECT * FROM video_comments WHERE lower(videoId) = lower(:videoId) ORDER BY createdAt DESC")
    fun getCommentsFlow(videoId: String): Flow<List<VideoCommentEntity>>

    @Query("SELECT * FROM video_comments WHERE lower(videoId) = lower(:videoId) ORDER BY createdAt DESC")
    suspend fun getComments(videoId: String): List<VideoCommentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: VideoCommentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComments(comments: List<VideoCommentEntity>)

    @Query("SELECT * FROM video_comments WHERE id = :commentId LIMIT 1")
    suspend fun getCommentById(commentId: String): VideoCommentEntity?

    @Query("DELETE FROM video_comments WHERE lower(videoId) = lower(:videoId)")
    suspend fun deleteCommentsByVideo(videoId: String)

    @Query("DELETE FROM video_comments WHERE id = :commentId")
    suspend fun deleteComment(commentId: String)
}
